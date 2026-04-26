package com.arflix.tv

import android.app.Application
import android.graphics.Bitmap
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.data.repository.AuthRepository
import com.arflix.tv.data.repository.AuthState
import com.arflix.tv.data.repository.CloudSyncCoordinator
import com.arflix.tv.data.repository.CloudSyncRepository
import com.arflix.tv.data.repository.RealtimeSyncManager
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.util.AppLogger
import com.arflix.tv.util.CrashlyticsProvider
import com.arflix.tv.util.DeviceType
import com.arflix.tv.util.detectDeviceType
import com.arflix.tv.worker.TraktSyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import android.os.Build

/**
 * ARVIO TV Application class
 */
@HiltAndroidApp
class ArflixApplication : Application(), Configuration.Provider, ImageLoaderFactory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    @Inject
    lateinit var profileManager: ProfileManager
    @Inject
    lateinit var authRepository: AuthRepository
    @Inject
    lateinit var cloudSyncRepository: CloudSyncRepository
    @Inject
    lateinit var cloudSyncCoordinator: CloudSyncCoordinator
    @Inject
    lateinit var realtimeSyncManager: RealtimeSyncManager
    @Inject
    lateinit var watchlistRepository: WatchlistRepository

    override fun onCreate() {
        super.onCreate()
        instance = this

        // OkHttpProvider.init(context) just stashes the app context; it does
        // not build the OkHttpClient. Safe to keep on the main thread — it's
        // a single volatile assignment.
        OkHttpProvider.init(this)

        // Defer both the OkHttpClient lazy build AND the CloudStream HTTP
        // bridge to IO. Accessing OkHttpProvider.client triggers a ~tens-of-ms
        // disk-cache probe (File(cacheDir, "http_cache")); keeping that off
        // the main thread trims cold-start jank on first frame. Plugins loaded
        // later acquire the client through the same lazy path, so ordering is
        // preserved without blocking here.
        //
        // Warm DNS for TMDB image CDN so the very first image request on the
        // home screen doesn't block on DoH bootstrap + resolution.
        appScope.launch(Dispatchers.IO) {
            runCatching {
                com.arflix.tv.cloudstream.initCloudstream(OkHttpProvider.client)
            }
            runCatching { OkHttpProvider.dns.lookup("image.tmdb.org") }
        }

        // Initialize crash reporting (gracefully handles missing Firebase config)
        CrashlyticsProvider.initialize()
        // Initialize active profile asynchronously to avoid blocking cold start.
        // Wire realtime push notification
        cloudSyncRepository.onPushCompleted = { realtimeSyncManager.markPush() }

        appScope.launch {
            runCatching { profileManager.initialize() }
            // Preload watchlist cache in background for instant display
            runCatching { watchlistRepository.getWatchlistItems() }
            delay(2_500L)
            cloudSyncCoordinator.start()
            if (!authRepository.getCurrentUserId().isNullOrBlank()) {
                // Let first render/navigation settle before cloud restore and
                // WebSocket work compete with image decode and Compose lists.
                delay(20_000L)
                runCatching { cloudSyncRepository.pullFromCloud() }
                // Start realtime WebSocket listener for instant cross-device sync
                realtimeSyncManager.start()
            }
        }

        // Observe auth state: start realtime on login, stop on logout
        appScope.launch {
            authRepository.authState.collectLatest { state ->
                if (state is AuthState.Authenticated) {
                    delay(20_000L)
                    if (!authRepository.getCurrentUserId().isNullOrBlank()) {
                        cloudSyncCoordinator.start()
                        realtimeSyncManager.start()
                    }
                } else {
                    realtimeSyncManager.stop()
                    cloudSyncCoordinator.stop()
                }
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        val isTvDevice = detectDeviceType(this) == DeviceType.TV
        return ImageLoader.Builder(this)
            // Use the dedicated Coil HTTP client instead of the main API client.
            // Avoids logging interceptor overhead and connection pool contention.
            .okHttpClient(OkHttpProvider.coilClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(if (isTvDevice) 0.11 else 0.14)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(if (isTvDevice) 128L * 1024L * 1024L else 96L * 1024L * 1024L)
                    .build()
            }
            .crossfade(false)
            .respectCacheHeaders(false)
            .allowRgb565(true)
            .bitmapConfig(Bitmap.Config.RGB_565)
            // No global placeholder — card composables use their own surface
            // background color as the visual placeholder. A global placeholder
            // causes a dark-rectangle flash behind transparent clearlogo PNGs
            // on the home hero. Error = transparent so failed loads are invisible
            // (the card surface background is the fallback visual).
            .error(android.R.color.transparent)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.ASSERT)
            .build()

    /**
     * Schedule periodic Trakt data sync
     */
    fun scheduleTraktSyncIfNeeded() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Use INCREMENTAL sync on startup for fast app launch
        // Full sync only happens on periodic schedule or explicit user action
        val oneTimeRequest = OneTimeWorkRequestBuilder<TraktSyncWorker>()
            .setConstraints(constraints)
            // Defer startup sync to keep first-run navigation and scrolling smooth.
            .setInitialDelay(2, TimeUnit.MINUTES)
            .setInputData(
                workDataOf(TraktSyncWorker.INPUT_SYNC_MODE to TraktSyncWorker.SYNC_MODE_INCREMENTAL)
            )
            .addTag(TraktSyncWorker.TAG)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<TraktSyncWorker>(
            TraktSyncWorker.SYNC_INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .addTag(TraktSyncWorker.TAG)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            TraktSyncWorker.WORK_NAME_ON_OPEN,
            ExistingWorkPolicy.KEEP,
            oneTimeRequest
        )

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            TraktSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    companion object {
        lateinit var instance: ArflixApplication
            private set
    }
}





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
import com.arflix.tv.data.repository.CloudSyncRepository
import com.arflix.tv.data.repository.RealtimeSyncManager
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.util.AppLogger
import com.arflix.tv.util.CrashlyticsProvider
import com.arflix.tv.worker.TraktSyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    lateinit var realtimeSyncManager: RealtimeSyncManager
    @Inject
    lateinit var watchlistRepository: WatchlistRepository

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize OkHttp disk cache before any network calls
        OkHttpProvider.init(this)

        // Bridge arvio's shared OkHttpClient into the CloudStream plugin
        // runtime's global `app` accessor so any `.cs3` plugin loaded later
        // performs HTTP through the same DNS-over-HTTPS, connection pool,
        // and interceptors as the rest of the app.
        com.lagradost.cloudstream3.setCloudstreamHttpClient(OkHttpProvider.client)

        // Warm DNS for TMDB image CDN so the very first image request on the home
        // screen doesn't block on DNS-over-HTTPS bootstrap + resolution. Without
        // this, the first batch of card images can take 1-3s extra on cold start
        // while DoH resolves image.tmdb.org. The SettingsViewModel already does
        // this when the DNS provider changes; doing it at app start ensures the
        // first home load is fast regardless of provider.
        appScope.launch(Dispatchers.IO) {
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
            if (!authRepository.getCurrentUserId().isNullOrBlank()) {
                // Pull cloud state shortly after startup for faster cross-device sync.
                delay(3_000L)
                runCatching { cloudSyncRepository.pullFromCloud() }
                // Start realtime WebSocket listener for instant cross-device sync
                realtimeSyncManager.start()
            }
        }

        // Observe auth state: start realtime on login, stop on logout
        appScope.launch {
            authRepository.authState.collect { state ->
                if (state is AuthState.Authenticated) {
                    realtimeSyncManager.start()
                } else {
                    realtimeSyncManager.stop()
                }
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // Use the dedicated Coil HTTP client instead of the main API client.
            // Avoids logging interceptor overhead and connection pool contention.
            .okHttpClient(OkHttpProvider.coilClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    // Bumped from 15% to 20% to reduce cache thrashing on the home
                    // screen where 10+ cards are visible simultaneously.
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    // Bumped from 48 MB to 128 MB to hold more original-quality
                    // backdrops across sessions. Original TMDB backdrops are 1-5 MB
                    // each; 128 MB caches 25-128 unique backdrops, covering several
                    // home screen visits worth of content. This means repeat visits
                    // load entirely from disk with no network, keeping original
                    // quality AND instant load speed.
                    .maxSizeBytes(128L * 1024L * 1024L)
                    .build()
            }
            .crossfade(200)
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





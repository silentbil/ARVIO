package com.arflix.tv

import android.app.Application
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
import coil.Coil
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.data.repository.AuthRepository
import com.arflix.tv.data.repository.CloudSyncRepository
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.util.CrashlyticsProvider
import com.arflix.tv.util.settingsDataStore
import com.arflix.tv.worker.TraktSyncWorker
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * ARVIO TV Application class
 */
@HiltAndroidApp
class ArflixApplication : Application(), Configuration.Provider {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    @Inject
    lateinit var profileManager: ProfileManager
    @Inject
    lateinit var authRepository: AuthRepository
    @Inject
    lateinit var cloudSyncRepository: CloudSyncRepository
    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize OkHttp disk cache before any network calls
        OkHttpProvider.init(this)

        // Initialize the global loader immediately; profile DNS is applied asynchronously.
        Coil.setImageLoader(OkHttpProvider.createCoilImageLoader(this))

        appScope.launch {
            profileManager.initialize()
            applyProfileDnsAndRefreshCoil()
        }

        // Keep Coil loader in sync when active profile changes.
        appScope.launch {
            profileManager.activeProfileId.collect {
                applyProfileDnsAndRefreshCoil()
            }
        }

        // Initialize crash reporting (gracefully handles missing Firebase config)
        CrashlyticsProvider.initialize()
        // Initialize active profile asynchronously to avoid blocking cold start.
        appScope.launch {
            if (!authRepository.getCurrentUserId().isNullOrBlank()) {
                // Keep cold-start smooth: perform first cloud pull after startup settles.
                delay(12_000L)
                runCatching { cloudSyncRepository.pullFromCloud() }
            }
        }
    }

    private suspend fun applyProfileDnsAndRefreshCoil() {
        val prefs = settingsDataStore.data.first()
        val raw = prefs[profileManager.profileStringKey("dns_provider")]
        OkHttpProvider.setDnsProvider(OkHttpProvider.parseDnsProvider(raw))
        Coil.setImageLoader(OkHttpProvider.createCoilImageLoader(this))
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




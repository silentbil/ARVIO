package com.arflix.tv.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudSyncCoordinator @Inject constructor(
    private val invalidationBus: CloudSyncInvalidationBus,
    private val cloudSyncRepository: CloudSyncRepository,
    private val authRepository: AuthRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectorJob: Job? = null
    private var flushJob: Job? = null

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        collectorJob = scope.launch {
            invalidationBus.events.collectLatest { invalidation ->
                if (authRepository.getCurrentUserId().isNullOrBlank()) return@collectLatest
                cloudSyncRepository.markLocalStateDirty()
                scheduleFlush(invalidation)
            }
        }
    }

    fun stop() {
        started = false
        collectorJob?.cancel()
        flushJob?.cancel()
        collectorJob = null
        flushJob = null
    }

    private fun scheduleFlush(invalidation: CloudSyncInvalidation) {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(debounceMsFor(invalidation.scope))
            if (authRepository.getCurrentUserId().isNullOrBlank()) return@launch
            runCatching { cloudSyncRepository.pushToCloud() }
                .onFailure { error ->
                    Log.w("CloudSyncCoordinator", "Cloud push failed after ${invalidation.scope}: ${error.message}")
                    cloudSyncRepository.markLocalStateDirty()
                }
        }
    }

    private fun debounceMsFor(scope: CloudSyncScope): Long {
        return when (scope) {
            CloudSyncScope.LOCAL_HISTORY -> 2_000L
            CloudSyncScope.IPTV -> 750L
            else -> 500L
        }
    }
}

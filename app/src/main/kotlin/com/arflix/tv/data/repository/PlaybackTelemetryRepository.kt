package com.arflix.tv.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackTelemetryRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val startupSamplesKey = longPreferencesKey("telemetry_startup_samples_v1")
    private val startupAvgMsKey = longPreferencesKey("telemetry_startup_avg_ms_v1")
    private val startupRetriesKey = longPreferencesKey("telemetry_startup_retries_v1")
    private val failoverAttemptsKey = longPreferencesKey("telemetry_failover_attempts_v1")
    private val failoverSuccessesKey = longPreferencesKey("telemetry_failover_successes_v1")
    private val longRebuffersKey = longPreferencesKey("telemetry_long_rebuffers_v1")
    private val playbackFailuresKey = longPreferencesKey("telemetry_playback_failures_v1")
    private val lastStartupMsKey = longPreferencesKey("telemetry_last_startup_ms_v1")
    private val lastSessionRetriesKey = intPreferencesKey("telemetry_last_session_retries_v1")

    suspend fun recordStartup(startupMs: Long, retries: Int, failoversBeforeStart: Int) {
        val safeStartup = startupMs.coerceAtLeast(0L)
        val safeRetries = retries.coerceAtLeast(0)
        val safeFailovers = failoversBeforeStart.coerceAtLeast(0)

        context.settingsDataStore.edit { prefs ->
            val oldSamples = prefs[startupSamplesKey] ?: 0L
            val oldAvg = prefs[startupAvgMsKey] ?: 0L
            val newSamples = oldSamples + 1L
            val newAvg = if (oldSamples <= 0L) {
                safeStartup
            } else {
                ((oldAvg * oldSamples) + safeStartup) / newSamples
            }

            prefs[startupSamplesKey] = newSamples
            prefs[startupAvgMsKey] = newAvg
            prefs[lastStartupMsKey] = safeStartup
            prefs[startupRetriesKey] = (prefs[startupRetriesKey] ?: 0L) + safeRetries
            prefs[lastSessionRetriesKey] = safeRetries

            if (safeFailovers > 0) {
                prefs[failoverAttemptsKey] = (prefs[failoverAttemptsKey] ?: 0L) + safeFailovers
                prefs[failoverSuccessesKey] = (prefs[failoverSuccessesKey] ?: 0L) + safeFailovers
            }
        }
    }

    suspend fun recordFailoverAttempt(success: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[failoverAttemptsKey] = (prefs[failoverAttemptsKey] ?: 0L) + 1L
            if (success) {
                prefs[failoverSuccessesKey] = (prefs[failoverSuccessesKey] ?: 0L) + 1L
            }
        }
    }

    suspend fun recordLongRebuffer() {
        context.settingsDataStore.edit { prefs ->
            prefs[longRebuffersKey] = (prefs[longRebuffersKey] ?: 0L) + 1L
        }
    }

    suspend fun recordPlaybackFailure() {
        context.settingsDataStore.edit { prefs ->
            prefs[playbackFailuresKey] = (prefs[playbackFailuresKey] ?: 0L) + 1L
        }
    }
}

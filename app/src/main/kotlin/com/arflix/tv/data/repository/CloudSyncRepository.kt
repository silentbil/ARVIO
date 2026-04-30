package com.arflix.tv.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.repository.ContinueWatchingItem
import com.arflix.tv.ui.components.CARD_LAYOUT_MODE_LANDSCAPE
import com.arflix.tv.ui.components.catalogueRowLayoutKeyFromPreferenceName
import com.arflix.tv.ui.components.catalogueRowLayoutPreferencePrefixFor
import com.arflix.tv.ui.components.normalizeCardLayoutMode
import com.arflix.tv.ui.components.profileCatalogueRowLayoutModeKey
import com.arflix.tv.util.LAST_APP_LANGUAGE_KEY
import com.arflix.tv.util.SKIP_PROFILE_SELECTION_KEY
import com.arflix.tv.util.settingsDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared cloud sync logic used by both SettingsViewModel (full push/pull on
 * Settings screen) and ProfileViewModel (pull on profile selection).
 *
 * This repository handles the data layer: build the cloud JSON snapshot,
 * push it to the server, and restore cloud state to local repositories.
 * UI-specific concerns (toasts, loading indicators) are left to the ViewModels.
 */
@Singleton
class CloudSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val profileManager: ProfileManager,
    private val catalogRepository: CatalogRepository,
    private val iptvRepository: IptvRepository,
    private val streamRepository: StreamRepository,
    private val traktRepository: TraktRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val watchlistRepository: WatchlistRepository,
    private val invalidationBus: CloudSyncInvalidationBus
) {
    private val gson = Gson()

    private fun mergeAddonsForSharedRestore(addonLists: Iterable<List<Addon>>): List<Addon> {
        val merged = LinkedHashMap<String, Addon>()
        addonLists.flatten().forEach { addon ->
            val id = addon.id.trim()
            if (id.isNotBlank()) {
                merged.putIfAbsent(id, addon)
            }
        }
        return merged.values.toList()
    }

    private fun mergeRepositoriesForSharedRestore(
        repositoryLists: Iterable<List<CloudstreamRepositoryRecord>>
    ): List<CloudstreamRepositoryRecord> {
        val merged = LinkedHashMap<String, CloudstreamRepositoryRecord>()
        repositoryLists.flatten().forEach { repository ->
            val url = repository.url.trim()
            if (url.isNotBlank()) {
                merged.putIfAbsent(url.lowercase(), repository.copy(url = url))
            }
        }
        return merged.values.toList()
    }

    /**
     * Serializes push/pull so they can never overlap. Without this, a manual
     * Save in Settings that coincides with a realtime pull (or a startup pull
     * that coincides with an auto-push) can interleave DataStore writes and
     * leave the local cache diverged from the cloud — which showed up as
     * "sync silently stops, needs logout/login to recover".
     */
    private val cloudSyncMutex = Mutex()

    /** Callback invoked after a successful push so realtime listeners can skip the echo. */
    var onPushCompleted: (() -> Unit)? = null

    /**
     * Dirty flag: set to true when a push fails, cleared on next successful push.
     * Callers (HomeViewModel.pullCloudStateOnResume, RealtimeSyncManager periodic sync)
     * can check this and retry the push so failed pushes aren't permanently lost.
     */
    @Volatile
    var isPushDirty: Boolean = false
        private set

    fun markLocalStateDirty() {
        isPushDirty = true
    }

    enum class RestoreResult { RESTORED, NO_BACKUP, FAILED }

    // ── Data class for per-profile settings stored in cloud ──

    data class CloudProfileSettings(
        val defaultSubtitle: String = "Off",
        val defaultAudioLanguage: String = "Auto (Original)",
        val contentLanguage: String = "en-US",
        val subtitleSize: String = "Medium",
        val subtitleColor: String = "White",
        val cardLayoutMode: String = CARD_LAYOUT_MODE_LANDSCAPE,
        val frameRateMatchingMode: String = "Off",
        val autoPlayNext: Boolean = true,
        val autoPlaySingleSource: Boolean = true,
        val autoPlayMinQuality: String = "Any",
        val trailerAutoPlay: Boolean = false,
        val clockFormat: String = "24h",
        val showBudget: Boolean = true,
        val volumeBoostDb: Int = 0,
        val includeSpecials: Boolean = false,
        val dnsProvider: String = "system",
        val subtitleUsageJson: String = "",
        val subtitleSettingsUpdatedAt: Long = 0L,
        val iptvHiddenGroups: String = "",
        val iptvGroupOrder: String = "",
        val secondarySubtitle: String = "Off",
        val filterSubtitlesByLanguage: Boolean = true,
        val catalogueRowLayoutModes: Map<String, String> = emptyMap()
    )

    // ── DataStore key helpers ──

    private fun contentLanguageKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, "content_language")
    private fun trailerAutoPlayKeyFor(profileId: String) =
        profileManager.profileBooleanKeyFor(profileId, "trailer_auto_play")
    private fun clockFormatKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, "clock_format")
    private fun showBudgetKeyFor(profileId: String) =
        profileManager.profileBooleanKeyFor(profileId, "show_budget_on_home")
    private fun volumeBoostDbKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, "volume_boost_db")
    private fun dnsProviderKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, "dns_provider")
    private fun subtitleUsageKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, "subtitle_usage_v1")
    private fun subtitleSettingsUpdatedAtKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, "subtitle_settings_updated_at")

    private fun subtitleSizeKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, "subtitle_size")
    private fun subtitleColorKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, "subtitle_color")
    private fun iptvHiddenGroupsKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, "iptv_hidden_groups")
    private fun iptvGroupOrderKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, "iptv_group_order")
    private fun secondarySubtitleKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, "secondary_subtitle")
    private fun filterSubtitlesByLanguageKeyFor(profileId: String) =
        profileManager.profileBooleanKeyFor(profileId, "filter_subtitles_by_lang")
    private fun defaultSubtitleKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, "default_subtitle")
    private fun defaultAudioLanguageKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, "default_audio_language")
    private fun cardLayoutModeKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, "card_layout_mode")
    private fun frameRateMatchingModeKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, "frame_rate_matching_mode")
    private fun autoPlayNextKeyFor(profileId: String) =
        profileManager.profileBooleanKeyFor(profileId, "auto_play_next")
    private fun autoPlaySingleSourceKeyFor(profileId: String) =
        profileManager.profileBooleanKeyFor(profileId, "auto_play_single_source")
    private fun autoPlayMinQualityKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, "auto_play_min_quality")
    private fun includeSpecialsKeyFor(profileId: String) =
        profileManager.profileBooleanKeyFor(profileId, "include_specials")
    private fun dnsProviderKey() = profileManager.profileStringKey("dns_provider")
    private fun subtitleUsageKey() = profileManager.profileStringKey("subtitle_usage_v1")
    private fun subtitleSettingsUpdatedAtKey() = profileManager.profileStringKey("subtitle_settings_updated_at")

    // Active-profile key shortcuts (used for legacy flat fields in snapshot)
    private fun defaultSubtitleKey() = profileManager.profileStringKey("default_subtitle")
    private fun defaultAudioLanguageKey() = profileManager.profileStringKey("default_audio_language")
    private fun cardLayoutModeKey() = profileManager.profileStringKey("card_layout_mode")
    private fun frameRateMatchingModeKey() = profileManager.profileStringKey("frame_rate_matching_mode")
    private fun autoPlayNextKey() = profileManager.profileBooleanKey("auto_play_next")
    private fun autoPlaySingleSourceKey() = profileManager.profileBooleanKey("auto_play_single_source")
    private fun autoPlayMinQualityKey() = profileManager.profileStringKey("auto_play_min_quality")
    private fun includeSpecialsKey() = profileManager.profileBooleanKey("include_specials")

    private fun catalogueRowLayoutModesForProfile(
        prefs: androidx.datastore.preferences.core.Preferences,
        profileId: String
    ): Map<String, String> {
        val prefix = catalogueRowLayoutPreferencePrefixFor(profileId)
        return prefs.asMap()
            .mapNotNull { (key, value) ->
                val rowKey = catalogueRowLayoutKeyFromPreferenceName(profileId, key.name)
                    ?: return@mapNotNull null
                val normalized = normalizeCardLayoutMode(value as? String)
                if (key.name.startsWith(prefix)) rowKey to normalized else null
            }
            .toMap()
    }

    // ── Normalize helpers ──

    private fun normalizeFrameRateMode(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            "off" -> "Off"
            "seamless", "seamless only", "only if seamless", "only_if_seamless" -> "Seamless only"
            "always" -> "Always"
            else -> "Off"
        }
    }

    private fun normalizeAutoPlayMinQuality(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            "any" -> "Any"
            "720p", "hd" -> "720p"
            "1080p", "fullhd", "fhd" -> "1080p"
            "4k", "2160p", "uhd" -> "4K"
            else -> "Any"
        }
    }

    // ══════════════════════════════════════════════════════════
    //  BUILD CLOUD SNAPSHOT JSON
    // ══════════════════════════════════════════════════════════

    /**
     * Builds the full cloud snapshot JSON for all profiles.
     * This replaces the SettingsViewModel version and does NOT reference any UI state.
     */
    suspend fun buildCloudSnapshotJson(): String {
        val prefs = context.settingsDataStore.data.first()
        val root = JSONObject()
        val profiles = profileRepository.getProfiles()

        // Per-profile settings
        val profileSettingsById = buildMap<String, CloudProfileSettings> {
            profiles.forEach { profile ->
                put(
                    profile.id,
                    CloudProfileSettings(
                        defaultSubtitle = prefs[defaultSubtitleKeyFor(profile.id)] ?: "Off",
                        defaultAudioLanguage = prefs[defaultAudioLanguageKeyFor(profile.id)] ?: "Auto (Original)",
                        contentLanguage = prefs[contentLanguageKeyFor(profile.id)] ?: "en-US",

                        trailerAutoPlay = prefs[trailerAutoPlayKeyFor(profile.id)] ?: false,
                        clockFormat = prefs[clockFormatKeyFor(profile.id)] ?: "24h",
                        showBudget = prefs[showBudgetKeyFor(profile.id)] ?: true,
                        volumeBoostDb = prefs[volumeBoostDbKeyFor(profile.id)]?.toIntOrNull()?.coerceIn(0, 15) ?: 0,
                        dnsProvider = prefs[dnsProviderKeyFor(profile.id)] ?: "system",
                        subtitleUsageJson = prefs[subtitleUsageKeyFor(profile.id)] ?: "",
                        subtitleSettingsUpdatedAt = prefs[subtitleSettingsUpdatedAtKeyFor(profile.id)]?.toLongOrNull() ?: 0L,
                        subtitleSize = prefs[subtitleSizeKeyFor(profile.id)] ?: "Medium",
                        subtitleColor = prefs[subtitleColorKeyFor(profile.id)] ?: "White",
                        iptvHiddenGroups = prefs[iptvHiddenGroupsKeyFor(profile.id)] ?: "",
                        iptvGroupOrder = prefs[iptvGroupOrderKeyFor(profile.id)] ?: "",
                        secondarySubtitle = prefs[secondarySubtitleKeyFor(profile.id)] ?: "Off",
                        filterSubtitlesByLanguage = prefs[filterSubtitlesByLanguageKeyFor(profile.id)] ?: true,
                        catalogueRowLayoutModes = catalogueRowLayoutModesForProfile(prefs, profile.id),
                        cardLayoutMode = normalizeCardLayoutMode(
                            prefs[cardLayoutModeKeyFor(profile.id)] ?: CARD_LAYOUT_MODE_LANDSCAPE
                        ),
                        frameRateMatchingMode = normalizeFrameRateMode(
                            prefs[frameRateMatchingModeKeyFor(profile.id)] ?: "Off"
                        ),
                        autoPlayNext = prefs[autoPlayNextKeyFor(profile.id)] ?: true,
                        autoPlaySingleSource = prefs[autoPlaySingleSourceKeyFor(profile.id)] ?: true,
                        autoPlayMinQuality = normalizeAutoPlayMinQuality(
                            prefs[autoPlayMinQualityKeyFor(profile.id)] ?: "Any"
                        ),
                        includeSpecials = prefs[includeSpecialsKeyFor(profile.id)] ?: false
                    )
                )
            }
        }

        root.put("version", 1)
        root.put("updatedAt", System.currentTimeMillis())
        // Legacy flat fields (for backward compat with older clients)
        root.put("defaultSubtitle", prefs[defaultSubtitleKey()] ?: "Off")
        root.put("defaultAudioLanguage", prefs[defaultAudioLanguageKey()] ?: "Auto (Original)")
        root.put("cardLayoutMode", normalizeCardLayoutMode(prefs[cardLayoutModeKey()] ?: CARD_LAYOUT_MODE_LANDSCAPE))
        root.put("frameRateMatchingMode", prefs[frameRateMatchingModeKey()] ?: "Off")
        root.put("autoPlayNext", prefs[autoPlayNextKey()] ?: true)
        root.put("autoPlaySingleSource", prefs[autoPlaySingleSourceKey()] ?: true)
        root.put("autoPlayMinQuality", normalizeAutoPlayMinQuality(prefs[autoPlayMinQualityKey()] ?: "Any"))
        root.put("includeSpecials", prefs[includeSpecialsKey()] ?: false)
        root.put("dnsProvider", prefs[dnsProviderKey()] ?: "system")
        root.put("subtitleUsageJson", prefs[subtitleUsageKey()] ?: "")
        root.put("subtitleSettingsUpdatedAt", prefs[subtitleSettingsUpdatedAtKey()]?.toLongOrNull() ?: 0L)
        root.put("skipProfileSelection", prefs[SKIP_PROFILE_SELECTION_KEY] ?: false)

        root.put("activeProfileId", profileRepository.getActiveProfileId() ?: JSONObject.NULL)
        root.put("profiles", JSONArray(gson.toJson(profiles)))
        root.put("profileSettingsById", JSONObject(gson.toJson(profileSettingsById)))

        // Trakt tokens per profile
        val traktTokens = traktRepository.exportTokensForProfiles(profiles.map { it.id })
        root.put("traktTokens", JSONObject(gson.toJson(traktTokens)))

        // Dismissed Continue Watching keys per profile (persist hide/remove state)
        val dismissedContinueWatchingByProfile =
            traktRepository.exportDismissedContinueWatchingForProfiles(profiles.map { it.id })
        root.put(
            "dismissedContinueWatchingByProfile",
            JSONObject(gson.toJson(dismissedContinueWatchingByProfile))
        )

        val localContinueWatchingByProfile =
            traktRepository.exportLocalContinueWatchingForProfiles(profiles.map { it.id })
        root.put(
            "localContinueWatchingByProfile",
            JSONObject(gson.toJson(localContinueWatchingByProfile))
        )

        val localWatchedMoviesByProfile =
            traktRepository.exportLocalWatchedMoviesForProfiles(profiles.map { it.id })
        root.put(
            "localWatchedMoviesByProfile",
            JSONObject(gson.toJson(localWatchedMoviesByProfile))
        )

        val localWatchedEpisodesByProfile =
            traktRepository.exportLocalWatchedEpisodesForProfiles(profiles.map { it.id })
        root.put(
            "localWatchedEpisodesByProfile",
            JSONObject(gson.toJson(localWatchedEpisodesByProfile))
        )

        // Addons are shared account state. Keep the per-profile payload shape
        // for older clients, but each profile receives the same shared list.
        val sharedAddons = sanitizeAddonsForCloudSync(streamRepository.installedAddons.first())
        val addonsByProfile = buildMap<String, List<Addon>> {
            profiles.forEach { profile ->
                put(profile.id, sharedAddons)
            }
        }
        root.put("addonsByProfile", JSONObject(gson.toJson(addonsByProfile)))

        val sharedCloudstreamRepositories = mergeCloudstreamRepositoriesFromAddons(
            streamRepository.cloudstreamRepositories.first(),
            sharedAddons
        )
        val cloudstreamRepositoriesByProfile = buildMap<String, List<CloudstreamRepositoryRecord>> {
            profiles.forEach { profile ->
                put(profile.id, sharedCloudstreamRepositories)
            }
        }
        root.put(
            "cloudstreamRepositoriesByProfile",
            JSONObject(gson.toJson(cloudstreamRepositoriesByProfile))
        )

        // Catalogs per profile
        val catalogsByProfile = buildMap<String, List<CatalogConfig>> {
            profiles.forEach { profile ->
                put(profile.id, catalogRepository.getCatalogsForProfile(profile.id))
            }
        }
        root.put("catalogsByProfile", JSONObject(gson.toJson(catalogsByProfile)))

        // Hidden preinstalled catalogs per profile
        val hiddenPreinstalledByProfile = buildMap<String, List<String>> {
            profiles.forEach { profile ->
                put(profile.id, catalogRepository.getHiddenPreinstalledCatalogIdsForProfile(profile.id))
            }
        }
        root.put("hiddenPreinstalledByProfile", JSONObject(gson.toJson(hiddenPreinstalledByProfile)))

        // Hidden addon catalogs per profile — without this, a deletion on one
        // device is undone by another device's next addon sync.
        val hiddenAddonByProfile = buildMap<String, List<String>> {
            profiles.forEach { profile ->
                put(profile.id, catalogRepository.getHiddenAddonCatalogIdsForProfile(profile.id))
            }
        }
        root.put("hiddenAddonByProfile", JSONObject(gson.toJson(hiddenAddonByProfile)))

        // IPTV config per profile (including favorites)
        val iptvByProfile = buildMap<String, IptvCloudProfileState> {
            profiles.forEach { profile ->
                put(profile.id, iptvRepository.exportCloudConfigForProfile(profile.id))
            }
        }
        root.put("iptvByProfile", JSONObject(gson.toJson(iptvByProfile)))

        // Watchlist per profile
        val watchlistByProfile = buildMap<String, List<LocalWatchlistItem>> {
            profiles.forEach { profile ->
                put(profile.id, watchlistRepository.exportWatchlistForProfile(profile.id))
            }
        }
        root.put("watchlistByProfile", JSONObject(gson.toJson(watchlistByProfile)))

        // Backward compatibility fields (legacy single-profile clients)
        root.put("addons", JSONArray(gson.toJson(sharedAddons)))
        root.put("catalogs", JSONArray(gson.toJson(catalogRepository.getCatalogs())))
        root.put(
            "hiddenPreinstalledCatalogs",
            JSONArray(gson.toJson(catalogRepository.getHiddenPreinstalledCatalogIdsForActiveProfile()))
        )
        val iptvConfig = iptvRepository.observeConfig().first()
        root.put("iptvM3uUrl", iptvConfig.m3uUrl)
        root.put("iptvEpgUrl", iptvConfig.epgUrl)
        root.put("iptvFavoriteGroups", JSONArray(gson.toJson(iptvRepository.observeFavoriteGroups().first())))
        root.put("iptvFavoriteChannels", JSONArray(gson.toJson(iptvRepository.observeFavoriteChannels().first())))

        // Informational
        val isTraktLinked = traktRepository.isAuthenticated.first()
        root.put("traktLinked", isTraktLinked)
        root.put("traktExpiration", JSONObject.NULL)

        return root.toString()
    }

    // ══════════════════════════════════════════════════════════
    //  PUSH LOCAL STATE TO CLOUD
    // ══════════════════════════════════════════════════════════

    suspend fun pushToCloud(): Result<Unit> = cloudSyncMutex.withLock {
        if (authRepository.getCurrentUserId().isNullOrBlank()) {
            return@withLock Result.failure(IllegalStateException("Not logged in"))
        }
        val payload = runCatching { buildCloudSnapshotJson() }.getOrElse {
            isPushDirty = true
            return@withLock Result.failure(it)
        }
        val result = authRepository.saveAccountSyncPayload(payload)
        if (result.isSuccess) {
            isPushDirty = false
            onPushCompleted?.invoke()
        } else {
            // Mark dirty so the next ON_RESUME or periodic sync retries the push.
            // Without this, a single network hiccup would permanently diverge the
            // cloud state until the user explicitly changes another setting.
            isPushDirty = true
        }
        result
    }

    // ══════════════════════════════════════════════════════════
    //  PULL CLOUD STATE TO LOCAL
    // ══════════════════════════════════════════════════════════

    /**
     * Restores the full cloud state to local repositories.
     * Returns [RestoreResult] indicating what happened.
     */
    suspend fun pullFromCloud(): RestoreResult = cloudSyncMutex.withLock {
        val payloadResult = authRepository.loadAccountSyncPayload()
        if (payloadResult.isFailure) return@withLock RestoreResult.FAILED

        val payload = payloadResult.getOrNull().orEmpty()
        if (payload.isBlank()) return@withLock RestoreResult.NO_BACKUP

        runCatching {
            invalidationBus.suppressDuringRemoteApply {
                applyCloudPayload(payload)
            }
        }.fold(
            onSuccess = { RestoreResult.RESTORED },
            onFailure = { e ->
                System.err.println("[CLOUD-SYNC] pullFromCloud failed: ${e.message}")
                RestoreResult.FAILED
            }
        )
    }

    /**
     * Applies a cloud JSON payload to all local repositories.
     */
    private suspend fun applyCloudPayload(payload: String) {
        val root = JSONObject(payload)

        val fallbackDefaultSubtitle = root.optString("defaultSubtitle", "Off")
        val fallbackDefaultAudioLanguage = root.optString("defaultAudioLanguage", "Auto (Original)")
        val fallbackCardLayoutMode = normalizeCardLayoutMode(root.optString("cardLayoutMode", CARD_LAYOUT_MODE_LANDSCAPE))
        val fallbackFrameRateMatchingMode = normalizeFrameRateMode(root.optString("frameRateMatchingMode", "Off"))
        val fallbackAutoPlayNext = root.optBoolean("autoPlayNext", true)
        val fallbackAutoPlaySingleSource = root.optBoolean("autoPlaySingleSource", true)
        val fallbackAutoPlayMinQuality = normalizeAutoPlayMinQuality(root.optString("autoPlayMinQuality", "Any"))
        val fallbackIncludeSpecials = root.optBoolean("includeSpecials", false)
        val fallbackSubtitleSettingsUpdatedAt = root.optLong("subtitleSettingsUpdatedAt", 0L)
        var preservedNewerLocalSubtitle = false

        // ── Profiles ──
        root.optJSONArray("profiles")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
            val type = object : TypeToken<List<Profile>>() {}.type
            val profiles: List<Profile> = gson.fromJson(json, type) ?: emptyList()
            val activeProfileId = root.optString("activeProfileId").ifBlank { null }
            if (profiles.isNotEmpty()) {
                // Preserve local active profile if it exists in cloud set
                val localActiveId = profileRepository.getActiveProfileId()
                val effectiveActiveId = if (localActiveId != null &&
                    profiles.any { it.id == localActiveId }
                ) localActiveId else activeProfileId
                profileRepository.replaceProfilesFromCloud(profiles, effectiveActiveId)
            }
        }

        val allProfiles = profileRepository.getProfiles()
        val activeProfileId = profileRepository.getActiveProfileId()
            ?: allProfiles.firstOrNull()?.id ?: "default"

        // ── Per-profile settings ──
        root.optJSONObject("profileSettingsById")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
            val type = object : TypeToken<Map<String, CloudProfileSettings>>() {}.type
            val settingsByProfile: Map<String, CloudProfileSettings> = gson.fromJson(json, type) ?: emptyMap()
            if (settingsByProfile.isNotEmpty()) {
                context.settingsDataStore.edit { prefs ->
                    settingsByProfile.forEach { (profileId, state) ->
                        val defaultSubtitleKey = defaultSubtitleKeyFor(profileId)
                        val subtitleUpdatedAtKey = subtitleSettingsUpdatedAtKeyFor(profileId)
                        val localSubtitle = prefs[defaultSubtitleKey]?.trim().orEmpty()
                        val localSubtitleUpdatedAt = prefs[subtitleUpdatedAtKey]?.toLongOrNull() ?: 0L
                        val cloudSubtitle = state.defaultSubtitle.trim().ifBlank { "Off" }
                        val keepLocalSubtitle = localSubtitle.isNotBlank() &&
                            !localSubtitle.equals(cloudSubtitle, ignoreCase = true) &&
                            (
                                localSubtitleUpdatedAt > state.subtitleSettingsUpdatedAt ||
                                    (
                                        state.subtitleSettingsUpdatedAt <= 0L &&
                                            cloudSubtitle.equals("Off", ignoreCase = true) &&
                                            !localSubtitle.equals("Off", ignoreCase = true)
                                        )
                                )

                        if (keepLocalSubtitle) {
                            preservedNewerLocalSubtitle = true
                        } else {
                            prefs[defaultSubtitleKey] = cloudSubtitle
                            if (state.subtitleSettingsUpdatedAt > 0L) {
                                prefs[subtitleUpdatedAtKey] = state.subtitleSettingsUpdatedAt.toString()
                            }
                        }
                        prefs[defaultAudioLanguageKeyFor(profileId)] = state.defaultAudioLanguage
                        prefs[contentLanguageKeyFor(profileId)] = state.contentLanguage
                        if (profileId == activeProfileId) {
                            prefs[LAST_APP_LANGUAGE_KEY] = state.contentLanguage
                            context.getSharedPreferences("app_locale", Context.MODE_PRIVATE)
                                .edit().putString("locale_tag", state.contentLanguage).apply()
                        }

                        prefs[trailerAutoPlayKeyFor(profileId)] = state.trailerAutoPlay
                        prefs[clockFormatKeyFor(profileId)] = state.clockFormat
                        prefs[showBudgetKeyFor(profileId)] = state.showBudget
                        prefs[volumeBoostDbKeyFor(profileId)] = state.volumeBoostDb.coerceIn(0, 15).toString()
                        prefs[dnsProviderKeyFor(profileId)] = state.dnsProvider.ifBlank { "system" }
                        if (state.subtitleUsageJson.isBlank()) {
                            prefs.remove(subtitleUsageKeyFor(profileId))
                        } else {
                            prefs[subtitleUsageKeyFor(profileId)] = state.subtitleUsageJson
                        }
                        prefs[subtitleSizeKeyFor(profileId)] = state.subtitleSize
                        prefs[subtitleColorKeyFor(profileId)] = state.subtitleColor
                        if (state.iptvHiddenGroups.isNotBlank()) prefs[iptvHiddenGroupsKeyFor(profileId)] = state.iptvHiddenGroups
                        if (state.iptvGroupOrder.isNotBlank()) prefs[iptvGroupOrderKeyFor(profileId)] = state.iptvGroupOrder
                        prefs[secondarySubtitleKeyFor(profileId)] = state.secondarySubtitle.ifBlank { "Off" }
                        prefs[filterSubtitlesByLanguageKeyFor(profileId)] = state.filterSubtitlesByLanguage
                        val normalizedProfileLayout = normalizeCardLayoutMode(state.cardLayoutMode)
                        prefs[cardLayoutModeKeyFor(profileId)] = normalizedProfileLayout
                        state.catalogueRowLayoutModes.forEach { (rowKey, mode) ->
                            prefs[profileCatalogueRowLayoutModeKey(profileId, rowKey)] = normalizeCardLayoutMode(mode)
                        }
                        prefs[frameRateMatchingModeKeyFor(profileId)] = normalizeFrameRateMode(state.frameRateMatchingMode)
                        prefs[autoPlayNextKeyFor(profileId)] = state.autoPlayNext
                        prefs[autoPlaySingleSourceKeyFor(profileId)] = state.autoPlaySingleSource
                        prefs[autoPlayMinQualityKeyFor(profileId)] = normalizeAutoPlayMinQuality(state.autoPlayMinQuality)
                        prefs[includeSpecialsKeyFor(profileId)] = state.includeSpecials
                    }
                }
            }
        } ?: run {
            // Legacy fallback: single-profile settings
            context.settingsDataStore.edit { prefs ->
                val defaultSubtitleKey = defaultSubtitleKeyFor(activeProfileId)
                val subtitleUpdatedAtKey = subtitleSettingsUpdatedAtKeyFor(activeProfileId)
                val localSubtitle = prefs[defaultSubtitleKey]?.trim().orEmpty()
                val localSubtitleUpdatedAt = prefs[subtitleUpdatedAtKey]?.toLongOrNull() ?: 0L
                val cloudSubtitle = fallbackDefaultSubtitle.trim().ifBlank { "Off" }
                val keepLocalSubtitle = localSubtitle.isNotBlank() &&
                    !localSubtitle.equals(cloudSubtitle, ignoreCase = true) &&
                    (
                        localSubtitleUpdatedAt > fallbackSubtitleSettingsUpdatedAt ||
                            (
                                fallbackSubtitleSettingsUpdatedAt <= 0L &&
                                    cloudSubtitle.equals("Off", ignoreCase = true) &&
                                    !localSubtitle.equals("Off", ignoreCase = true)
                                )
                            )
                if (keepLocalSubtitle) {
                    preservedNewerLocalSubtitle = true
                } else {
                    prefs[defaultSubtitleKey] = cloudSubtitle
                    if (fallbackSubtitleSettingsUpdatedAt > 0L) {
                        prefs[subtitleUpdatedAtKey] = fallbackSubtitleSettingsUpdatedAt.toString()
                    }
                }
                prefs[defaultAudioLanguageKeyFor(activeProfileId)] = fallbackDefaultAudioLanguage
                prefs[cardLayoutModeKeyFor(activeProfileId)] = fallbackCardLayoutMode
                prefs[frameRateMatchingModeKeyFor(activeProfileId)] = fallbackFrameRateMatchingMode
                prefs[autoPlayNextKeyFor(activeProfileId)] = fallbackAutoPlayNext
                prefs[autoPlaySingleSourceKeyFor(activeProfileId)] = fallbackAutoPlaySingleSource
                prefs[autoPlayMinQualityKeyFor(activeProfileId)] = fallbackAutoPlayMinQuality
                prefs[includeSpecialsKeyFor(activeProfileId)] = fallbackIncludeSpecials
                prefs[dnsProviderKeyFor(activeProfileId)] = root.optString("dnsProvider", "system").ifBlank { "system" }
                root.optString("subtitleUsageJson", "").let { usage ->
                    if (usage.isBlank()) prefs.remove(subtitleUsageKeyFor(activeProfileId)) else prefs[subtitleUsageKeyFor(activeProfileId)] = usage
                }
            }
        }
        if (root.has("skipProfileSelection")) {
            context.settingsDataStore.edit { prefs ->
                prefs[SKIP_PROFILE_SELECTION_KEY] = root.optBoolean("skipProfileSelection", false)
            }
        }
        if (preservedNewerLocalSubtitle) {
            markLocalStateDirty()
        } else {
            authRepository.saveDefaultSubtitleToProfile(fallbackDefaultSubtitle)
        }
        authRepository.saveAutoPlayNextToProfile(fallbackAutoPlayNext)

        // ── Trakt tokens ──
        root.optJSONObject("traktTokens")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
            val type = object : TypeToken<Map<String, TraktRepository.CloudTraktToken>>() {}.type
            val tokens: Map<String, TraktRepository.CloudTraktToken> = gson.fromJson(json, type) ?: emptyMap()
            traktRepository.importTokensForProfiles(tokens)
        }

        // ── Addons ──
        root.optJSONObject("addonsByProfile")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
            val type = object : TypeToken<Map<String, List<Addon>>>() {}.type
            val map: Map<String, List<Addon>> = gson.fromJson(json, type) ?: emptyMap()
            val sharedAddons = mergeAddonsForSharedRestore(map.values)
            if (sharedAddons.isNotEmpty()) {
                streamRepository.replaceSharedAddonsFromCloud(sharedAddons)
            }
        }
        root.optJSONArray("addons")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
            if (!root.has("addonsByProfile")) {
                val type = object : TypeToken<List<Addon>>() {}.type
                val addons: List<Addon> = gson.fromJson(json, type) ?: emptyList()
                if (addons.isNotEmpty()) {
                    streamRepository.replaceSharedAddonsFromCloud(addons)
                }
            }
        }

        root.optJSONObject("cloudstreamRepositoriesByProfile")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
            val type = object : TypeToken<Map<String, List<CloudstreamRepositoryRecord>>>() {}.type
            val map: Map<String, List<CloudstreamRepositoryRecord>> = gson.fromJson(json, type) ?: emptyMap()
            val merged = mergeCloudstreamRepositoriesFromAddons(
                mergeRepositoriesForSharedRestore(map.values),
                emptyList()
            )
            if (merged.isNotEmpty()) {
                streamRepository.replaceSharedCloudstreamRepositoriesFromCloud(merged)
            }
        } ?: run {
            root.optJSONObject("addonsByProfile")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
                val type = object : TypeToken<Map<String, List<Addon>>>() {}.type
                val map: Map<String, List<Addon>> = gson.fromJson(json, type) ?: emptyMap()
                val recoveredRepositories = mergeCloudstreamRepositoriesFromAddons(
                    emptyList(),
                    mergeAddonsForSharedRestore(map.values)
                )
                if (recoveredRepositories.isNotEmpty()) {
                    streamRepository.replaceSharedCloudstreamRepositoriesFromCloud(recoveredRepositories)
                }
            }
        }

        // ── Catalogs ──
        root.optJSONObject("catalogsByProfile")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
            val type = object : TypeToken<Map<String, List<CatalogConfig>>>() {}.type
            val map: Map<String, List<CatalogConfig>> = gson.fromJson(json, type) ?: emptyMap()
            map.forEach { (profileId, catalogs) ->
                catalogRepository.replaceCatalogsForProfile(profileId, catalogs)
            }
        }
        root.optJSONArray("catalogs")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
            if (!root.has("catalogsByProfile")) {
                val type = object : TypeToken<List<CatalogConfig>>() {}.type
                val catalogs: List<CatalogConfig> = gson.fromJson(json, type) ?: emptyList()
                if (catalogs.isNotEmpty()) {
                    catalogRepository.replaceCatalogsForProfile(activeProfileId, catalogs)
                }
            }
        }

        // ── Hidden preinstalled catalogs ──
        root.optJSONObject("hiddenPreinstalledByProfile")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            val map: Map<String, List<String>> = gson.fromJson(json, type) ?: emptyMap()
            map.forEach { (profileId, hidden) ->
                catalogRepository.setHiddenPreinstalledCatalogIdsForProfile(profileId, hidden)
            }
        }
        root.optJSONArray("hiddenPreinstalledCatalogs")?.toString()?.let { json ->
            if (!root.has("hiddenPreinstalledByProfile")) {
                val hidden = if (json.isBlank()) {
                    emptyList()
                } else {
                    val type = object : TypeToken<List<String>>() {}.type
                    gson.fromJson<List<String>>(json, type) ?: emptyList()
                }
                catalogRepository.setHiddenPreinstalledCatalogIdsForProfile(activeProfileId, hidden)
            }
        }

        // ── Hidden addon catalogs ──
        root.optJSONObject("hiddenAddonByProfile")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            val map: Map<String, List<String>> = gson.fromJson(json, type) ?: emptyMap()
            map.forEach { (profileId, hidden) ->
                catalogRepository.setHiddenAddonCatalogIdsForProfile(profileId, hidden)
            }
        }

        // ── IPTV config + favorites ──
        var importedActiveProfileIptv = false
        root.optJSONObject("iptvByProfile")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
            val type = object : TypeToken<Map<String, IptvCloudProfileState>>() {}.type
            val map: Map<String, IptvCloudProfileState> = gson.fromJson(json, type) ?: emptyMap()
            map.forEach { (profileId, state) ->
                iptvRepository.importCloudConfigForProfile(profileId, state)
                if (profileId == activeProfileId) {
                    importedActiveProfileIptv = true
                }
            }
        }

        // Legacy IPTV flat fields (only used if iptvByProfile is absent)
        val cloudHasIptvKeys = root.has("iptvM3uUrl") || root.has("iptvEpgUrl") ||
            root.has("iptvFavoriteGroups") || root.has("iptvFavoriteChannels")
        val m3u = root.optString("iptvM3uUrl")
        val epg = root.optString("iptvEpgUrl")
        val favorites = root.optJSONArray("iptvFavoriteGroups")?.toString().orEmpty().let { j ->
            if (j.isBlank()) emptyList() else {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson<List<String>>(j, type) ?: emptyList()
            }
        }
        val favoriteChannels = root.optJSONArray("iptvFavoriteChannels")?.toString().orEmpty().let { j ->
            if (j.isBlank()) emptyList() else {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson<List<String>>(j, type) ?: emptyList()
            }
        }
        val localIptv = iptvRepository.observeConfig().first()
        val cloudHasIptvData = m3u.isNotBlank() || epg.isNotBlank() || favorites.isNotEmpty() || favoriteChannels.isNotEmpty()
        val localHasIptvData = localIptv.m3uUrl.isNotBlank() || localIptv.epgUrl.isNotBlank()
        var importedLegacyIptv = false
        if (!root.has("iptvByProfile") && cloudHasIptvKeys && (cloudHasIptvData || !localHasIptvData)) {
            iptvRepository.importCloudConfig(m3u, epg, favorites, favoriteChannels)
            importedLegacyIptv = true
        }

        if (importedActiveProfileIptv || importedLegacyIptv) {
            runCatching {
                iptvRepository.invalidateCache()
            }
        }

        // ── Watchlist ──
        root.optJSONObject("watchlistByProfile")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
            val type = object : TypeToken<Map<String, List<LocalWatchlistItem>>>() {}.type
            val map: Map<String, List<LocalWatchlistItem>> = gson.fromJson(json, type) ?: emptyMap()
            map.forEach { (profileId, items) ->
                // Restore the cloud mirror for every profile, including Trakt profiles.
                // Trakt remains the source of truth after a successful live sync, but
                // skipping this cache made fresh installs show an empty watchlist while
                // auth/network refresh was still settling or failed.
                watchlistRepository.importWatchlistForProfile(profileId, items)
            }
        }

        // ── Dismissed Continue Watching ──
        root.optJSONObject("dismissedContinueWatchingByProfile")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
            val type = object : TypeToken<Map<String, String>>() {}.type
            val map: Map<String, String> = gson.fromJson(json, type) ?: emptyMap()
            traktRepository.importDismissedContinueWatchingForProfiles(map)
        }

        // Only import local CW for profiles that DON'T have Trakt connected.
        // For Trakt profiles, CW is sourced exclusively from Trakt's progress API.
        // The previous code imported local CW unconditionally, which meant every
        // show ever partially watched in ARVIO (written to cloud by
        // saveLocalContinueWatching during playback) got restored to local DataStore
        // on cloud pull — polluting the CW row with non-Trakt items that persisted
        // even after app reinstall.
        // Only import local CW for profiles that DON'T have Trakt connected.
        // For Trakt profiles, CW is sourced exclusively from Trakt's progress API.
        // Only import local CW for profiles that DON'T have Trakt connected.
        // For Trakt profiles, CW is sourced exclusively from Trakt's progress API.
        root.optJSONObject("localContinueWatchingByProfile")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
            val type = object : TypeToken<Map<String, List<ContinueWatchingItem>>>() {}.type
            val map: Map<String, List<ContinueWatchingItem>> = gson.fromJson(json, type) ?: emptyMap()
            val traktProfiles = mutableSetOf<String>()

            val traktTokenType = object : TypeToken<Map<String, TraktRepository.CloudTraktToken>>() {}.type
            val traktTokens = root.optJSONObject("traktTokens")
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { tokenJson ->
                    runCatching {
                        gson.fromJson<Map<String, TraktRepository.CloudTraktToken>>(tokenJson, traktTokenType)
                    }.getOrNull()
                }
                .orEmpty()

            traktTokens.forEach { (profileId, token) ->
                if (profileId.isNotBlank() && token.accessToken.isNotBlank()) {
                    traktProfiles.add(profileId)
                }
            }

            val isActiveProfileTrakt = runCatching { traktRepository.isAuthenticated.first() }.getOrDefault(false)
            val activeProfileId = profileManager.getProfileIdSync().ifBlank { null }
            if (isActiveProfileTrakt && activeProfileId != null) {
                traktProfiles.add(activeProfileId)
            }

            val nonTraktOnly = map.filterKeys { it !in traktProfiles }
            if (nonTraktOnly.isNotEmpty()) {
                traktRepository.importLocalContinueWatchingForProfiles(nonTraktOnly)
            }
        }

        root.optJSONObject("localWatchedMoviesByProfile")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
            val type = object : TypeToken<Map<String, List<Int>>>() {}.type
            val map: Map<String, List<Int>> = gson.fromJson(json, type) ?: emptyMap()
            traktRepository.importLocalWatchedMoviesForProfiles(map)
        }

        root.optJSONObject("localWatchedEpisodesByProfile")?.toString()?.takeIf { it.isNotBlank() }?.let { json ->
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            val map: Map<String, List<String>> = gson.fromJson(json, type) ?: emptyMap()
            traktRepository.importLocalWatchedEpisodesForProfiles(map)
        }

        traktRepository.clearAllProfileCaches()
        watchHistoryRepository.clearProfileCaches()

        System.err.println("[CLOUD-SYNC] Full cloud restore applied successfully")
    }
}

package com.arflix.tv.ui.screens.home

import android.app.ActivityManager
import android.content.Context
import com.arflix.tv.util.settingsDataStore
import androidx.compose.runtime.mutableStateMapOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.model.CatalogKind
import com.arflix.tv.data.model.CollectionGroupKind
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.TraktRepository
import com.arflix.tv.data.repository.TraktSyncService
import com.arflix.tv.data.repository.ContinueWatchingItem
import com.arflix.tv.data.repository.CatalogRepository
import com.arflix.tv.data.repository.CloudSyncRepository
import com.arflix.tv.data.repository.LauncherContinueWatchingRepository
import com.arflix.tv.data.repository.StreamRepository
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.data.repository.CloudSyncStatus
import com.arflix.tv.data.repository.CollectionTemplateManifest
import com.arflix.tv.data.repository.WatchHistoryRepository
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.util.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = false,
    val isInitialLoad: Boolean = true,
    val categories: List<Category> = emptyList(),
    val collectionRows: List<HomeCollectionRow> = emptyList(),
    val error: String? = null,
    // Current hero (may update during transitions)
    val heroItem: MediaItem? = null,
    val heroLogoUrl: String? = null,
    val heroTrailerKey: String? = null,
    val trailerAutoPlay: Boolean = false,
    // Home hero metadata visibility toggles (issue #72)
    val showBudget: Boolean = true,
    val heroOverviewOverride: String? = null,
    val cardLogoUrls: Map<String, String> = emptyMap(),
    // Previous hero for crossfade (Phase 2.1)
    val previousHeroItem: MediaItem? = null,
    val previousHeroLogoUrl: String? = null,
    // Transition state for animations
    val isHeroTransitioning: Boolean = false,
    val isAuthenticated: Boolean = false,
    val clockFormat: String = "24h",
    // Cloud sync status for the indicator on the home top bar
    val syncStatus: com.arflix.tv.data.repository.CloudSyncStatus = com.arflix.tv.data.repository.CloudSyncStatus.NOT_SIGNED_IN,
    // Toast
    val toastMessage: String? = null,
    val toastType: ToastType = ToastType.INFO
)

data class HomeCollectionRow(
    val id: String,
    val title: String,
    val items: List<CatalogConfig>
)

enum class ToastType {
    SUCCESS, ERROR, INFO
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val catalogRepository: CatalogRepository,
    private val streamRepository: StreamRepository,
    private val traktRepository: TraktRepository,
    private val traktSyncService: TraktSyncService,
    private val iptvRepository: IptvRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val watchlistRepository: WatchlistRepository,
    private val cloudSyncRepository: CloudSyncRepository,
    private val launcherContinueWatchingRepository: LauncherContinueWatchingRepository,
    private val realtimeSyncManager: com.arflix.tv.data.repository.RealtimeSyncManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val imageLoader: ImageLoader by lazy(LazyThreadSafetyMode.NONE) {
        context.imageLoader
    }

    private data class HeroDetailsSnapshot(
        val duration: String,
        val releaseDate: String?,
        val imdbRating: String,
        val tmdbRating: String,
        val budget: Long?,
        val overview: String,
        val primaryNetworkLogo: String? = null
    )

    private data class CategoryPaginationState(
        var loadedCount: Int = 0,
        var hasMore: Boolean = true,
        var isLoading: Boolean = false
    )

    // IPTV favorite channels — maps MediaItem.id (Int hash) to channel data
    private val iptvChannelMap = mutableMapOf<Int, com.arflix.tv.data.model.IptvChannel>()

    companion object {
        const val FAVORITE_TV_CATEGORY_ID = "favorite_tv"
        /** Prefix used in MediaItem.status to identify IPTV items. */
        const val IPTV_STATUS_PREFIX = "iptv:"
    }

    /** Check if a MediaItem represents an IPTV channel. */
    fun isIptvItem(item: MediaItem): Boolean = item.status?.startsWith(IPTV_STATUS_PREFIX) == true

    fun isCollectionItem(item: MediaItem): Boolean = item.status?.startsWith("collection:") == true

    /** Returns the service / franchise hero-video URL for a focused collection tile, or null. */
    fun getCollectionHeroVideoUrl(item: MediaItem): String? {
        if (!isCollectionItem(item)) return null
        return collectionCatalogByMediaId[item.id]?.collectionHeroVideoUrl
            ?.takeIf { it.isNotBlank() }
    }

    fun getCollectionHeroImageUrl(item: MediaItem): String? {
        if (!isCollectionItem(item)) return null
        return collectionCatalogByMediaId[item.id]?.collectionHeroImageUrl
            ?.takeIf { it.isNotBlank() }
            ?: collectionCatalogByMediaId[item.id]?.collectionCoverImageUrl?.takeIf { it.isNotBlank() }
    }

    private fun isActionableMediaItem(item: MediaItem): Boolean {
        // Synthetic collection tiles (Netflix/Disney/HBO service cards) carry
        // a hash-based id in MediaItem.id that is not a real TMDB id — treating
        // them as actionable triggers TMDB /images 404s in the logo preloader.
        return item.id > 0 && !item.isPlaceholder && !isCollectionItem(item)
    }

    private fun continueWatchingKey(mediaType: MediaType, id: Int): String {
        return "${mediaType.name}:$id"
    }

    private fun continueWatchingShowKey(item: ContinueWatchingItem): String {
        return continueWatchingKey(item.mediaType, item.id)
    }

    private fun parseContinueWatchingUpdatedAt(primary: String?, fallback: String? = null): Long {
        fun parse(value: String?): Long {
            if (value.isNullOrBlank()) return 0L
            return try {
                java.time.Instant.parse(value).toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
        return maxOf(parse(primary), parse(fallback))
    }

    private fun mergeContinueWatchingVisuals(
        preferred: ContinueWatchingItem,
        fallback: ContinueWatchingItem
    ): ContinueWatchingItem {
        return preferred.copy(
            title = preferred.title.ifBlank { fallback.title },
            episodeTitle = preferred.episodeTitle ?: fallback.episodeTitle,
            backdropPath = preferred.backdropPath ?: fallback.backdropPath,
            posterPath = preferred.posterPath ?: fallback.posterPath,
            streamKey = preferred.streamKey ?: fallback.streamKey,
            streamAddonId = preferred.streamAddonId ?: fallback.streamAddonId,
            streamTitle = preferred.streamTitle ?: fallback.streamTitle,
            year = preferred.year.ifBlank { fallback.year },
            releaseDate = preferred.releaseDate.ifBlank { fallback.releaseDate },
            overview = preferred.overview.ifBlank { fallback.overview },
            imdbRating = preferred.imdbRating.ifBlank { fallback.imdbRating },
            duration = preferred.duration.ifBlank { fallback.duration },
            budget = preferred.budget ?: fallback.budget,
            updatedAtMs = maxOf(preferred.updatedAtMs, fallback.updatedAtMs)
        )
    }

    private fun mergeTraktAndRecentLocalContinueWatching(
        traktItems: List<ContinueWatchingItem>,
        localItems: List<ContinueWatchingItem>,
        historyItems: List<ContinueWatchingItem>
    ): List<ContinueWatchingItem> {
        val nowMs = System.currentTimeMillis()
        val localOverrideWindowMs = 15 * 60_000L
        val freshestLocalByShow = (localItems + historyItems)
            .groupBy(::continueWatchingShowKey)
            .mapValues { (_, candidates) ->
                candidates.maxWithOrNull(
                    compareBy<ContinueWatchingItem> { it.updatedAtMs }
                        .thenBy { it.resumePositionSeconds }
                        .thenBy { it.progress }
                )
            }

        val merged = mutableListOf<ContinueWatchingItem>()
        val seenShowKeys = mutableSetOf<String>()

        traktItems.forEach { traktItem ->
            val showKey = continueWatchingShowKey(traktItem)
            val freshestLocal = freshestLocalByShow[showKey]
            val sameEpisode = freshestLocal != null &&
                freshestLocal.mediaType == traktItem.mediaType &&
                freshestLocal.id == traktItem.id &&
                freshestLocal.season == traktItem.season &&
                freshestLocal.episode == traktItem.episode
            val recentLocalOverride = freshestLocal != null &&
                freshestLocal.updatedAtMs > 0L &&
                nowMs - freshestLocal.updatedAtMs <= localOverrideWindowMs

            val chosen = when {
                sameEpisode && freshestLocal != null ->
                    mergeContinueWatchingVisuals(
                        preferred = freshestLocal.copy(
                            resumePositionSeconds = maxOf(traktItem.resumePositionSeconds, freshestLocal.resumePositionSeconds),
                            durationSeconds = maxOf(traktItem.durationSeconds, freshestLocal.durationSeconds),
                            progress = maxOf(traktItem.progress, freshestLocal.progress)
                        ),
                        fallback = traktItem
                    )
                recentLocalOverride && freshestLocal != null ->
                    mergeContinueWatchingVisuals(freshestLocal, traktItem)
                else -> traktItem
            }

            merged += chosen
            seenShowKeys += showKey
        }

        freshestLocalByShow
            .asSequence()
            .filter { (showKey, item) ->
                item != null &&
                    showKey !in seenShowKeys &&
                    item.updatedAtMs > 0L &&
                    nowMs - item.updatedAtMs <= localOverrideWindowMs
            }
            .mapNotNull { (_, item) -> item }
            .sortedByDescending { it.updatedAtMs }
            .forEach { merged += it }

        return merged
    }

    private fun overviewLooksTruncated(overview: String): Boolean {
        val value = overview.trim()
        return value.isBlank() || value.endsWith("...") || value.length < 140
    }

    private suspend fun resolveBestOverview(item: MediaItem, candidateOverview: String): String {
        val base = candidateOverview.trim()
        if (!overviewLooksTruncated(base) || item.title.isBlank()) {
            return base
        }

        val searchMatches = runCatching { mediaRepository.search(item.title) }.getOrDefault(emptyList())
        val bestMatch = searchMatches
            .asSequence()
            .filter { match -> match.overview.isNotBlank() }
            .maxByOrNull { match ->
                val titleBonus = if (match.title.equals(item.title, ignoreCase = true)) 1_000 else 0
                val typeBonus = if (match.mediaType == item.mediaType) 120 else 0
                val qualityBonus = if (!overviewLooksTruncated(match.overview)) 80 else 0
                titleBonus + typeBonus + qualityBonus + match.overview.length
            }

        val improved = bestMatch?.overview?.trim().orEmpty()
        return if (
            improved.isNotBlank() &&
            (improved.length > base.length || (!improved.endsWith("...") && base.endsWith("...")))
        ) {
            improved
        } else {
            base
        }
    }

    private suspend fun resolvePrimaryNetworkLogo(mediaType: MediaType, mediaId: Int): String? {
        return runCatching {
            mediaRepository.getStreamingServices(
                mediaType = mediaType,
                mediaId = mediaId,
                preferredRegion = Locale.getDefault().country
            )
        }.getOrNull()
            ?.services
            ?.firstOrNull()
            ?.logoUrl
            ?.takeIf { it.isNotBlank() }
    }

    private fun isEpisodeAlreadyAired(rawAirDate: String): Boolean {
        val value = rawAirDate.trim()
        if (value.isEmpty()) return true
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            parser.isLenient = false
            val parsed = parser.parse(value) ?: return true
            parsed.time <= System.currentTimeMillis()
        } catch (_: Exception) {
            true
        }
    }

    private suspend fun sanitizeContinueWatchingItems(items: List<ContinueWatchingItem>): List<ContinueWatchingItem> {
        if (items.isEmpty()) return emptyList()

        val seasonEpisodesCache = HashMap<Pair<Int, Int>, List<com.arflix.tv.data.model.Episode>?>()

        return items.mapNotNull { item ->
            if (item.mediaType != MediaType.TV) {
                return@mapNotNull item
            }

            val season = item.season
            val episode = item.episode
            if (season == null || episode == null) {
                return@mapNotNull item
            }

            val cacheKey = item.id to season
            val seasonEpisodes = if (seasonEpisodesCache.containsKey(cacheKey)) {
                seasonEpisodesCache[cacheKey]
            } else {
                val fetched = runCatching {
                    mediaRepository.getSeasonEpisodes(item.id, season)
                }.getOrNull()
                seasonEpisodesCache[cacheKey] = fetched
                fetched
            }

            // If TMDB fetch failed, keep the item rather than silently dropping it.
            if (seasonEpisodes == null) {
                return@mapNotNull item
            }

            // If TMDB doesn't have episodes for this season yet (e.g., a brand-new
            // season premiere that Trakt knows about but TMDB hasn't indexed), keep
            // the item. Trakt's progress API is authoritative for "what to watch next."
            // The previous code dropped these items, causing shows with new season
            // premieres to silently disappear from Continue Watching.
            if (seasonEpisodes.isEmpty()) {
                return@mapNotNull item // Keep — TMDB may not have the season data yet
            }

            val matchedEpisode = seasonEpisodes.firstOrNull { it.episodeNumber == episode }
            if (matchedEpisode == null) {
                return@mapNotNull item // Keep — episode may not be on TMDB yet
            }

            if (!isEpisodeAlreadyAired(matchedEpisode.airDate)) {
                return@mapNotNull null
            }

            item.copy(
                episodeTitle = item.episodeTitle ?: matchedEpisode.name
            )
        }
    }

    /** Extract the IPTV channel ID from a MediaItem's status field. */
    fun getIptvChannelId(item: MediaItem): String? =
        item.status?.removePrefix(IPTV_STATUS_PREFIX)?.takeIf { it.isNotBlank() }

    /** Get the stream URL for an IPTV MediaItem. */
    fun getIptvStreamUrl(itemId: Int): String? = iptvChannelMap[itemId]?.streamUrl

    private fun iptvChannelToMediaItem(
        channel: com.arflix.tv.data.model.IptvChannel,
        epg: com.arflix.tv.data.model.IptvNowNext?
    ): MediaItem {
        val stableId = channel.id.hashCode() and 0x7FFFFFFF
        iptvChannelMap[stableId] = channel

        val nowProgram = epg?.now
        val nextProgram = epg?.next ?: epg?.later ?: epg?.upcoming?.firstOrNull()
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getDefault()
        }
        fun fmtRange(p: com.arflix.tv.data.model.IptvProgram): String {
            val s = timeFmt.format(java.util.Date(p.startUtcMillis))
            val e = timeFmt.format(java.util.Date(p.endUtcMillis))
            return "$s - $e"
        }
        val overviewParts = mutableListOf<String>()
        if (nowProgram != null) {
            overviewParts.add("Now: ${fmtRange(nowProgram)}  ${nowProgram.title}")
            if (!nowProgram.description.isNullOrBlank()) {
                overviewParts.add(nowProgram.description)
            }
        }
        if (nextProgram != null) {
            overviewParts.add("Next: ${fmtRange(nextProgram)}  ${nextProgram.title}")
        }

        return MediaItem(
            id = stableId,
            title = channel.name,
            subtitle = channel.group,
            overview = overviewParts.joinToString("\n").ifBlank { "Live TV" },
            mediaType = MediaType.TV,
            image = channel.logo ?: "",
            backdrop = channel.logo,
            badge = "LIVE",
            status = "$IPTV_STATUS_PREFIX${channel.id}",
            isOngoing = true
        )
    }

    private suspend fun buildFavoriteTvCategory(): Category? {
        // Use non-blocking memory read first; fall back to mutex-guarded disk read
        val snapshot = iptvRepository.getMemoryCachedSnapshot()
            ?: return null
        val favoriteIds = snapshot.favoriteChannels.toHashSet()
        if (favoriteIds.isEmpty()) return null

        // Re-derive now/next from cached programs so "Now" shifts when a program ends.
        // This is free (no network) — just recalculates which program is live.
        val favoriteChannelIds = snapshot.channels
            .filter { favoriteIds.contains(it.id) }
            .map { it.id }
            .toSet()
        iptvRepository.reDeriveCachedNowNext(favoriteChannelIds)
        // Re-read snapshot after re-derive to get updated nowNext
        val freshSnapshot = iptvRepository.getMemoryCachedSnapshot() ?: snapshot

        // Iterate channels in their original list order (matching TV page order)
        val items = freshSnapshot.channels
            .filter { favoriteIds.contains(it.id) }
            .mapNotNull { channel ->
                val epg = freshSnapshot.nowNext[channel.id]
                iptvChannelToMediaItem(channel, epg)
            }
        if (items.isEmpty()) return null

        return Category(
            id = FAVORITE_TV_CATEGORY_ID,
            title = "Favorite TV",
            items = items
        )
    }

    private fun isCustomCatalogConfig(cfg: CatalogConfig): Boolean {
        if (cfg.kind == CatalogKind.COLLECTION || cfg.kind == CatalogKind.COLLECTION_RAIL) {
            return false
        }
        return !cfg.isPreinstalled ||
            cfg.id.startsWith("custom_") ||
            !cfg.sourceUrl.isNullOrBlank() ||
            !cfg.sourceRef.isNullOrBlank()
    }

    private fun isCollectionRailConfig(cfg: CatalogConfig): Boolean = cfg.kind == CatalogKind.COLLECTION_RAIL

    private fun isCollectionTileConfig(cfg: CatalogConfig): Boolean = cfg.kind == CatalogKind.COLLECTION

    private fun collectionRowId(group: CollectionGroupKind): String {
        return "collection_row_${group.name.lowercase(Locale.US)}"
    }

    private fun hasRealItems(category: Category?): Boolean {
        return category?.items?.any { !it.isPlaceholder } == true
    }

    private fun chooseInitialHero(categories: List<Category>): MediaItem? {
        val preferredRow = categories.firstOrNull { category ->
            !category.id.startsWith("collection_row_") && category.items.any { !it.isPlaceholder }
        }
        return preferredRow?.items?.firstOrNull { !it.isPlaceholder }
            ?: categories.asSequence()
                .flatMap { it.items.asSequence() }
                .firstOrNull { !it.isPlaceholder }
            ?: categories.firstOrNull()?.items?.firstOrNull()
    }

    /**
     * Refresh the Favorite TV category's EPG data (Now/Next display).
     * @param networkFetch If true, also fetch fresh EPG from the Xtream short EPG API.
     *                     If false, only re-derive from cached program data (free, no network).
     */
    private fun refreshFavoriteTvEpg(networkFetch: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val categories = _uiState.value.categories
                val favTvIndex = categories.indexOfFirst { it.id == FAVORITE_TV_CATEGORY_ID }
                if (favTvIndex < 0) return@launch

                val currentFavTv = categories[favTvIndex]
                // Collect channel IDs from current items
                val channelIds = currentFavTv.items.mapNotNull { getIptvChannelId(it) }.toSet()
                if (channelIds.isEmpty()) return@launch

                // Optionally do network refresh first
                if (networkFetch) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEpgNetworkRefreshMs >= EPG_NETWORK_REFRESH_MS) {
                        lastEpgNetworkRefreshMs = now
                        runCatching { iptvRepository.refreshEpgForChannels(channelIds) }
                    }
                }

                // Re-derive now/next from (possibly updated) cached data
                iptvRepository.reDeriveCachedNowNext(channelIds)

                // Rebuild the category with updated EPG text
                val freshCategory = withContext(Dispatchers.IO) {
                    runCatching { buildFavoriteTvCategory() }.getOrNull()
                } ?: return@launch

                // Check if anything actually changed to avoid needless recomposition
                val oldOverviews = currentFavTv.items.map { it.overview }
                val newOverviews = freshCategory.items.map { it.overview }
                if (oldOverviews == newOverviews) return@launch

                // Apply user-renamed title if applicable
                val cfg = savedCatalogById[FAVORITE_TV_CATEGORY_ID]
                val titled = if (cfg != null && cfg.title.isNotBlank() && cfg.title != freshCategory.title) {
                    freshCategory.copy(title = cfg.title)
                } else {
                    freshCategory
                }

                withContext(Dispatchers.Main.immediate) {
                    val current = _uiState.value.categories.toMutableList()
                    val idx = current.indexOfFirst { it.id == FAVORITE_TV_CATEGORY_ID }
                    if (idx >= 0) {
                        current[idx] = titled
                        _uiState.value = _uiState.value.copy(categories = current)
                        System.err.println("[EPG-Refresh] Updated Favorite TV row (network=$networkFetch)")
                    }
                }
            } catch (e: Exception) {
                System.err.println("[EPG-Refresh] Error: ${e.message}")
            }
        }
    }

    /** Start periodic EPG refresh for the Favorite TV home row. */
    private fun startEpgRefreshTimer() {
        epgRefreshJob?.cancel()
        epgRefreshJob = viewModelScope.launch {
            // Initial delay — let home data + IPTV warmup finish first
            delay(if (isLowRamDevice) 10_000L else 5_000L)
            var tickCount = 0L
            while (true) {
                tickCount++
                // Every tick (60s): local re-derive
                // Every 5th tick (5 min): also do network refresh
                val doNetwork = tickCount % ((EPG_NETWORK_REFRESH_MS / EPG_LOCAL_REFRESH_MS).coerceAtLeast(1)) == 0L
                refreshFavoriteTvEpg(networkFetch = doNetwork)
                delay(EPG_LOCAL_REFRESH_MS)
            }
        }
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val isLowRamDevice = activityManager.isLowRamDevice || activityManager.memoryClass <= 256
    private val gson = com.google.gson.Gson()

    // Disk cache key for the home categories — profile-scoped so each profile gets
    // its own cached home screen. On app launch, the cached categories are shown
    // immediately (within 1 frame) while loadHomeData() fetches fresh data from
    // TMDB in the background. This eliminates the visible skeleton/loading phase
    // that made the app feel slow on startup.
    // Use a plain file in the cache dir instead of DataStore — DataStore has
    // a size limit and the categories JSON can be 200KB+. A cache file is faster
    // to read/write and doesn't trigger DataStore observers.
    private val categoriesCacheFile by lazy {
        java.io.File(context.cacheDir, "home_categories_cache.json")
    }

    private fun persistCategoriesCache(categories: List<Category>) {
        val cacheable = categories.filter { cat ->
            cat.items.isNotEmpty() && cat.items.none { it.isPlaceholder }
        }
        if (cacheable.isEmpty()) return
        runCatching {
            categoriesCacheFile.writeText(gson.toJson(cacheable))
        }
    }

    private fun loadCategoriesCache(): List<Category> {
        return runCatching {
            if (!categoriesCacheFile.exists()) return emptyList()
            if (categoriesCacheFile.length() > maxCategoriesCacheBytes) {
                categoriesCacheFile.delete()
                return emptyList()
            }
            val json = categoriesCacheFile.readText()
            if (json.isBlank()) return emptyList()
            val type = com.google.gson.reflect.TypeToken
                .getParameterized(MutableList::class.java, Category::class.java)
                .type
            val parsed: List<Category> = gson.fromJson(json, type) ?: emptyList()
            parsed.filter { it.items.isNotEmpty() }
        }.getOrDefault(emptyList())
    }
    // IO concurrency for network requests (logo fetches, catalog loads, etc.)
    private val networkParallelism = if (isLowRamDevice) 2 else 4
    private val networkDispatcher = Dispatchers.IO.limitedParallelism(networkParallelism)
    private var lastContinueWatchingItems: List<MediaItem> = emptyList()
    private var lastContinueWatchingUpdateMs: Long = 0L
    private var lastResolvedBaseCategories: List<Category> = emptyList()
    private val dismissedContinueWatchingKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val CONTINUE_WATCHING_REFRESH_MS = 45_000L
    private val WATCHED_BADGES_REFRESH_MS = 90_000L
    private var lastWatchedBadgesRefreshMs: Long = 0L
    private val HOME_PLACEHOLDER_ITEM_COUNT = 8

    // EPG refresh intervals for Favorite TV row
    /** Local re-derive: shift now/next from cached programs when a program ends. */
    private val EPG_LOCAL_REFRESH_MS = 60_000L
    /** Network refresh: fetch fresh short EPG for favorite channels (Xtream only). */
    private val EPG_NETWORK_REFRESH_MS = 5 * 60_000L
    private var epgRefreshJob: Job? = null
    private var lastEpgNetworkRefreshMs: Long = 0L

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    val cardLogoUrls = mutableStateMapOf<String, String>()

    // Debounce job for hero updates (Phase 6.1)
    private var heroUpdateJob: Job? = null
    private var heroDetailsJob: Job? = null
    private var prefetchJob: Job? = null
    private var preloadCategoryJob: Job? = null
    private var preloadCategoryPriorityJob: Job? = null
    private var customCatalogsJob: Job? = null
    private var loadHomeJob: Job? = null
    private var refreshContinueWatchingJob: Job? = null
    private var watchedBadgesJob: Job? = null
    private var loadHomeRequestId: Long = 0L
    private val HERO_DEBOUNCE_MS = 80L // Short debounce; focus idle is handled in HomeScreen
    private val startupCreatedAtMs = SystemClock.elapsedRealtime()
    private val startupSettleMs = if (isLowRamDevice) 2_200L else 1_400L

    // Phase 6.2-6.3: Fast scroll detection
    private var lastFocusChangeTime = 0L
    private var consecutiveFastChanges = 0
    private val FAST_SCROLL_THRESHOLD_MS = 650L  // Under 650ms = fast scrolling
    private val FAST_SCROLL_DEBOUNCE_MS = 620L   // Higher debounce during fast scroll

    private val FOCUS_PREFETCH_COALESCE_MS = if (isLowRamDevice) 70L else 45L

    private val logoPreloadWidth = if (isLowRamDevice) 260 else 300
    private val logoPreloadHeight = if (isLowRamDevice) 60 else 70
    private val cardBackdropWidth = (240 * context.resources.displayMetrics.density)
        .toInt()
        .coerceAtLeast(1)
    private val cardBackdropHeight = (cardBackdropWidth / (16f / 9f))
        .toInt()
        .coerceAtLeast(1)
    private val backdropPreloadWidth = cardBackdropWidth
    private val backdropPreloadHeight = cardBackdropHeight
    private val initialLogoPrefetchRows = if (isLowRamDevice) 1 else 2
    private val initialLogoPrefetchItemsPerRow = if (isLowRamDevice) 3 else 4
    // Prefetch enough backdrops to fill the first visible row on the home screen
    // (typically 6-8 cards on a TV). Was 2, which left the majority of the first
    // row unpreloaded on cold start, causing visible black -> image pop-in.
    private val initialBackdropPrefetchItems = if (isLowRamDevice) 2 else 3
    private val incrementalLogoPrefetchItems = if (isLowRamDevice) 4 else 6
    private val prioritizedLogoPrefetchItems = if (isLowRamDevice) 5 else 7
    private val incrementalBackdropPrefetchItems = 1
    private val initialCategoryItemCap = if (isLowRamDevice) 12 else 16
    private val categoryPageSize = if (isLowRamDevice) 12 else 16
    private val initialMdblistCatalogCount = if (isLowRamDevice) 2 else 3
    private val nearEndThreshold = 4

    // Track current focus for ahead-of-focus preloading
    private var currentRowIndex = 0
    private var currentItemIndex = 0

    // Track if preloaded data was used to avoid duplicate loading
    private var usedPreloadedData = false

    private val maxLogoCacheEntries = if (isLowRamDevice) 220 else 420
    private val maxLogoCacheJsonChars = if (isLowRamDevice) 250_000 else 500_000
    private val maxCategoriesCacheBytes = if (isLowRamDevice) 500_000L else 1_000_000L
    private val logoCacheLock = Any()
    private val logoCache = LinkedHashMap<String, String>(maxLogoCacheEntries + 32, 0.75f, true)
    private var logoCacheRevision: Long = 0L
    private var lastPublishedLogoCacheRevision: Long = -1L
    private val logoCachePrefs = context.getSharedPreferences("logo_cache", Context.MODE_PRIVATE)
    private var logoCacheDiskWriteJob: Job? = null
    private val logoFetchInFlight = Collections.synchronizedSet(mutableSetOf<String>())
    private val heroDetailsCache = ConcurrentHashMap<String, HeroDetailsSnapshot>()
    private val savedCatalogById = ConcurrentHashMap<String, CatalogConfig>()
    private val categoryPaginationStates = ConcurrentHashMap<String, CategoryPaginationState>()
    private val preloadedRequests = Collections.synchronizedSet(mutableSetOf<String>())
    private var logoCachePublishJob: Job? = null
    @Volatile
    private var pendingLogoPublishPriority: Boolean = false
    private var lastLogoCachePublishMs: Long = 0L
    private val LOGO_CACHE_PUBLISH_THROTTLE_MS = if (isLowRamDevice) 400L else 180L
    private val LOGO_CACHE_IDLE_REQUIRED_MS = if (isLowRamDevice) 350L else 200L
    private val LOGO_CACHE_FAST_SCROLL_IDLE_MS = if (isLowRamDevice) 180L else 100L

    private fun getCachedLogo(key: String): String? = synchronized(logoCacheLock) {
        logoCache[key]
    }

    private fun isStartupSettling(): Boolean {
        return SystemClock.elapsedRealtime() - startupCreatedAtMs < startupSettleMs
    }

    private suspend fun delayUntilStartupSettled(extraDelayMs: Long = 0L) {
        val waitMs = (startupCreatedAtMs + startupSettleMs + extraDelayMs) -
            SystemClock.elapsedRealtime()
        if (waitMs > 0L) {
            delay(waitMs)
        }
    }

    private fun scheduleInitialHomeLoad() {
        viewModelScope.launch {
            delay(if (isLowRamDevice) 220L else 140L)
            if (
                usedPreloadedData ||
                _uiState.value.categories.isNotEmpty() ||
                _uiState.value.heroItem != null
            ) {
                delayUntilStartupSettled()
            }
            loadHomeData()
        }
    }

    private fun scheduleStartupImageWarmup(
        logoUrls: List<String> = emptyList(),
        backdropUrls: List<String> = emptyList()
    ) {
        if (logoUrls.isEmpty() && backdropUrls.isEmpty()) return
        viewModelScope.launch(networkDispatcher) {
            delayUntilStartupSettled(160L)
            if (logoUrls.isNotEmpty()) {
                preloadLogoImages(
                    logoUrls,
                    batchLimit = if (isLowRamDevice) 4 else 6
                )
            }
            if (backdropUrls.isNotEmpty()) {
                preloadBackdropImages(backdropUrls)
            }
        }
    }

    private fun scheduleStartupHeroHydration(item: MediaItem) {
        heroDetailsJob?.cancel()
        heroDetailsJob = viewModelScope.launch(networkDispatcher) {
            delayUntilStartupSettled(120L)
            val currentHero = _uiState.value.heroItem
            if (currentHero?.id == item.id && currentHero.mediaType == item.mediaType) {
                hydrateHeroDetailsIfNeeded(item)
            }
        }
    }

    private fun hasCachedLogo(key: String): Boolean = synchronized(logoCacheLock) {
        logoCache.containsKey(key)
    }

    private fun putCachedLogo(key: String, value: String): Boolean {
        synchronized(logoCacheLock) {
            val existing = logoCache[key]
            if (existing == value) return false
            logoCache[key] = value
            while (logoCache.size > maxLogoCacheEntries) {
                val oldestKey = logoCache.entries.iterator().next().key
                logoCache.remove(oldestKey)
            }
            logoCacheRevision += 1L
            return true
        }
    }

    private fun putCachedLogos(entries: Map<String, String>): Boolean {
        if (entries.isEmpty()) return false
        var changed = false
        synchronized(logoCacheLock) {
            entries.forEach { (key, value) ->
                if (logoCache[key] != value) {
                    logoCache[key] = value
                    changed = true
                }
            }
            if (changed) {
                while (logoCache.size > maxLogoCacheEntries) {
                    val oldestKey = logoCache.entries.iterator().next().key
                    logoCache.remove(oldestKey)
                }
                logoCacheRevision += 1L
            }
        }
        if (changed) saveLogoCacheToDisk()
        return changed
    }

    private fun snapshotLogoCache(): Map<String, String> = synchronized(logoCacheLock) {
        LinkedHashMap(logoCache)
    }

    private fun replaceCardLogoState(snapshot: Map<String, String>) {
        val keysToRemove = cardLogoUrls.keys.toList().filterNot { snapshot.containsKey(it) }
        keysToRemove.forEach { cardLogoUrls.remove(it) }
        snapshot.forEach { (key, value) ->
            if (cardLogoUrls[key] != value) {
                cardLogoUrls[key] = value
            }
        }
    }

    private fun publishLogoCacheSnapshotIfChanged() {
        val snapshot: Map<String, String>
        synchronized(logoCacheLock) {
            if (logoCacheRevision == lastPublishedLogoCacheRevision) return
            snapshot = LinkedHashMap(logoCache)
            lastPublishedLogoCacheRevision = logoCacheRevision
        }
        lastLogoCachePublishMs = SystemClock.elapsedRealtime()
        replaceCardLogoState(snapshot)
    }

    private fun scheduleLogoCachePublish(highPriority: Boolean = false) {
        if (highPriority) {
            pendingLogoPublishPriority = true
        }
        val idleElapsedAtSchedule = System.currentTimeMillis() - lastFocusChangeTime
        if (highPriority && idleElapsedAtSchedule < LOGO_CACHE_FAST_SCROLL_IDLE_MS) {
            // During rapid D-pad movement, avoid forcing immediate full-map publishes.
            pendingLogoPublishPriority = false
        }
        if (logoCachePublishJob?.isActive == true) {
            if (highPriority) {
                logoCachePublishJob?.cancel()
            } else {
                return
            }
        }
        logoCachePublishJob = viewModelScope.launch {
            val priorityNow = pendingLogoPublishPriority
            pendingLogoPublishPriority = false
            if (priorityNow) {
                val elapsedSincePublish = SystemClock.elapsedRealtime() - lastLogoCachePublishMs
                val priorityThrottleMs = if (isLowRamDevice) 120L else 80L
                val throttleWaitMs = (priorityThrottleMs - elapsedSincePublish).coerceAtLeast(0L)
                val idleElapsedMs = System.currentTimeMillis() - lastFocusChangeTime
                val idleWaitMs = (LOGO_CACHE_FAST_SCROLL_IDLE_MS - idleElapsedMs).coerceAtLeast(0L)
                val waitMs = maxOf(throttleWaitMs, idleWaitMs)
                if (waitMs > 0L) delay(waitMs)
            } else {
                while (true) {
                    val elapsedSincePublish = SystemClock.elapsedRealtime() - lastLogoCachePublishMs
                    val throttleWaitMs = (LOGO_CACHE_PUBLISH_THROTTLE_MS - elapsedSincePublish).coerceAtLeast(0L)
                    val idleElapsedMs = System.currentTimeMillis() - lastFocusChangeTime
                    val idleWaitMs = (LOGO_CACHE_IDLE_REQUIRED_MS - idleElapsedMs).coerceAtLeast(0L)
                    val waitMs = maxOf(throttleWaitMs, idleWaitMs)
                    if (waitMs <= 0L) break
                    delay(waitMs)
                }
            }
            publishLogoCacheSnapshotIfChanged()
        }
    }

    /** Restore logo URL cache from disk (SharedPreferences). Called once at init. */
    private fun restoreLogoCacheFromDisk() {
        try {
            val json = logoCachePrefs.getString("urls", null) ?: return
            if (json.length > maxLogoCacheJsonChars) {
                logoCachePrefs.edit().remove("urls").apply()
                return
            }
            val map = org.json.JSONObject(json)
            val keys = map.keys()
            synchronized(logoCacheLock) {
                while (keys.hasNext()) {
                    val key = keys.next()
                    logoCache[key] = map.getString(key)
                    while (logoCache.size > maxLogoCacheEntries) {
                        val eldestKey = logoCache.entries.iterator().next().key
                        logoCache.remove(eldestKey)
                    }
                }
                if (logoCache.isNotEmpty()) {
                    logoCacheRevision += 1L
                }
            }
            System.err.println("HomeVM: restored ${logoCache.size} logo URLs from disk cache")
        } catch (e: Throwable) {
            System.err.println("HomeVM: failed to restore logo cache: ${e.message}")
            runCatching { logoCachePrefs.edit().remove("urls").apply() }
        }
    }

    /** Persist logo URL cache to disk (debounced). */
    private fun saveLogoCacheToDisk() {
        logoCacheDiskWriteJob?.cancel()
        logoCacheDiskWriteJob = viewModelScope.launch(Dispatchers.IO) {
            delay(2_000L) // debounce: wait 2s after last change before writing
            try {
                val snapshot = synchronized(logoCacheLock) { LinkedHashMap(logoCache) }
                val json = org.json.JSONObject(snapshot as Map<*, *>).toString()
                logoCachePrefs.edit().putString("urls", json).apply()
            } catch (e: Exception) {
                System.err.println("HomeVM: failed to save logo cache: ${e.message}")
            }
        }
    }

    init {
        // Load top-level UI preferences used on Home
        viewModelScope.launch {
            try {
                val prefs = context.settingsDataStore.data.first()
                // Search for any profile key that matches trailer_auto_play
                val trailerEnabled = prefs.asMap().any { (key, value) ->
                    key.name.endsWith("_trailer_auto_play") && value == true
                }
                // show_budget_on_home defaults to TRUE so existing users see no change
                // until they explicitly disable it. We check any active-profile key; if
                // none exist yet the default of true is preserved. Issue #72.
                val showBudgetExplicit = prefs.asMap().entries
                    .firstOrNull { (key, _) -> key.name.endsWith("_show_budget_on_home") }
                    ?.value as? Boolean
                val showBudget = showBudgetExplicit ?: true
                val clockFormat = prefs.asMap().entries
                    .firstOrNull { (key, _) -> key.name.endsWith("_clock_format") }
                    ?.value as? String ?: "24h"
                _uiState.value = _uiState.value.copy(
                    trailerAutoPlay = trailerEnabled,
                    showBudget = showBudget,
                    clockFormat = clockFormat
                )
            } catch (_: Exception) {}
        }

        // Subscribe to realtime watch_history events so Continue Watching refreshes on
        // this device within a few seconds of another device updating the shared
        // Supabase watch_history table. Before this, mid-playback updates from device A
        // were only visible on device B after a manual Home ON_RESUME or the 5-minute
        // periodic sync — so switching devices mid-episode always showed the stale
        // resume position. Fixes #91.
        viewModelScope.launch {
            realtimeSyncManager.watchHistoryEvents.collect {
                refreshContinueWatchingOnly(force = true)
                runCatching { launcherContinueWatchingRepository.refreshForCurrentProfile() }
            }
        }

        // Reload home data when account_sync changes arrive from another device
        // (catalogs, addons, settings pushed by TV/phone). This ensures the UI
        // reflects the latest state without waiting for the next ON_RESUME.
        viewModelScope.launch {
            realtimeSyncManager.accountSyncEvents.collect {
                loadHomeData()
                runCatching { launcherContinueWatchingRepository.refreshForCurrentProfile() }
            }
        }

        // Collect sync status so the UI can show a connection indicator.
        viewModelScope.launch {
            realtimeSyncManager.syncStatusFlow.collect { status ->
                _uiState.value = _uiState.value.copy(syncStatus = status)
            }
        }
        // Restore logo URL cache from disk off the main thread. This used to run
        // synchronously during HomeViewModel init, which made the profile->home
        // transition much more likely to hitch or ANR on phones with larger caches.
        viewModelScope.launch(Dispatchers.IO) {
            restoreLogoCacheFromDisk()
            val cachedLogos = snapshotLogoCache()
            if (cachedLogos.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    replaceCardLogoState(cachedLogos)
                }
                // Keep startup smooth: defer memory warmup until after initial Home rendering.
                delay(if (isLowRamDevice) 1_800L else 1_200L)
                val cachedUrls = synchronized(logoCacheLock) { logoCache.values.toList() }
                preloadLogoImages(
                    cachedUrls.takeLast(if (isLowRamDevice) 8 else 12),
                    batchLimit = if (isLowRamDevice) 4 else 6
                )
            }
        }

        // Instantly show last session's home categories from disk cache so the home
        // screen appears populated within 1 frame of launch — no skeleton loading
        // phase. loadHomeData() refreshes from TMDB in the background and silently
        // replaces the cached data when it arrives.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cachedCategories = loadCategoriesCache()
                if (cachedCategories.isNotEmpty() && _uiState.value.categories.isEmpty()) {
                    val heroItem = chooseInitialHero(cachedCategories)
                    val heroKey = heroItem?.let { "${it.mediaType}_${it.id}" }
                    val heroLogo = heroKey?.let { getCachedLogo(it) }
                    withContext(Dispatchers.Main) {
                        if (_uiState.value.categories.isEmpty()) {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                isInitialLoad = false,
                                categories = cachedCategories,
                                heroItem = heroItem,
                                heroLogoUrl = heroLogo,
                                error = null
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Instantly show Continue Watching from disk cache before anything else loads.
        // When Trakt is connected, we still use the cache for instant display but the
        // cache will only contain Trakt-sourced items after the first successful
        // resolveContinueWatchingItems call (which saves Trakt-only data to the cache).
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dismissedKeys = runCatching {
                    traktRepository.getDismissedContinueWatchingShowKeys()
                }.getOrDefault(emptySet())
                val cached = preloadStartupContinueWatchingItems().filterNot { item ->
                    dismissedKeys.contains("${item.mediaType.name}:${item.id}")
                }
                if (cached.isNotEmpty()) {
                    val merged = mergeContinueWatchingResumeData(cached)
                    val cwCategory = Category(
                        id = "continue_watching",
                        title = "Continue Watching",
                        items = merged.map { it.toMediaItem() }
                    )
                    cwCategory.items.forEach { mediaRepository.cacheItem(it) }

                    // Set hero item IMMEDIATELY from raw CW data (before the slow
                    // merge step) so the hero section, clear logo, and overview text
                    // appear on the very first frame after profile selection.
                    val rawFirstItem = cached.firstOrNull()?.toMediaItem()
                    val heroKey = rawFirstItem?.let { "${it.mediaType}_${it.id}" }
                    val heroLogo = heroKey?.let { getCachedLogo(it) }

                    withContext(Dispatchers.Main) {
                        if (rawFirstItem != null && _uiState.value.heroItem == null) {
                            if (heroLogo != null) {
                                if (isStartupSettling()) {
                                    scheduleStartupImageWarmup(logoUrls = listOf(heroLogo))
                                } else {
                                    preloadLogoImages(listOf(heroLogo), batchLimit = 1)
                                }
                            }
                            mediaRepository.cacheItem(rawFirstItem)
                            _uiState.value = _uiState.value.copy(
                                heroItem = rawFirstItem,
                                heroLogoUrl = heroLogo
                            )
                            if (isStartupSettling()) {
                                scheduleStartupHeroHydration(rawFirstItem)
                            } else {
                                hydrateHeroDetailsIfNeeded(rawFirstItem)
                            }
                        }

                        lastContinueWatchingItems = cwCategory.items
                        lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
                        val updated = _uiState.value.categories.toMutableList()
                        val idx = updated.indexOfFirst { it.id == "continue_watching" }
                        if (idx >= 0) updated[idx] = cwCategory else updated.add(0, cwCategory)
                        _uiState.value = _uiState.value.copy(
                            categories = updated
                        )
                    }
                }
            } catch (e: Exception) {
                System.err.println("HomeVM: preload CW cache failed: ${e.message}")
            }
        }
        scheduleInitialHomeLoad()
        // Defer heavy background warmups so first-launch navigation remains smooth.
        viewModelScope.launch {
            delay(if (isLowRamDevice) 12_000L else 8_000L)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    iptvRepository.warmXtreamVodCachesIfPossible()
                    System.err.println("HomeVM: VOD cache warmup completed")
                } catch (e: Exception) {
                    System.err.println("HomeVM: VOD cache warmup failed: ${e.message}")
                }
            }
        }
        viewModelScope.launch {
            try {
                // Start CW fetch as soon as Trakt auth is available. This no longer
                // blocks on base catalog loading because the CW fetch runs in its own
                // coroutine and updates the row independently when ready.
                traktRepository.isAuthenticated.filter { it }.first()
                launchContinueWatchingFetch()
            } catch (e: Exception) {
                System.err.println("HomeVM: auth observer CW refresh failed: ${e.message}")
            }
        }
        viewModelScope.launch {
            traktSyncService.syncEvents.collect { status ->
                if (status == com.arflix.tv.data.repository.SyncStatus.COMPLETED) {
                    refreshContinueWatchingOnly(force = true)
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            delay(if (isLowRamDevice) 4_000L else 2_500L)
            // Warm IPTV channels + EPG in background after startup settles.
            // First load from disk cache (fast), then do targeted network EPG refresh
            // for favorite channels so home screen shows current program info.
            try {
                iptvRepository.prefetchFreshStartupData()
                val snapshot = iptvRepository.getMemoryCachedSnapshot() ?: return@launch
                // Phase 2: Refresh EPG for favorite channels (lightweight network call)
                val snap = iptvRepository.getMemoryCachedSnapshot()
                if (snap != null) {
                    val favIds = snap.favoriteChannels.toHashSet()
                    val favChannelIds = snap.channels
                        .filter { favIds.contains(it.id) }
                        .map { it.id }
                        .toSet()
                    if (favChannelIds.isNotEmpty()) {
                        val refreshed = runCatching { iptvRepository.refreshEpgForChannels(favChannelIds) }.getOrNull()
                        if (refreshed == null) {
                            // refreshEpgForChannels failed (not Xtream?) — do full EPG reload
                            runCatching { iptvRepository.loadSnapshot(forcePlaylistReload = false, forceEpgReload = true) }
                        }
                        // Rebuild Favorite TV row with fresh EPG data
                        refreshFavoriteTvEpg(networkFetch = false)
                    }
                }
            } catch (e: Exception) {
                System.err.println("HomeVM: IPTV warmup failed: ${e.message}")
            }
        }
        // Periodically refresh EPG data for Favorite TV row on home screen
        startEpgRefreshTimer()
        viewModelScope.launch {
            catalogRepository.observeCatalogs()
                .map { catalogs ->
                    catalogs.joinToString("|") { "${it.id}:${it.title}:${it.sourceUrl.orEmpty()}" }
                }
                .distinctUntilChanged()
                .drop(2) // Skip first two emissions to avoid re-triggering loadHomeData during
                         // initial startup and ensurePreinstalledDefaults DataStore write.
                .collect {
                    // Apply catalog reorder/add/remove immediately on Home.
                    loadHomeData()
                }
        }
    }

    /**
     * Set preloaded data from StartupViewModel for instant display.
     * This skips the initial network call since data is already loaded.
     *
     * Shows placeholder Continue Watching cards immediately while real data loads.
     */
    fun setPreloadedData(
        categories: List<Category>,
        heroItem: MediaItem?,
        heroLogoUrl: String?,
        logoCache: Map<String, String>
    ) {

        if (usedPreloadedData) {
            if (logoCache.isNotEmpty()) {
                if (putCachedLogos(logoCache)) {
                    publishLogoCacheSnapshotIfChanged()
                }
            }
            val currentState = _uiState.value
            if (heroLogoUrl != null && currentState.heroLogoUrl == null) {
                _uiState.value = currentState.copy(heroLogoUrl = heroLogoUrl)
            }
            return
        }
        if (categories.isEmpty()) {
            return
        }

        usedPreloadedData = true

        putCachedLogos(logoCache)

        // Filter out any existing continue_watching from preloaded data
        val filteredCategories = categories.filter { it.id != "continue_watching" }.toMutableList()

        // Preserve real CW data if we already have it (from disk cache preload in init).
        // Only show placeholders if we have NO real CW items yet.
        val existingCW = _uiState.value.categories.firstOrNull {
            it.id == "continue_watching" && it.items.isNotEmpty() &&
                it.items.none { item -> item.isPlaceholder }
        }
        if (existingCW != null) {
            filteredCategories.add(0, existingCW)
        } else {
            val placeholderItems = (1..5).map { index ->
                MediaItem(
                    id = -index, // Negative IDs for placeholders
                    title = "",
                    mediaType = MediaType.MOVIE,
                    isPlaceholder = true
                )
            }
            val placeholderContinueWatching = Category(
                id = "continue_watching",
                title = "Continue Watching",
                items = placeholderItems
            )
            filteredCategories.add(0, placeholderContinueWatching)
        }

        // Adjust hero item if it was from continue watching
        val adjustedHeroItem = if (heroItem != null &&
            categories.firstOrNull()?.id == "continue_watching" &&
            categories.firstOrNull()?.items?.any { it.id == heroItem.id } == true) {
            // Hero was from continue watching, use first item from filtered categories
            filteredCategories.getOrNull(1)?.items?.firstOrNull() ?: heroItem
        } else if (heroItem != null && !isCollectionItem(heroItem)) {
            heroItem
        } else {
            chooseInitialHero(filteredCategories)
        }

        // If CW preload already set a hero with a logo, preserve it — the preloaded
        // hero from startup doesn't carry a logo URL and would cause a visible flash
        // from logo → text → logo.
        val currentHero = _uiState.value.heroItem
        val currentLogo = _uiState.value.heroLogoUrl
        val finalHero = if (currentHero != null && currentLogo != null) {
            currentHero  // keep CW hero that already has a logo
        } else {
            adjustedHeroItem
        }
        val finalLogo = if (finalHero == currentHero && currentLogo != null) {
            currentLogo  // keep the cached logo
        } else if (adjustedHeroItem == heroItem) {
            heroLogoUrl  // use whatever startup preloaded
        } else {
            null
        }
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isInitialLoad = false,
            categories = filteredCategories,
            heroItem = finalHero,
            heroLogoUrl = finalLogo,
            error = null
        )
        replaceCardLogoState(snapshotLogoCache())
        refreshWatchedBadges()
    }

    private var cwFetchJob: Job? = null
    private val prefetchedDetailsKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val collectionCatalogByMediaId = ConcurrentHashMap<Int, CatalogConfig>()

    fun getCollectionCatalog(item: MediaItem): CatalogConfig? {
        return collectionCatalogByMediaId[item.id]
    }

    private fun toCollectionCategory(row: HomeCollectionRow): Category {
        val items = row.items.mapIndexed { index, config ->
            val fakeId = (config.id.hashCode() and Int.MAX_VALUE).let { if (it == 0) index + 1 else it }
            collectionCatalogByMediaId[fakeId] = config
            // Cards intentionally do NOT seed a clearlogo or a description
            // for collection tiles — the branded cover is the whole card,
            // per the design spec. Clearlogo lives only on the collection
            // detail hero; description lives only on detail, never on
            // the home-row card.
            MediaItem(
                id = fakeId,
                title = config.title,
                overview = "",
                mediaType = MediaType.MOVIE,
                image = config.collectionCoverImageUrl.orEmpty(),
                backdrop = config.collectionFocusGifUrl
                    ?: config.collectionHeroImageUrl
                    ?: config.collectionCoverImageUrl,
                status = "collection:${config.id}",
                collectionGroup = config.collectionGroup,
                collectionTileShape = config.collectionTileShape,
                collectionHideTitle = config.collectionHideTitle
            )
        }
        return Category(
            id = row.id,
            title = row.title,
            items = items
        )
    }

    /**
     * Fetch Continue Watching in its OWN coroutine that is NOT cancelled by
     * loadHomeData restarts. loadHomeData() is called multiple times during
     * startup (profile load, catalog observer, cloud pull), each cancelling
     * the previous job. When getContinueWatching() was inside that job, the
     * 30-60s Trakt fetch (all watched shows × progress API) was always getting
     * cancelled before completing. This decoupled coroutine survives those
     * restarts and updates the CW row independently when the data arrives.
     */
    private fun launchContinueWatchingFetch() {
        // Don't restart if already running
        if (cwFetchJob?.isActive == true) return
        cwFetchJob = viewModelScope.launch(Dispatchers.IO) {
            // FAST PATH — resolve from cached Trakt/local data and publish
            // immediately. Avoids the 30–60s cold-refresh wait that used to
            // leave the CW row empty for minutes (especially when the Trakt
            // progress endpoint throttles with HTTP 429).
                val instant = runCatching { resolveContinueWatchingItemsStable(forceFresh = false) }
                .getOrDefault(emptyList())
            if (instant.isNotEmpty()) {
                publishContinueWatching(instant)
            }

            // SLOW PATH — do a freshness refresh in the background. If it
            // returns something different, republish. Swallows transient
            // Trakt 429s so the visible row doesn't blink back to empty.
                val fresh = runCatching { resolveContinueWatchingItemsStable(forceFresh = true) }
                .getOrDefault(emptyList())
            if (fresh.isNotEmpty() && fresh != instant) {
                publishContinueWatching(fresh)
            }
        }
    }

    private suspend fun publishContinueWatching(items: List<ContinueWatchingItem>) {
        val continueWatchingCategory = Category(
            id = "continue_watching",
            title = "Continue Watching",
            items = items.map { it.toMediaItem() }
        )
        continueWatchingCategory.items.forEach { mediaRepository.cacheItem(it) }
        lastContinueWatchingItems = continueWatchingCategory.items
        lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
        withContext(Dispatchers.Main) {
            val current = _uiState.value.categories.toMutableList()
            val cwIdx = current.indexOfFirst { it.id == "continue_watching" }
            if (cwIdx >= 0) {
                current[cwIdx] = continueWatchingCategory
            } else {
                current.add(0, continueWatchingCategory)
            }
            _uiState.value = _uiState.value.copy(categories = current)
        }
    }

    private fun loadHomeData() {
        loadHomeJob?.cancel()
        val requestId = ++loadHomeRequestId
        loadHomeJob = viewModelScope.launch loadHome@{
            // Skip delay - preloading now happens on profile focus for instant display
            // Only add minimal delay if no preloaded data exists yet
            if (!usedPreloadedData) {
                delay(50) // Minimal delay for LaunchedEffect to potentially set preloaded data
            }
            if (requestId != loadHomeRequestId) return@loadHome

            try {
                if (_uiState.value.categories.isEmpty()) {
                    // Build the early skeleton from the default catalog list minus any
                    // preinstalled catalogs the user has explicitly hidden for the active
                    // profile. Without this filter, deleted catalogs flash back into view
                    // for 1-3 seconds on every cold load and on every loadHomeData() call,
                    // which is the "catalog reappears after deletion" symptom reported in
                    // issue #71 (and the duplicate #74 which we already closed).
                    val hiddenForSkeleton = runCatching {
                        catalogRepository.getHiddenPreinstalledCatalogIdsForActiveProfile().toSet()
                    }.getOrDefault(emptySet())
                    val skeletonDefaults = mediaRepository.getDefaultCatalogConfigs()
                        .filterNot { cfg -> cfg.isPreinstalled && cfg.id in hiddenForSkeleton }
                    val earlySkeleton = buildProfileSkeletonCategories(
                        savedCatalogs = skeletonDefaults,
                        cachedContinueWatching = emptyList()
                    )
                    if (requestId != loadHomeRequestId) return@loadHome
                    if (earlySkeleton.isNotEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = true,
                            isInitialLoad = false,
                            categories = earlySkeleton,
                            heroItem = earlySkeleton.firstOrNull()?.items?.firstOrNull { !it.isPlaceholder },
                            heroLogoUrl = null,
                            error = null
                        )
                    }
                }

                val cachedContinueWatching = preloadStartupContinueWatchingItems()
                val savedCatalogs = withContext(networkDispatcher) {
                    runCatching {
                        streamRepository.removeCustomAddonsByUrl(
                            CollectionTemplateManifest.autoInstalledAddonUrls() +
                                listOf(MediaRepository.STREAMING_COLLECTION_ADDON_URL)
                        )
                        val addons = streamRepository.installedAddons.first()
                        catalogRepository.syncAddonCatalogs(addons)
                        // Ensure built-in preinstalled catalogs exist, then re-read
                        // the persisted catalog list so the returned value includes
                        // BOTH built-ins AND the addon catalogs just synced above.
                        // Previously `ensurePreinstalledDefaults()` was returned
                        // directly, and its output didn't include newly-synced
                        // addon catalogs — that's why user-added Stremio addons
                        // (Bharat Binge, AIO Metadata, etc.) appeared in the
                        // Catalogs tab but were dropped from the Home screen.
                        catalogRepository.ensurePreinstalledDefaults(
                            mediaRepository.getDefaultCatalogConfigs()
                        )
                        catalogRepository.getCatalogs()
                    }.getOrElse {
                        // If sync/defaults fail, fall back to whatever is already saved
                        // (includes user's custom Trakt catalogs) instead of only preinstalled defaults.
                        runCatching { catalogRepository.getCatalogs() }
                            .getOrDefault(mediaRepository.getDefaultCatalogConfigs())
                    }
                }
                savedCatalogById.clear()
                savedCatalogs.forEach { savedCatalogById[it.id] = it }
                categoryPaginationStates.clear()

                // When Home is opened from profile selection, avoid an empty frame by showing
                // profile-ordered skeleton rows immediately while real catalogs load.
                if (_uiState.value.categories.isEmpty()) {
                    val skeletonCategories = buildProfileSkeletonCategories(
                        savedCatalogs = savedCatalogs,
                        cachedContinueWatching = cachedContinueWatching
                    )
                    if (requestId != loadHomeRequestId) return@loadHome
                    if (skeletonCategories.isNotEmpty()) {
                        val skeletonHero = chooseInitialHero(skeletonCategories)
                        _uiState.value = _uiState.value.copy(
                            isLoading = true,
                            isInitialLoad = false,
                            categories = skeletonCategories,
                            heroItem = skeletonHero,
                            heroLogoUrl = null,
                            error = null
                        )
                    }
                } else {
                    // Keep preloaded/previous UI visible and refresh in background.
                    _uiState.value = _uiState.value.copy(isLoading = false, error = null)
                }

                val currentBaseCategories = _uiState.value.categories.filter {
                    it.id != "continue_watching" && !it.id.startsWith("collection_row_")
                }
                // Build Favorite TV category from IPTV cache (runs on IO)
                val favoriteTvCategory = withContext(Dispatchers.IO) {
                    runCatching { buildFavoriteTvCategory() }.getOrNull()
                }

                var categories = withContext(networkDispatcher) {
                    val baseCategories = runCatching {
                        mediaRepository.getHomeCategories()
                    }.getOrElse { emptyList() }

                    val baseById = LinkedHashMap<String, Category>().apply {
                        currentBaseCategories.forEach { put(it.id, it) }
                        baseCategories.forEach { put(it.id, it) }
                        // Inject Favorite TV so catalog ordering picks it up, or remove
                        // stale skeleton/placeholder if no favorites exist for this profile.
                        if (favoriteTvCategory != null) {
                            put(FAVORITE_TV_CATEGORY_ID, favoriteTvCategory)
                        } else {
                            remove(FAVORITE_TV_CATEGORY_ID)
                        }
                    }

                    // Split preinstalled into TMDB-based and MDBList-based
                    val tmdbPreinstalled = savedCatalogs
                        .filter {
                            it.isPreinstalled &&
                                it.sourceUrl.isNullOrBlank() &&
                                !isCollectionRailConfig(it) &&
                                !isCollectionTileConfig(it)
                        }
                        .mapNotNull { cfg ->
                            val category = baseById[cfg.id] ?: return@mapNotNull null
                            if (cfg.title.isNotBlank() && cfg.title != category.title) {
                                category.copy(title = cfg.title)
                            } else {
                                category
                            }
                        }
                    // Load MDBList preinstalled catalogs - first 8 immediately, rest lazily on scroll
                    val mdblistConfigs = savedCatalogs.filter {
                        it.isPreinstalled &&
                            !it.sourceUrl.isNullOrBlank() &&
                            !isCollectionRailConfig(it) &&
                            !isCollectionTileConfig(it)
                    }
                    val initialBatch = mdblistConfigs.take(initialMdblistCatalogCount)
                    val deferredBatch = mdblistConfigs.drop(initialBatch.size)
                    val mdblistInitial = initialBatch.map { cfg ->
                        async(networkDispatcher) {
                            try {
                                val result = mediaRepository.loadCustomCatalogPage(catalog = cfg, offset = 0, limit = 20)
                                if (result.items.isNotEmpty()) Category(id = cfg.id, title = cfg.title, items = result.items) else null
                            } catch (_: Exception) { null }
                        }
                    }
                    val mdblistCategories = mdblistInitial.awaitAll().filterNotNull()
                    // Create placeholder categories for deferred ones (will load on scroll)
                    val deferredCategories = deferredBatch.map { cfg -> Category(id = cfg.id, title = cfg.title, items = emptyList()) }
                    // Merge both lists maintaining the saved catalog order
                    val allPreinstalledById = (tmdbPreinstalled + mdblistCategories + deferredCategories).associateBy { it.id }

                    // Load deferred catalogs in background in batches of 3
                    if (deferredBatch.isNotEmpty()) {
                        viewModelScope.launch(networkDispatcher) {
                            deferredBatch.chunked(3).forEach { batch ->
                                val results = batch.map { cfg ->
                                    async {
                                        try {
                                            val result = mediaRepository.loadCustomCatalogPage(catalog = cfg, offset = 0, limit = 20)
                                            if (result.items.isNotEmpty()) Category(id = cfg.id, title = cfg.title, items = result.items) else null
                                        } catch (_: Exception) { null }
                                    }
                                }.awaitAll().filterNotNull()
                                if (results.isNotEmpty()) {
                                    val current = _uiState.value.categories.toMutableList()
                                    for (cat in results) {
                                        val idx = current.indexOfFirst { it.id == cat.id }
                                        if (idx >= 0) current[idx] = cat else current.add(cat)
                                    }
                                    _uiState.value = _uiState.value.copy(categories = current)
                                }
                            }
                        }
                    }
                    val preinstalled = savedCatalogs
                        .filter {
                            it.isPreinstalled &&
                                !isCollectionRailConfig(it) &&
                                !isCollectionTileConfig(it)
                        }
                        .mapNotNull { cfg -> allPreinstalledById[cfg.id] }
                    val customCatalogConfigs = savedCatalogs.filter { cfg -> isCustomCatalogConfig(cfg) }

                    // Fetch ALL custom catalogs (Trakt lists, user-added) in parallel
                    // right here in the bulk path, instead of deferring to
                    // loadCustomCatalogsIncrementally which loaded them one-by-one and
                    // caused slow incremental insertion. This way everything appears in
                    // the single bulk categories set at line ~1250.
                    val customSemaphore = kotlinx.coroutines.sync.Semaphore(if (isLowRamDevice) 1 else 2)
                    val freshCustomCategories = customCatalogConfigs.map { cfg ->
                        async(networkDispatcher) {
                            customSemaphore.withPermit {
                                try {
                                    val result = mediaRepository.loadCustomCatalogPage(
                                        catalog = cfg, offset = 0, limit = initialCategoryItemCap
                                    )
                                    if (result.items.isNotEmpty()) {
                                        categoryPaginationStates[cfg.id] = CategoryPaginationState(
                                            loadedCount = result.items.size,
                                            hasMore = result.hasMore
                                        )
                                        Category(id = cfg.id, title = cfg.title, items = result.items)
                                    } else null
                                } catch (_: Exception) { null }
                            }
                        }
                    }.awaitAll().filterNotNull()

                    // Fall back to previously cached data for any custom catalog
                    // that failed to load in this round
                    val stickyCustomById = currentBaseCategories
                        .filter { category ->
                            customCatalogConfigs.any { it.id == category.id } && category.items.isNotEmpty()
                        }
                        .associateBy { it.id }
                    val freshCustomById = freshCustomCategories.associateBy { it.id }

                    // Build the final list following the user's savedCatalogs order
                    // for ALL catalog types (preinstalled, MDBList, custom/Trakt).
                    // The previous code added all preinstalled first, then appended
                    // custom catalogs at the end — which ignored the user's configured
                    // ordering and always put Favorite TV before custom catalogs
                    // regardless of where the user placed it.
                    val allById = LinkedHashMap<String, Category>()
                    // Seed with preinstalled data
                    preinstalled.forEach { allById[it.id] = it }
                    // Layer on fresh custom catalog data (prefer fresh, fall back to cached)
                    customCatalogConfigs.forEach { cfg ->
                        val cat = freshCustomById[cfg.id]
                            ?: stickyCustomById[cfg.id]
                            ?: return@forEach
                        allById[cat.id] = cat
                    }
                    // Fallback: if no preinstalled loaded at all, use base/cached
                    if (preinstalled.isEmpty()) {
                        val fallback = baseCategories.ifEmpty { currentBaseCategories.ifEmpty { lastResolvedBaseCategories } }
                        fallback.forEach { allById.putIfAbsent(it.id, it) }
                    }

                    // Resolve in savedCatalogs order — this is the user's configured
                    // catalog ordering from Settings > Catalogs.
                    // Addon-provided service-branded catalogs (Netflix, Disney+,
                    // Hulu etc. rows contributed by aio-metadata / org.kris /
                    // nuvio addons) are suppressed here — we already surface
                    // those services via the collection-tile Services row, so
                    // having a second identically-named catalog row below it
                    // was duplicative per user feedback. Preinstalled catalogs
                    // (Trending, Just Added, Top 10, etc.) are never skipped.
                    val serviceTitleBlocklist = setOf(
                        "netflix", "prime video", "prime", "apple tv+", "apple tv plus",
                        "apple tv", "disney+", "disney plus", "paramount+", "paramount plus",
                        "hbo max", "max", "hulu", "shudder", "jiohotstar", "sonyliv",
                        "sky", "crunchyroll", "peacock"
                    )
                    val resolved = savedCatalogs.mapNotNull { cfg ->
                        if (isCollectionRailConfig(cfg) || isCollectionTileConfig(cfg)) {
                            return@mapNotNull null
                        }
                        val cat = allById[cfg.id]
                        if (cat == null || cat.items.isEmpty()) return@mapNotNull null
                        if (!cfg.isPreinstalled &&
                            cat.title.trim().lowercase(Locale.US) in serviceTitleBlocklist
                        ) return@mapNotNull null
                        cat
                    }.toMutableList()
                    resolved
                }
                val collectionRows = withContext(networkDispatcher) {
                    val collectionConfigs = savedCatalogs.filter { cfg ->
                        isCollectionTileConfig(cfg) && CollectionTemplateManifest.isValidCollectionConfig(cfg)
                    }

                    savedCatalogs.mapNotNull { cfg ->
                        if (!isCollectionRailConfig(cfg) || !CollectionTemplateManifest.isValidCollectionConfig(cfg)) {
                            return@mapNotNull null
                        }
                        val group = cfg.collectionGroup ?: return@mapNotNull null
                        val items = collectionConfigs
                            .filter { it.collectionGroup == group }
                        if (items.isEmpty()) {
                            null
                        } else {
                            HomeCollectionRow(
                                id = collectionRowId(group),
                                title = cfg.title,
                                items = items
                            )
                        }
                    }
                }
                val categoryById = categories.associateBy { it.id }
                val collectionCategoryById = collectionRows.associateBy { it.id }
                categories = savedCatalogs.mapNotNull { cfg ->
                    when {
                        isCollectionTileConfig(cfg) -> null
                        isCollectionRailConfig(cfg) -> {
                            val group = cfg.collectionGroup ?: return@mapNotNull null
                            collectionCategoryById[collectionRowId(group)]?.let { toCollectionCategory(it) }
                        }
                        else -> categoryById[cfg.id]
                    }
                }.toMutableList()
                if (categories.any { it.id != "continue_watching" && !it.id.startsWith("collection_row_") }) {
                    lastResolvedBaseCategories = categories.filter {
                        it.id != "continue_watching" && !it.id.startsWith("collection_row_")
                    }
                }
                categories.forEach { category ->
                    if (category.id != "continue_watching" && !category.id.startsWith("collection_row_")) {
                        categoryPaginationStates[category.id] = CategoryPaginationState(
                            loadedCount = category.items.size,
                            hasMore = category.items.size >= categoryPageSize
                        )
                    }
                }
                if (requestId != loadHomeRequestId) return@loadHome

                // CW resolution is decoupled from loadHomeData to prevent cancellation.
                // loadHomeData() is called multiple times during startup (profile load,
                // catalog observer, cloud pull) — each call cancels the previous one via
                // loadHomeJob?.cancel(). When getContinueWatching() was inside this job,
                // the 30-60s Trakt API fetch (97+ shows × progress call) was always
                // getting cancelled before it could finish. Now it runs in its own
                // independent coroutine that survives loadHomeData restarts.
                val existingCW = _uiState.value.categories.firstOrNull {
                    it.id == "continue_watching" && it.items.isNotEmpty() &&
                        it.items.none { item -> item.isPlaceholder }
                }
                if (existingCW != null) {
                    categories.add(0, existingCW)
                } else if (cachedContinueWatching.isNotEmpty()) {
                    // Show the persisted CW cache instantly regardless of
                    // whether Trakt is connected. For Trakt users, the cache
                    // holds the last-successful Trakt resolution, so there's
                    // no reason to gate it behind auth — the slow fresh
                    // Trakt fetch (launchContinueWatchingFetch below) will
                    // still overwrite the row when it finishes. Previously
                    // Trakt users saw the row disappear during loadHomeData
                    // and only reappear 30-60s later when the fresh fetch
                    // completed.
                    val merged = mergeContinueWatchingResumeData(cachedContinueWatching)
                    val cwCat = Category(
                        id = "continue_watching",
                        title = "Continue Watching",
                        items = merged.map { it.toMediaItem() }
                    )
                    categories.add(0, cwCat)
                }
                // Launch the independent CW fetch
                launchContinueWatchingFetch()

                val heroItem = chooseInitialHero(categories)

                // Preload logos for the first visible rows so card overlays appear immediately.
                // Skip IPTV items — their channel logo is already in item.image.
                // Skip items with disk-cached logos — no network call needed.
                val itemsToPreload = categories
                    .take(initialLogoPrefetchRows)
                    .flatMap { it.items.take(initialLogoPrefetchItemsPerRow) }
                    .filter { isActionableMediaItem(it) && !isIptvItem(it) }

                // Separate: items already in logo cache (instant) vs items needing fetch
                val cachedLogoResults = mutableMapOf<String, String>()
                val itemsNeedingFetch = mutableListOf<MediaItem>()
                for (item in itemsToPreload) {
                    val key = "${item.mediaType}_${item.id}"
                    val cached = getCachedLogo(key)
                    if (cached != null) {
                        cachedLogoResults[key] = cached
                    } else {
                        itemsNeedingFetch.add(item)
                    }
                }

                // If we have disk-cached logos, publish them immediately (before network fetch)
                if (cachedLogoResults.isNotEmpty()) {
                    val heroLogoFromCache = heroItem?.let { item ->
                        val key = "${item.mediaType}_${item.id}"
                        cachedLogoResults[key]
                    }
                    if (heroLogoFromCache != null || cachedLogoResults.isNotEmpty()) {
                        // Use high batch limit for initial display — preload all cached logos at once
                        if (isStartupSettling()) {
                            scheduleStartupImageWarmup(logoUrls = cachedLogoResults.values.toList())
                        } else {
                            preloadLogoImages(
                                cachedLogoResults.values.toList(),
                                batchLimit = if (isLowRamDevice) 4 else 6
                            )
                        }
                        _uiState.value = _uiState.value.copy(
                            isLoading = _uiState.value.isLoading,
                            categories = categories,
                            collectionRows = collectionRows,
                            heroItem = heroItem,
                            heroLogoUrl = heroLogoFromCache ?: _uiState.value.heroLogoUrl
                        )
                        heroItem?.let { item ->
                            if (isStartupSettling()) {
                                scheduleStartupHeroHydration(item)
                            } else {
                                hydrateHeroDetailsIfNeeded(item)
                            }
                        }
                        replaceCardLogoState(snapshotLogoCache())
                    }
                }

                // Fetch remaining logos from TMDB (only items not in disk cache)
                val logoJobs = itemsNeedingFetch.map { item ->
                    async(networkDispatcher) {
                        val key = "${item.mediaType}_${item.id}"
                        try {
                            val logoUrl = mediaRepository.getLogoUrl(item.mediaType, item.id)
                            if (logoUrl != null) key to logoUrl else null
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                val fetchedLogoResults = logoJobs.awaitAll().filterNotNull().toMap()
                if (requestId != loadHomeRequestId) return@loadHome

                val logoResults = cachedLogoResults + fetchedLogoResults

                // Phase 1.2: Preload actual images with Coil (only newly fetched)
                if (fetchedLogoResults.isNotEmpty()) {
                    if (isStartupSettling()) {
                        scheduleStartupImageWarmup(logoUrls = fetchedLogoResults.values.toList())
                    } else {
                        preloadLogoImages(
                            fetchedLogoResults.values.toList(),
                            batchLimit = if (isLowRamDevice) 4 else 6
                        )
                    }
                }

                // Also preload backdrop images for first row
                val backdropUrls = categories.firstOrNull()?.items?.take(initialBackdropPrefetchItems)?.mapNotNull {
                    it.backdrop ?: it.image
                } ?: emptyList()
                if (isStartupSettling()) {
                    scheduleStartupImageWarmup(backdropUrls = backdropUrls)
                } else {
                    preloadBackdropImages(backdropUrls)
                }

                val heroLogoUrl = heroItem?.let { item ->
                    val key = "${item.mediaType}_${item.id}"
                    getCachedLogo(key) ?: logoResults[key]
                }

                putCachedLogos(logoResults)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isInitialLoad = false,
                    categories = categories,
                    collectionRows = collectionRows,
                    heroItem = heroItem,
                    heroLogoUrl = heroLogoUrl,
                    isAuthenticated = traktRepository.isAuthenticated.first(),
                    error = null
                )
                heroItem?.let { item ->
                    if (isStartupSettling()) {
                        scheduleStartupHeroHydration(item)
                    } else {
                        hydrateHeroDetailsIfNeeded(item)
                    }
                }
                replaceCardLogoState(snapshotLogoCache())
                refreshWatchedBadges()

                // Persist the real categories to disk so the next app launch
                // shows them immediately without waiting for TMDB API calls.
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { persistCategoriesCache(categories) }
                }

                // On mobile/touch devices, prefetch logos for ALL categories in the background
                // since there's no D-pad focus to trigger incremental loading.
                if (!isLowRamDevice) {
                    viewModelScope.launch(networkDispatcher) {
                        delay(1_500L) // Let initial UI settle first
                        for (i in initialLogoPrefetchRows until categories.size) {
                            preloadLogosForCategory(i, prioritizeVisible = false)
                            delay(300L) // Stagger to avoid API rate limiting
                        }
                    }
                }

                // Immediately refresh EPG for Favorite TV row if the cached data
                // produced "Live TV" fallback (stale/empty EPG).
                // The background init warmup may also be refreshing EPG concurrently —
                // this is a fast-path that fires as soon as categories are set.
                val favTvCat = _uiState.value.categories.firstOrNull { it.id == FAVORITE_TV_CATEGORY_ID }
                if (favTvCat != null && favTvCat.items.any { it.overview == "Live TV" }) {
                    viewModelScope.launch(Dispatchers.IO) {
                        delay(if (isLowRamDevice) 4_000L else 2_500L)
                        val channelIds = favTvCat.items.mapNotNull { getIptvChannelId(it) }.toSet()
                        if (channelIds.isNotEmpty()) {
                            // Try lightweight Xtream short EPG first
                            val refreshed = runCatching { iptvRepository.refreshEpgForChannels(channelIds) }.getOrNull()
                            if (refreshed == null) {
                                // Not Xtream or failed — defer full EPG reload to the normal
                                // background warmup path instead of competing with Home startup.
                            }
                            refreshFavoriteTvEpg(networkFetch = false)
                            // Also update hero if it's an IPTV item showing stale EPG
                            val currentHero = _uiState.value.heroItem
                            if (currentHero != null && isIptvItem(currentHero) && currentHero.overview == "Live TV") {
                                val updatedCat = _uiState.value.categories.firstOrNull { it.id == FAVORITE_TV_CATEGORY_ID }
                                val updatedHero = updatedCat?.items?.firstOrNull { it.id == currentHero.id }
                                if (updatedHero != null) {
                                    withContext(Dispatchers.Main.immediate) {
                                        _uiState.value = _uiState.value.copy(heroItem = updatedHero)
                                    }
                                }
                            }
                        }
                    }
                }

                // Custom catalogs are now loaded in the bulk path above (parallel
                // fetch alongside TMDB + MDBList). No need for incremental loading
                // which caused the slow one-by-one appearance and the viewport "trip"
                // when rows were inserted mid-list.

                viewModelScope.launch cw@{
                    if (requestId != loadHomeRequestId) return@cw
                    delay(if (isLowRamDevice) 2_200L else 1_200L)
                    if (requestId != loadHomeRequestId) return@cw
                    val freshContinueWatching = resolveContinueWatchingItemsStable(forceFresh = true)
                    if (requestId != loadHomeRequestId) return@cw

                    if (freshContinueWatching.isNotEmpty()) {
                        val mergedContinueWatching = mergeContinueWatchingResumeData(freshContinueWatching)
                        val continueWatchingCategory = Category(
                            id = "continue_watching",
                            title = "Continue Watching",
                            items = mergedContinueWatching.map { it.toMediaItem() }
                        )
                        continueWatchingCategory.items.forEach { mediaRepository.cacheItem(it) }
                        lastContinueWatchingItems = continueWatchingCategory.items
                        lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
                        val updated = _uiState.value.categories.toMutableList()
                        val index = updated.indexOfFirst { it.id == "continue_watching" }
                        if (index >= 0) {
                            updated[index] = continueWatchingCategory
                        } else {
                            updated.add(0, continueWatchingCategory)
                        }
                        _uiState.value = _uiState.value.copy(categories = updated)
                    }
                }
              } catch (e: Exception) {
                if (requestId != loadHomeRequestId) return@loadHome
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isInitialLoad = false,
                    error = if (_uiState.value.categories.isEmpty()) e.message ?: "Failed to load content" else null
                )
            } finally {
            }
        }
    }

    /**
     * Warm critical details assets while the card is focused so opening Details feels instant:
     * - clearlogo URL
     * - next-episode season episodes for TV (or season 1)
     * The calls hit in-memory/network caches and are safe to fire-and-forget.
     */
    fun prefetchDetailsAssets(item: MediaItem) {
        if (!isActionableMediaItem(item) || isIptvItem(item) || isCollectionItem(item)) return
        val key = "${item.mediaType}_${item.id}_${item.nextEpisode?.seasonNumber ?: 1}"
        if (!prefetchedDetailsKeys.add(key)) return
        viewModelScope.launch(networkDispatcher) {
            runCatching { mediaRepository.getLogoUrl(item.mediaType, item.id) }
            if (item.mediaType == MediaType.TV) {
                val season = item.nextEpisode?.seasonNumber ?: 1
                runCatching { mediaRepository.getSeasonEpisodes(item.id, season) }
            }
        }
    }

    private fun loadCustomCatalogsIncrementally(savedCatalogs: List<CatalogConfig>) {
        customCatalogsJob?.cancel()
        customCatalogsJob = viewModelScope.launch(networkDispatcher) {
            delay(if (isLowRamDevice) 700L else 350L)
            val customCatalogs = savedCatalogs.filter { cfg -> isCustomCatalogConfig(cfg) }
            if (customCatalogs.isEmpty()) return@launch
            val customIds = customCatalogs.map { it.id }.toSet()
            val existingCustomById = _uiState.value.categories
                .filter { category -> customIds.contains(category.id) && category.items.isNotEmpty() }
                .associateBy { it.id }
            val baseCategories = _uiState.value.categories.filterNot { customIds.contains(it.id) }
            val baseById = baseCategories.associateBy { it.id }

            val loadedById = java.util.concurrent.ConcurrentHashMap<String, Category>()
            fun publishMerged() {
                val latestState = _uiState.value
                val currentCategories = latestState.categories.toMutableList()

                // Preserve CW from latest state
                val latestCW = latestState.categories.firstOrNull {
                    it.id == "continue_watching" && it.items.isNotEmpty()
                }

                // For each custom catalog that has loaded, update it IN-PLACE if it
                // already has a slot in the displayed list, or APPEND it at the end
                // if it's brand new. This prevents mid-list insertions that cause
                // LazyColumn to re-layout and the viewport to jump ("trip" bug).
                // Base (TMDB) catalogs are NOT touched here — they're set in the
                // main loadHomeData path which does a single bulk replacement.
                var anyChange = false
                for (id in customIds) {
                    val freshData = loadedById[id]
                        ?: existingCustomById[id]
                        ?: continue
                    if (freshData.items.isEmpty()) continue

                    val existingIdx = currentCategories.indexOfFirst { it.id == id }
                    if (existingIdx >= 0) {
                        if (currentCategories[existingIdx].items.size != freshData.items.size) {
                            currentCategories[existingIdx] = freshData
                            anyChange = true
                        }
                    } else {
                        // Append new custom catalog at end — no mid-list insert
                        currentCategories.add(freshData)
                        anyChange = true
                    }
                }

                // Also update Favorite TV if it exists in base but not yet in displayed list
                val favTv = baseById[FAVORITE_TV_CATEGORY_ID]
                if (favTv != null && favTv.items.isNotEmpty()) {
                    val favIdx = currentCategories.indexOfFirst { it.id == FAVORITE_TV_CATEGORY_ID }
                    if (favIdx >= 0) {
                        currentCategories[favIdx] = favTv
                        anyChange = true
                    } else {
                        currentCategories.add(favTv)
                        anyChange = true
                    }
                }

                // Restore CW at position 0
                if (latestCW != null) {
                    val cwIdx = currentCategories.indexOfFirst { it.id == "continue_watching" }
                    if (cwIdx >= 0) {
                        currentCategories[cwIdx] = latestCW
                    } else {
                        currentCategories.add(0, latestCW)
                        anyChange = true
                    }
                }

                if (!anyChange) return
                _uiState.value = latestState.copy(categories = currentCategories)
            }
            withContext(Dispatchers.Main.immediate) {
                publishMerged()
            }

            // Load custom catalogs in parallel (up to 3 concurrently) for faster appearance
            val catalogSemaphore = kotlinx.coroutines.sync.Semaphore(if (isLowRamDevice) 2 else 3)
            val jobs = customCatalogs.map { catalog ->
                async(networkDispatcher) {
                    catalogSemaphore.withPermit {
                        val firstPage = runCatching {
                            mediaRepository.loadCustomCatalogPage(
                                catalog = catalog,
                                offset = 0,
                                limit = initialCategoryItemCap
                            )
                        }.getOrNull()
                        if (firstPage == null || firstPage.items.isEmpty()) {
                            loadedById[catalog.id] = Category(
                                id = catalog.id,
                                title = catalog.title,
                                items = emptyList()
                            )
                            categoryPaginationStates[catalog.id] = CategoryPaginationState(
                                loadedCount = 0,
                                hasMore = false
                            )
                        } else {
                            loadedById[catalog.id] = Category(
                                id = catalog.id,
                                title = catalog.title,
                                items = firstPage.items
                            )
                            categoryPaginationStates[catalog.id] = CategoryPaginationState(
                                loadedCount = firstPage.items.size,
                                hasMore = firstPage.hasMore
                            )
                        }
                        // Publish as each catalog completes so rows appear incrementally
                        withContext(Dispatchers.Main.immediate) {
                            publishMerged()
                        }
                    }
                }
            }
            jobs.awaitAll()
        }
    }

    fun maybeLoadNextPageForCategory(categoryId: String, focusedItemIndex: Int) {
        if (categoryId == "continue_watching") return
        val currentCategory = _uiState.value.categories.firstOrNull { it.id == categoryId } ?: return
        if (currentCategory.items.isEmpty() || currentCategory.items.all { it.isPlaceholder }) return
        if (focusedItemIndex < currentCategory.items.size - nearEndThreshold) return
        loadNextPageForCategory(categoryId)
    }

    private fun loadNextPageForCategory(categoryId: String) {
        val pagination = categoryPaginationStates.getOrPut(categoryId) {
            CategoryPaginationState(
                loadedCount = _uiState.value.categories.firstOrNull { it.id == categoryId }?.items?.size ?: 0
            )
        }
        if (!pagination.hasMore || pagination.isLoading) return

        pagination.isLoading = true
        viewModelScope.launch(networkDispatcher) {
            try {
                val currentCategories = _uiState.value.categories
                val currentCategory = currentCategories.firstOrNull { it.id == categoryId } ?: return@launch

                val catalog = savedCatalogById[categoryId]
                val result = if (catalog?.isPreinstalled == true && catalog.sourceUrl.isNullOrBlank()) {
                    // Pure TMDB preinstalled catalog (no MDBList source)
                    val nextPage = (currentCategory.items.size / categoryPageSize) + 1
                    mediaRepository.loadHomeCategoryPage(categoryId, nextPage)
                } else {
                    // MDBList/custom catalog (including preinstalled MDBList ones)
                    val cfg = catalog ?: return@launch
                    mediaRepository.loadCustomCatalogPage(
                        catalog = cfg,
                        offset = currentCategory.items.size,
                        limit = categoryPageSize
                    )
                }

                if (result.items.isEmpty()) {
                    pagination.hasMore = false
                    return@launch
                }

                val seen = currentCategory.items
                    .map { "${it.mediaType.name}_${it.id}" }
                    .toHashSet()
                val uniqueNewItems = result.items.filter { item ->
                    seen.add("${item.mediaType.name}_${item.id}")
                }
                if (uniqueNewItems.isEmpty()) {
                    pagination.hasMore = false
                    return@launch
                }

                val updatedCategories = currentCategories.map { category ->
                    if (category.id == categoryId) {
                        category.copy(items = category.items + uniqueNewItems)
                    } else {
                        category
                    }
                }

                uniqueNewItems.forEach { mediaRepository.cacheItem(it) }
                val logoEntries = uniqueNewItems.take(6).mapNotNull { item ->
                    if (!isActionableMediaItem(item) || isIptvItem(item)) return@mapNotNull null
                    val key = "${item.mediaType}_${item.id}"
                    if (hasCachedLogo(key) || !logoFetchInFlight.add(key)) return@mapNotNull null
                    val logo = runCatching {
                        mediaRepository.getLogoUrl(item.mediaType, item.id)
                    }.getOrNull()
                    logoFetchInFlight.remove(key)
                    if (logo == null) return@mapNotNull null
                    key to logo
                }
                if (logoEntries.isNotEmpty()) {
                    val logoMap = logoEntries.toMap()
                    if (putCachedLogos(logoMap)) {
                        scheduleLogoCachePublish()
                    }
                    preloadLogoImages(logoMap.values.toList())
                }
                preloadBackdropImages(uniqueNewItems.take(incrementalBackdropPrefetchItems).mapNotNull { it.backdrop ?: it.image })

                pagination.loadedCount = updatedCategories
                    .firstOrNull { it.id == categoryId }
                    ?.items
                    ?.size
                    ?: pagination.loadedCount
                pagination.hasMore = result.hasMore

                _uiState.value = _uiState.value.copy(categories = updatedCategories)
            } catch (_: Exception) {
                // Keep UI stable; user can retry naturally by continuing to browse the row.
            } finally {
                pagination.isLoading = false
            }
        }
    }

    private fun buildProfileSkeletonCategories(
        savedCatalogs: List<com.arflix.tv.data.model.CatalogConfig>,
        cachedContinueWatching: List<ContinueWatchingItem>
    ): List<Category> {
        val placeholderItems = (1..HOME_PLACEHOLDER_ITEM_COUNT).map { index ->
            MediaItem(
                id = -index,
                title = "",
                mediaType = MediaType.MOVIE,
                isPlaceholder = true
            )
        }

        val rows = mutableListOf<Category>()
        if (cachedContinueWatching.isNotEmpty()) {
            rows.add(
                Category(
                    id = "continue_watching",
                    title = "Continue Watching",
                    items = cachedContinueWatching.map { it.toMediaItem() }
                )
            )
        } else {
            rows.add(
                Category(
                    id = "continue_watching",
                    title = "Continue Watching",
                    items = placeholderItems
                )
            )
        }

        savedCatalogs.forEach { cfg ->
            if (isCollectionTileConfig(cfg)) return@forEach
            rows.add(
                Category(
                    id = if (isCollectionRailConfig(cfg)) {
                        collectionRowId(cfg.collectionGroup ?: return@forEach)
                    } else {
                        cfg.id
                    },
                    title = cfg.title,
                    items = placeholderItems
                )
            )
        }

        return rows
    }

    /**
     * Phase 1.2: Preload images into Coil's memory/disk cache
     * Uses target display sizes to reduce decode overhead.
     */
    private fun preloadImagesWithCoil(urls: List<String>, width: Int, height: Int, batchLimit: Int = 0) {
        if (preloadedRequests.size > if (isLowRamDevice) 1_200 else 4_000) {
            preloadedRequests.clear()
        }
        // Bumped from 2/4 to 4/8 — enough to preload one full row of cards on
        // the home screen. The old limits left the majority of visible cards
        // without preloaded images on cold start.
        val defaultLimit = if (isLowRamDevice) 4 else 8
        val limit = if (batchLimit > 0) batchLimit else defaultLimit
        val uniqueUrls = urls.filter { url ->
            preloadedRequests.add("$url|${width}x${height}")
        }.take(limit)
        if (uniqueUrls.isEmpty()) return

        uniqueUrls.forEach { url ->
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(width.coerceAtLeast(1), height.coerceAtLeast(1))
                .precision(Precision.INEXACT)
                .allowHardware(true)
                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                .build()
            imageLoader.enqueue(request)
        }
    }

    private fun preloadLogoImages(urls: List<String>, batchLimit: Int = 0) {
        preloadImagesWithCoil(urls, logoPreloadWidth, logoPreloadHeight, batchLimit)
    }

    private fun preloadBackdropImages(urls: List<String>) {
        preloadImagesWithCoil(urls, backdropPreloadWidth, backdropPreloadHeight)
    }

    fun refresh() {
        loadHomeData()
    }

    private suspend fun resolveContinueWatchingItems(forceFresh: Boolean): List<ContinueWatchingItem> {
        val isTraktAuthenticated = runCatching { traktRepository.isAuthenticated.first() }.getOrDefault(false)
        // Debug: write CW state to a file we can pull via adb
        val items: List<ContinueWatchingItem> = if (isTraktAuthenticated) {
            // When connected to Trakt, use ONLY Trakt as the source of truth for
            // Continue Watching. The previous code merged local/history items which
            // polluted the CW row with shows not on the user's Trakt — e.g., items
            // watched before connecting Trakt, or items from ARVIO Cloud watch_history
            // that Trakt doesn't know about. Trakt users expect CW to match exactly
            // what Trakt shows as "Up Next."
            val traktItems = if (forceFresh) {
                runCatching { traktRepository.getContinueWatching(forceRefresh = true) }.getOrDefault(emptyList())
            } else {
                val cached = traktRepository.getCachedContinueWatching()
                if (cached.isNotEmpty()) cached else runCatching { traktRepository.getContinueWatching() }.getOrDefault(emptyList())
            }

            // Enrich Trakt items with local resume position data where available.
            // This preserves exact playback position (e.g., "resume at 32:15") which
            // Trakt's progress API doesn't provide — it only gives episode-level progress.
            val localItems = runCatching { traktRepository.getLocalContinueWatching() }.getOrDefault(emptyList())
            val historyItems = loadContinueWatchingFromHistory()
            val localByKey = (localItems + historyItems).associateBy { "${it.mediaType}:${it.id}" }

            traktItems.map { traktItem ->
                val key = "${traktItem.mediaType}:${traktItem.id}"
                val local = localByKey[key]
                if (local != null && local.season == traktItem.season && local.episode == traktItem.episode) {
                    // Same episode — enrich with local resume position
                    traktItem.copy(
                        resumePositionSeconds = maxOf(traktItem.resumePositionSeconds, local.resumePositionSeconds),
                        durationSeconds = maxOf(traktItem.durationSeconds, local.durationSeconds),
                        episodeTitle = traktItem.episodeTitle ?: local.episodeTitle,
                        backdropPath = traktItem.backdropPath ?: local.backdropPath,
                        posterPath = traktItem.posterPath ?: local.posterPath
                    )
                } else {
                    traktItem
                }
            }
        } else {
            val historyItems = loadContinueWatchingFromHistory()
            if (historyItems.isNotEmpty()) {
                // Cloud watch_history is the shared source of truth for non-Trakt
                // profiles. Only fall back to local CW when the cloud has nothing
                // yet, so one device's stale local cache cannot override another
                // device's synced progress/episode.
                historyItems
            } else {
                runCatching { traktRepository.getLocalContinueWatching() }.getOrDefault(emptyList())
            }
        }

        val persistedDismissedKeys = runCatching {
            traktRepository.getDismissedContinueWatchingShowKeys()
        }.getOrDefault(emptySet())

        val sanitizedItems = sanitizeContinueWatchingItems(items)

        val dismissed = sanitizedItems.filter { item ->
            val showKey = continueWatchingKey(item.mediaType, item.id)
            dismissedContinueWatchingKeys.contains(showKey) || persistedDismissedKeys.contains(showKey)
        }
        return sanitizedItems
            .filterNot { item ->
                val showKey = continueWatchingKey(item.mediaType, item.id)
                dismissedContinueWatchingKeys.contains(showKey) || persistedDismissedKeys.contains(showKey)
            }
            // Don't filter by progress range for Trakt items — Trakt's progress API
            // is authoritative. Some "up next" items have synthetic progress = 0
            // (brand new season premiere, never started) which the old 1..99 filter
            // was dropping. For non-Trakt items, keep the 1..99 filter to avoid
            // showing completed (100%) or never-started (0%) items.
            .filter { item ->
                if (isTraktAuthenticated) true else item.progress in 1..99
            }
            .take(Constants.MAX_CONTINUE_WATCHING)
    }

    /**
     * Pull the full cloud state (addons, catalogs, settings, profiles) on ON_RESUME.
     * This is the critical fix for the "addon added on phone but not on TV" symptom:
     * when the TV comes back from background, the WebSocket may be dead, so we do
     * an explicit pull to catch any account_sync_state changes that were missed.
     * Throttled to at most once per 10 seconds to avoid excessive pulls on rapid
     * activity transitions (e.g., player back → home → details → home).
     */
    @Volatile
    private var lastCloudPullTimestamp = 0L
    private val cloudPullThrottleMs = 10_000L

    fun pullCloudStateOnResume() {
        val now = System.currentTimeMillis()
        if (now - lastCloudPullTimestamp < cloudPullThrottleMs) return
        lastCloudPullTimestamp = now
        viewModelScope.launch(Dispatchers.IO) {
            // If a previous push failed (dirty flag), retry it now before pulling.
            // This ensures the cloud has our latest state before we pull the other
            // device's state on top of it — preventing stale overwrites.
            if (cloudSyncRepository.isPushDirty) {
                android.util.Log.i("HomeViewModel", "Retrying dirty push before pull")
                runCatching { cloudSyncRepository.pushToCloud() }
            }
            val result = runCatching {
                cloudSyncRepository.pullFromCloud()
            }
            result.onSuccess { restoreResult ->
                if (restoreResult == CloudSyncRepository.RestoreResult.RESTORED) {
                    // Cloud state was applied — reload home data so catalog changes,
                    // addon changes, and settings from the other device take effect
                    // immediately without waiting for the observeCatalogs flow.
                    loadHomeData()
                }
            }.onFailure {
                android.util.Log.w("HomeViewModel", "ON_RESUME cloud pull failed: ${it.message}")
            }
        }
    }

    fun refreshContinueWatchingOnly(force: Boolean = false) {
        // Don't cancel an in-progress Trakt fetch - restarting a fetch that takes
        // 10+ seconds (424 watched shows, 41 filtered, 50 progress API calls) wastes
        // time and causes Continue Watching to never appear. Multiple callers
        // (ON_RESUME, isAuthenticated observer, sync completion) would keep cancelling
        // each other's fetches. The throttle mechanism prevents redundant fetches.
        if (refreshContinueWatchingJob?.isActive == true) {
            if (force) refreshContinueWatchingJob?.cancel() else return
        }
        refreshContinueWatchingJob = viewModelScope.launch {
            try {
                val now = SystemClock.elapsedRealtime()
                val startCategories = _uiState.value.categories
                val continueWatchingIndexAtStart = startCategories.indexOfFirst { it.id == "continue_watching" }
                val existingContinueWatching = startCategories.getOrNull(continueWatchingIndexAtStart)
                val hasPlaceholders = existingContinueWatching?.items?.any { it.isPlaceholder } == true

                // Allow refresh if we have placeholders (need to replace them), otherwise throttle
                if (!force && !hasPlaceholders && now - lastContinueWatchingUpdateMs < CONTINUE_WATCHING_REFRESH_MS) {
                    return@launch
                }

                val resolvedContinueWatching = resolveContinueWatchingItemsStable(forceFresh = force)

                if (resolvedContinueWatching.isNotEmpty()) {
                    val mergedContinueWatching = mergeContinueWatchingResumeData(resolvedContinueWatching)
                    val continueWatchingCategory = Category(
                        id = "continue_watching",
                        title = "Continue Watching",
                        items = mergedContinueWatching.map { it.toMediaItem() }
                    )
                    continueWatchingCategory.items.forEach { mediaRepository.cacheItem(it) }
                    lastContinueWatchingItems = continueWatchingCategory.items
                    lastContinueWatchingUpdateMs = now
                    val latestCategories = _uiState.value.categories.toMutableList()
                    val continueWatchingIndex = latestCategories.indexOfFirst { it.id == "continue_watching" }
                    if (continueWatchingIndex >= 0) {
                        latestCategories[continueWatchingIndex] = continueWatchingCategory
                    } else {
                        latestCategories.add(0, continueWatchingCategory)
                    }
                    _uiState.value = _uiState.value.copy(categories = latestCategories)
                    refreshWatchedBadges()
                } else {
                    // No new data from any source
                    val latestCategories = _uiState.value.categories.toMutableList()
                    val continueWatchingIndex = latestCategories.indexOfFirst { it.id == "continue_watching" }
                    val latestHasPlaceholders = latestCategories
                        .getOrNull(continueWatchingIndex)
                        ?.items
                        ?.any { it.isPlaceholder } == true
                    if (hasPlaceholders) {
                        // We had placeholders but no data loaded - remove the placeholder category
                        if (continueWatchingIndex >= 0) {
                            latestCategories.removeAt(continueWatchingIndex)
                            _uiState.value = _uiState.value.copy(categories = latestCategories)
                        }
                    } else if (!latestHasPlaceholders && continueWatchingIndex >= 0) {
                        // Continue Watching exists with real data - preserve it exactly as is
                        return@launch
                    } else if (lastContinueWatchingItems.isNotEmpty()) {
                        // Only resurrect last-known CW items for non-Trakt profiles.
                        // For Trakt profiles, if the fresh fetch returned empty, that
                        // means the user genuinely has nothing to continue — don't
                        // re-insert stale items that may include non-Trakt ghost data.
                        val isTraktAuth = runCatching { traktRepository.isAuthenticated.first() }.getOrDefault(false)
                        if (isTraktAuth) {
                            return@launch // Trakt said empty — trust it
                        }
                        val persistedDismissedKeys = runCatching {
                            traktRepository.getDismissedContinueWatchingShowKeys()
                        }.getOrDefault(emptySet())
                        val safeItems = lastContinueWatchingItems.filterNot { item ->
                            val showKey = continueWatchingKey(item.mediaType, item.id)
                            dismissedContinueWatchingKeys.contains(showKey) || persistedDismissedKeys.contains(showKey)
                        }
                        if (safeItems.isEmpty()) {
                            return@launch
                        }
                        val continueWatchingCategory = Category(
                            id = "continue_watching",
                            title = "Continue Watching",
                            items = safeItems
                        )
                        latestCategories.add(0, continueWatchingCategory)
                        _uiState.value = _uiState.value.copy(categories = latestCategories)
                    }
                    // Else: No data anywhere - nothing to show, UI already doesn't have it
                }
            } catch (e: Exception) {
                // Silently fail - don't clear existing data on error
            }
        }
    }

    private suspend fun loadContinueWatchingFromHistory(): List<ContinueWatchingItem> {
        return try {
            val entries = watchHistoryRepository.getContinueWatching()
            if (entries.isEmpty()) return emptyList()
            val mapped = entries.distinctBy { entry ->
                // Deduplicate at show level for TV — only keep the most recent episode per show.
                // Entries are already sorted by updated_at desc, so distinctBy keeps the latest.
                "${entry.media_type}:${entry.show_tmdb_id}"
            }.mapNotNull { entry ->
                val mediaType = if (entry.media_type == "tv") MediaType.TV else MediaType.MOVIE
                val storedPct = (entry.progress * 100f).toInt()
                val derivedPct = if (storedPct <= 0 && entry.duration_seconds > 0 && entry.position_seconds > 0) {
                    ((entry.position_seconds.toFloat() / entry.duration_seconds.toFloat()) * 100f).toInt()
                } else {
                    storedPct
                }
                ContinueWatchingItem(
                    id = entry.show_tmdb_id,
                    title = entry.title ?: return@mapNotNull null,
                    mediaType = mediaType,
                    progress = derivedPct.coerceIn(0, 100),
                    resumePositionSeconds = entry.position_seconds.coerceAtLeast(0L),
                    durationSeconds = entry.duration_seconds.coerceAtLeast(0L),
                    season = entry.season,
                    episode = entry.episode,
                    episodeTitle = entry.episode_title,
                    backdropPath = entry.backdrop_path,
                    posterPath = entry.poster_path
                )
            }
            traktRepository.enrichContinueWatchingItems(mapped)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun loadContinueWatchingFromHistoryStable(): List<ContinueWatchingItem> {
        return try {
            val entries = watchHistoryRepository.getContinueWatching()
            if (entries.isEmpty()) return emptyList()
            val mapped = entries.distinctBy { entry ->
                "${entry.media_type}:${entry.show_tmdb_id}"
            }.mapNotNull { entry ->
                val mediaType = if (entry.media_type == "tv") MediaType.TV else MediaType.MOVIE
                val storedPct = (entry.progress * 100f).toInt()
                val derivedPct = if (storedPct <= 0 && entry.duration_seconds > 0 && entry.position_seconds > 0) {
                    ((entry.position_seconds.toFloat() / entry.duration_seconds.toFloat()) * 100f).toInt()
                } else {
                    storedPct
                }
                ContinueWatchingItem(
                    id = entry.show_tmdb_id,
                    title = entry.title ?: return@mapNotNull null,
                    mediaType = mediaType,
                    progress = derivedPct.coerceIn(0, 100),
                    resumePositionSeconds = entry.position_seconds.coerceAtLeast(0L),
                    durationSeconds = entry.duration_seconds.coerceAtLeast(0L),
                    season = entry.season,
                    episode = entry.episode,
                    episodeTitle = entry.episode_title,
                    backdropPath = entry.backdrop_path,
                    posterPath = entry.poster_path,
                    updatedAtMs = parseContinueWatchingUpdatedAt(entry.updated_at, entry.paused_at)
                )
            }
            traktRepository.enrichContinueWatchingItems(mapped)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveContinueWatchingItemsStable(forceFresh: Boolean): List<ContinueWatchingItem> {
        val isTraktAuthenticated = runCatching { traktRepository.isAuthenticated.first() }.getOrDefault(false)
        val items = if (isTraktAuthenticated) {
            val traktItems = if (forceFresh) {
                runCatching { traktRepository.getContinueWatching(forceRefresh = true) }.getOrDefault(emptyList())
            } else {
                val cached = traktRepository.getCachedContinueWatching()
                if (cached.isNotEmpty()) cached else runCatching { traktRepository.getContinueWatching() }.getOrDefault(emptyList())
            }
            val localItems = runCatching { traktRepository.getLocalContinueWatching() }.getOrDefault(emptyList())
            val historyItems = loadContinueWatchingFromHistoryStable()
            mergeTraktAndRecentLocalContinueWatching(
                traktItems = traktItems,
                localItems = localItems,
                historyItems = historyItems
            )
        } else {
            val historyItems = loadContinueWatchingFromHistoryStable()
            if (historyItems.isNotEmpty()) {
                historyItems
            } else {
                runCatching { traktRepository.getLocalContinueWatching() }.getOrDefault(emptyList())
            }
        }

        val persistedDismissedKeys = runCatching {
            traktRepository.getDismissedContinueWatchingShowKeys()
        }.getOrDefault(emptySet())

        return sanitizeContinueWatchingItems(items)
            .filterNot { item ->
                val showKey = continueWatchingKey(item.mediaType, item.id)
                dismissedContinueWatchingKeys.contains(showKey) || persistedDismissedKeys.contains(showKey)
            }
            .filter { item ->
                if (isTraktAuthenticated) true else item.progress in 1..99
            }
            .take(Constants.MAX_CONTINUE_WATCHING)
    }

    private suspend fun preloadStartupContinueWatchingItems(): List<ContinueWatchingItem> {
        val isTraktAuthenticated = runCatching { traktRepository.isAuthenticated.first() }.getOrDefault(false)
        val items = if (isTraktAuthenticated) {
            val localItems = runCatching { traktRepository.getLocalContinueWatching() }.getOrDefault(emptyList())
            val historyItems = loadContinueWatchingFromHistoryStable()
            mergeTraktAndRecentLocalContinueWatching(
                traktItems = emptyList(),
                localItems = localItems,
                historyItems = historyItems
            )
        } else {
            val historyItems = loadContinueWatchingFromHistoryStable()
            if (historyItems.isNotEmpty()) {
                historyItems
            } else {
                runCatching { traktRepository.getLocalContinueWatching() }.getOrDefault(emptyList())
            }
        }

        val persistedDismissedKeys = runCatching {
            traktRepository.getDismissedContinueWatchingShowKeys()
        }.getOrDefault(emptySet())

        return sanitizeContinueWatchingItems(items)
            .filterNot { item ->
                val showKey = continueWatchingKey(item.mediaType, item.id)
                dismissedContinueWatchingKeys.contains(showKey) || persistedDismissedKeys.contains(showKey)
            }
            .filter { item ->
                if (isTraktAuthenticated) true else item.progress in 1..99
            }
            .take(Constants.MAX_CONTINUE_WATCHING)
    }

    private suspend fun mergeContinueWatchingResumeData(
        items: List<ContinueWatchingItem>
    ): List<ContinueWatchingItem> {
        if (items.isEmpty()) return emptyList()
        return try {
            val historyEntries = watchHistoryRepository.getContinueWatching()
            if (historyEntries.isEmpty()) return items

            val sortedHistory = historyEntries.sortedByDescending { it.updated_at ?: it.paused_at.orEmpty() }
            val byExactKey = sortedHistory.associateBy { entry ->
                "${entry.media_type}:${entry.show_tmdb_id}:${entry.season ?: -1}:${entry.episode ?: -1}"
            }
            val byShowKey = sortedHistory.associateBy { entry ->
                "${entry.media_type}:${entry.show_tmdb_id}"
            }

            items.map { item ->
                val mediaTypeKey = if (item.mediaType == MediaType.TV) "tv" else "movie"
                val exactKey = "$mediaTypeKey:${item.id}:${item.season ?: -1}:${item.episode ?: -1}"
                val showKey = "$mediaTypeKey:${item.id}"
                // Only use exact episode-matched history, NOT show-level fallback.
                // Show-level fallback caused old episode's position to leak to the next episode.
                val match = byExactKey[exactKey]
                if (match == null) {
                    item
                } else {
                    val storedProgress = (match.progress * 100f).toInt()
                    val derivedProgress = if (storedProgress <= 0 && match.duration_seconds > 0 && match.position_seconds > 0) {
                        ((match.position_seconds.toFloat() / match.duration_seconds.toFloat()) * 100f).toInt()
                    } else {
                        storedProgress
                    }
                    item.copy(
                        progress = derivedProgress.coerceIn(0, 100),
                        resumePositionSeconds = match.position_seconds.coerceAtLeast(0L),
                        durationSeconds = match.duration_seconds.coerceAtLeast(0L),
                        season = item.season ?: match.season,
                        episode = item.episode ?: match.episode,
                        episodeTitle = item.episodeTitle ?: match.episode_title
                    )
                }
            }
        } catch (_: Exception) {
            items
        }
    }

    private fun refreshWatchedBadges(immediate: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!immediate && now - lastWatchedBadgesRefreshMs < WATCHED_BADGES_REFRESH_MS) return

        watchedBadgesJob?.cancel()
        watchedBadgesJob = viewModelScope.launch(networkDispatcher) {
            if (!immediate) {
                delay(if (isLowRamDevice) 3_000L else 1_800L)
            }
            try {
                val isAuth = traktRepository.isAuthenticated.first()
                if (!isAuth) return@launch

                traktRepository.initializeWatchedCache()
                val categories = _uiState.value.categories
                if (categories.isEmpty()) return@launch

                val watchedMovies = traktRepository.getWatchedMoviesFromCache()

                // Performance: Build show watched map only for unique TV shows
                val showWatched = mutableMapOf<Int, Boolean>()
                val seenShows = mutableSetOf<Int>()
                for (category in categories) {
                    if (category.id == "continue_watching") continue
                    for (item in category.items) {
                        if (item.mediaType == MediaType.TV && seenShows.add(item.id)) {
                            showWatched[item.id] = traktRepository.hasWatchedEpisodes(item.id)
                        }
                    }
                }

                var anyChange = false
                val updatedCategories = categories.map { category ->
                    if (category.id == "continue_watching") {
                        category
                    } else {
                        var categoryChanged = false
                        val updatedItems = category.items.map { item ->
                            val newWatched = when (item.mediaType) {
                                MediaType.MOVIE -> watchedMovies.contains(item.id)
                                MediaType.TV -> showWatched[item.id] == true
                            }
                            if (item.isWatched != newWatched) {
                                categoryChanged = true
                                item.copy(isWatched = newWatched)
                            } else {
                                item
                            }
                        }
                        if (categoryChanged) {
                            anyChange = true
                            category.copy(items = updatedItems)
                        } else {
                            category
                        }
                    }
                }

                if (!anyChange) {
                    lastWatchedBadgesRefreshMs = SystemClock.elapsedRealtime()
                    return@launch
                }

                val heroItem = _uiState.value.heroItem
                val updatedHero = heroItem?.let { hero ->
                    updatedCategories.asSequence()
                        .flatMap { it.items.asSequence() }
                        .firstOrNull { it.id == hero.id && it.mediaType == hero.mediaType }
                        ?: hero
                }

                _uiState.value = _uiState.value.copy(
                    categories = updatedCategories,
                    heroItem = updatedHero
                )
                lastWatchedBadgesRefreshMs = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                System.err.println("HomeVM: refreshWatchedBadges failed: ${e.message}")
            }
        }
    }

    /**
     * Phase 1.4 & 6.1 & 6.2-6.3: Update hero with adaptive debouncing
     * Uses fast-scroll detection for smoother experience during rapid navigation
     */
    fun updateHeroItem(item: MediaItem) {
        if (isCollectionItem(item)) {
            if (item.isPlaceholder) return
            heroUpdateJob?.cancel()
            heroDetailsJob?.cancel()
            val catalog = collectionCatalogByMediaId[item.id]
            val collectionLogo = catalog?.collectionClearLogoUrl?.takeIf { it.isNotBlank() }
            // Prefer the high-res static hero art for the backdrop so the
            // hero area reads as a cinematic still rather than a looping GIF.
            val heroBackdrop = catalog?.collectionHeroImageUrl?.takeIf { it.isNotBlank() }
                ?: item.backdrop
            val heroItem = if (heroBackdrop != null && heroBackdrop != item.backdrop) {
                item.copy(backdrop = heroBackdrop)
            } else item
            _uiState.value = _uiState.value.copy(
                previousHeroItem = _uiState.value.heroItem,
                previousHeroLogoUrl = _uiState.value.heroLogoUrl,
                heroItem = heroItem,
                heroLogoUrl = collectionLogo,
                heroOverviewOverride = item.overview,
                heroTrailerKey = null,
                isHeroTransitioning = false
            )
            return
        }
        if (!isActionableMediaItem(item)) {
            return
        }

        val cacheKey = "${item.mediaType}_${item.id}"
        val cachedLogo = getCachedLogo(cacheKey)

        // Phase 6.2-6.3: Detect fast scrolling
        val currentTime = System.currentTimeMillis()
        val timeSinceLastChange = currentTime - lastFocusChangeTime
        lastFocusChangeTime = currentTime

        val isFastScrolling = timeSinceLastChange < FAST_SCROLL_THRESHOLD_MS
        if (isFastScrolling) {
            consecutiveFastChanges++
        } else {
            consecutiveFastChanges = 0
        }

        // Adaptive debounce: higher during fast scroll sequences
        val debounceMs = when {
            consecutiveFastChanges > 3 -> FAST_SCROLL_DEBOUNCE_MS  // Very fast scroll
            consecutiveFastChanges > 1 -> HERO_DEBOUNCE_MS + 50    // Moderate fast scroll
            cachedLogo != null -> 0L  // Cached = instant
            else -> HERO_DEBOUNCE_MS  // Normal debounce
        }

        // Phase 1.4: If logo is cached and not fast-scrolling, update immediately
        val fastScrolling = consecutiveFastChanges > 1
        if (cachedLogo != null && !fastScrolling) {
            heroUpdateJob?.cancel()
            performHeroUpdate(item, cachedLogo)
            scheduleHeroDetailsFetch(item, fastScrolling)
            return
        }

        // Phase 6.1 + 6.2-6.3: Adaptive debounce
        heroUpdateJob?.cancel()
        heroDetailsJob?.cancel()
        heroUpdateJob = viewModelScope.launch {
            if (debounceMs > 0) {
                delay(debounceMs)
            }

            // Check if still the current focus after debounce
            val currentCachedLogo = getCachedLogo(cacheKey)
            performHeroUpdate(item, currentCachedLogo)
            scheduleHeroDetailsFetch(item, fastScrolling)

            // Fetch logo async if not cached (skip IPTV — uses channel logo directly)
            if (currentCachedLogo == null && isActionableMediaItem(item) && !isIptvItem(item)) {
                try {
                    val logoUrl = withContext(networkDispatcher) {
                        mediaRepository.getLogoUrl(item.mediaType, item.id)
                    }
                    if (logoUrl != null && _uiState.value.heroItem?.id == item.id) {
                        putCachedLogo(cacheKey, logoUrl)
                        _uiState.value = _uiState.value.copy(
                            heroLogoUrl = logoUrl,
                            isHeroTransitioning = false
                        )
                        scheduleLogoCachePublish()
                        // Preload the logo image
                        preloadLogoImages(listOf(logoUrl))
                    }
                } catch (e: Exception) {
                    // Logo fetch failed
                }
            }
        }
    }

    private fun performHeroUpdate(item: MediaItem, logoUrl: String?) {
        val currentState = _uiState.value
        val currentHero = currentState.heroItem
        if (currentHero?.id == item.id &&
            currentHero.mediaType == item.mediaType &&
            currentState.heroLogoUrl == logoUrl &&
            !currentState.isHeroTransitioning
        ) {
            return
        }

        // Save previous hero for crossfade animation, clear trailer for new hero
        _uiState.value = currentState.copy(
            previousHeroItem = currentState.heroItem,
            previousHeroLogoUrl = currentState.heroLogoUrl,
            heroItem = item,
            heroLogoUrl = logoUrl,
            heroOverviewOverride = null,
            heroTrailerKey = null,
            isHeroTransitioning = true
        )
    }

    private fun hydrateHeroDetailsIfNeeded(item: MediaItem) {
        if (!isActionableMediaItem(item) || isIptvItem(item) || isCollectionItem(item)) {
            return
        }

        // Always fetch trailer for new hero item
        if (_uiState.value.trailerAutoPlay) {
            // Clear previous trailer immediately
            _uiState.value = _uiState.value.copy(heroTrailerKey = null)
            viewModelScope.launch(networkDispatcher) {
                try {
                    val trailerKey = mediaRepository.getTrailerKey(item.mediaType, item.id)
                    if (trailerKey != null && _uiState.value.heroItem?.id == item.id) {
                        _uiState.value = _uiState.value.copy(heroTrailerKey = trailerKey)
                    }
                } catch (_: Exception) {}
            }
        }

        val normalizedOverview = item.overview.trim()
        val looksTruncated = normalizedOverview.endsWith("...") || normalizedOverview.length < 120
        if (normalizedOverview.isNotBlank() && !looksTruncated && item.duration.isNotBlank() && item.duration != "0m") {
            return
        }

        heroDetailsJob?.cancel()
        heroDetailsJob = viewModelScope.launch(networkDispatcher) {
            try {
                val details = runCatching {
                    if (item.mediaType == MediaType.MOVIE) {
                        mediaRepository.getMovieDetails(item.id)
                    } else {
                        mediaRepository.getTvDetails(item.id)
                    }
                }.getOrNull()
                val resolvedOverview = resolveBestOverview(
                    item = item,
                    candidateOverview = details?.overview?.ifBlank { item.overview } ?: item.overview
                )
                val detailsKey = "${item.mediaType}_${item.id}"
                val primaryNetworkLogo = resolvePrimaryNetworkLogo(item.mediaType, item.id)
                heroDetailsCache[detailsKey] = HeroDetailsSnapshot(
                    duration = details?.duration.orEmpty(),
                    releaseDate = details?.releaseDate,
                    imdbRating = details?.imdbRating.orEmpty(),
                    tmdbRating = details?.tmdbRating.orEmpty(),
                    budget = details?.budget,
                    overview = resolvedOverview,
                    primaryNetworkLogo = primaryNetworkLogo
                )

                val latestHero = _uiState.value.heroItem
                if (latestHero?.id == item.id && latestHero.mediaType == item.mediaType) {
                    val updatedHero = latestHero.copy(
                        duration = details?.duration?.ifEmpty { latestHero.duration } ?: latestHero.duration,
                        releaseDate = details?.releaseDate ?: latestHero.releaseDate,
                        imdbRating = details?.imdbRating?.ifEmpty { latestHero.imdbRating } ?: latestHero.imdbRating,
                        tmdbRating = details?.tmdbRating?.ifEmpty { latestHero.tmdbRating } ?: latestHero.tmdbRating,
                        budget = details?.budget ?: latestHero.budget,
                        overview = resolvedOverview.ifBlank { latestHero.overview },
                        primaryNetworkLogo = primaryNetworkLogo ?: latestHero.primaryNetworkLogo
                    )
                    mediaRepository.cacheItem(updatedHero)
                    _uiState.value = _uiState.value.copy(
                        heroItem = updatedHero,
                        heroOverviewOverride = resolvedOverview.ifBlank { updatedHero.overview },
                        isHeroTransitioning = false
                    )
                }

                // Fetch trailer key for hero (YouTube)
                try {
                    val trailerKey = mediaRepository.getTrailerKey(item.mediaType, item.id)
                    if (trailerKey != null && _uiState.value.heroItem?.id == item.id) {
                        _uiState.value = _uiState.value.copy(heroTrailerKey = trailerKey)
                    }
                } catch (_: Exception) {}
            } catch (_: Exception) {
            }
        }
    }

    private fun scheduleHeroDetailsFetch(item: MediaItem, fastScrolling: Boolean) {
        heroDetailsJob?.cancel()

        // Always fetch trailer for new hero item (separate from details cache)
        if (_uiState.value.trailerAutoPlay) {
            _uiState.value = _uiState.value.copy(heroTrailerKey = null)
            viewModelScope.launch(networkDispatcher) {
                try {
                    val trailerKey = mediaRepository.getTrailerKey(item.mediaType, item.id)
                    if (trailerKey != null && _uiState.value.heroItem?.id == item.id) {
                        _uiState.value = _uiState.value.copy(heroTrailerKey = trailerKey)
                    }
                } catch (_: Exception) {}
            }
        }

        heroDetailsJob = viewModelScope.launch(networkDispatcher) {
            val detailsKey = "${item.mediaType}_${item.id}"
            val cachedDetails = heroDetailsCache[detailsKey]
            if (cachedDetails != null) {
                val currentHero = _uiState.value.heroItem
                if (currentHero?.id == item.id && currentHero.mediaType == item.mediaType) {
                    val updatedHero = currentHero.copy(
                        duration = cachedDetails.duration.ifEmpty { currentHero.duration },
                        releaseDate = cachedDetails.releaseDate ?: currentHero.releaseDate,
                        imdbRating = cachedDetails.imdbRating.ifEmpty { currentHero.imdbRating },
                        tmdbRating = cachedDetails.tmdbRating.ifEmpty { currentHero.tmdbRating },
                        budget = cachedDetails.budget ?: currentHero.budget,
                        overview = cachedDetails.overview.ifBlank { currentHero.overview },
                        primaryNetworkLogo = cachedDetails.primaryNetworkLogo ?: currentHero.primaryNetworkLogo
                    )
                    mediaRepository.cacheItem(updatedHero)
                    _uiState.value = _uiState.value.copy(
                        heroItem = updatedHero,
                        heroOverviewOverride = cachedDetails.overview.ifBlank { currentHero.overview },
                        isHeroTransitioning = false
                    )
                }
                return@launch
            }

            // Keep hero metadata (duration/budget/ratings) feeling immediate.
            // Use a tiny settle delay for normal navigation and a short delay
            // while fast-scrolling to avoid redundant network churn.
            val detailsDelayMs = if (fastScrolling) 220L else 60L
            if (detailsDelayMs > 0L) {
                delay(detailsDelayMs)
            }
            val currentHero = _uiState.value.heroItem
            if (currentHero?.id != item.id) return@launch

            try {
                val details = runCatching {
                    if (item.mediaType == MediaType.MOVIE) {
                        mediaRepository.getMovieDetails(item.id)
                    } else {
                        mediaRepository.getTvDetails(item.id)
                    }
                }.getOrNull()
                val primaryNetworkLogo = resolvePrimaryNetworkLogo(item.mediaType, item.id)
                val resolvedOverview = resolveBestOverview(
                    item = item,
                    candidateOverview = details?.overview?.ifBlank { currentHero.overview } ?: currentHero.overview
                )

                val updatedItem = currentHero.copy(
                    duration = details?.duration?.ifEmpty { currentHero.duration } ?: currentHero.duration,
                    releaseDate = details?.releaseDate ?: currentHero.releaseDate,
                    imdbRating = details?.imdbRating?.ifEmpty { currentHero.imdbRating } ?: currentHero.imdbRating,
                    tmdbRating = details?.tmdbRating?.ifEmpty { currentHero.tmdbRating } ?: currentHero.tmdbRating,
                    budget = details?.budget ?: currentHero.budget,
                    overview = resolvedOverview.ifBlank { currentHero.overview },
                    primaryNetworkLogo = primaryNetworkLogo ?: currentHero.primaryNetworkLogo
                )
                heroDetailsCache[detailsKey] = HeroDetailsSnapshot(
                    duration = details?.duration.orEmpty(),
                    releaseDate = details?.releaseDate,
                    imdbRating = details?.imdbRating.orEmpty(),
                    tmdbRating = details?.tmdbRating.orEmpty(),
                    budget = details?.budget,
                    overview = resolvedOverview,
                    primaryNetworkLogo = primaryNetworkLogo
                )
                mediaRepository.cacheItem(updatedItem)
                _uiState.value = _uiState.value.copy(
                    heroItem = updatedItem,
                    heroOverviewOverride = resolvedOverview.ifBlank { updatedItem.overview },
                    isHeroTransitioning = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isHeroTransitioning = false)
            }
        }
    }

    /**
     * Phase 1.3: Ahead-of-focus preloading
     * Call this when focus changes to preload nearby items
     */
    fun onFocusChanged(rowIndex: Int, itemIndex: Int, shouldPrefetch: Boolean = true) {
        currentRowIndex = rowIndex
        currentItemIndex = itemIndex
        lastFocusChangeTime = System.currentTimeMillis()
        if (!shouldPrefetch) {
            prefetchJob?.cancel()
            return
        }

        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(networkDispatcher) {
            delay(FOCUS_PREFETCH_COALESCE_MS)

            val categories = _uiState.value.categories
            if (rowIndex < 0 || rowIndex >= categories.size) return@launch

            val category = categories[rowIndex]

            if (category.items.isEmpty()) return@launch
            if (category.id.startsWith("collection_row_")) return@launch

            // Ensure focused card + next 4 cards get logo priority.
            val startIndex = itemIndex.coerceIn(0, category.items.lastIndex)
            val endIndex = minOf(itemIndex + 4, category.items.lastIndex)
            if (startIndex > endIndex) return@launch

            val focusWindowItems = (startIndex..endIndex)
                .mapNotNull { category.items.getOrNull(it) }
                .filter { isActionableMediaItem(it) }

            val itemsToLoad = focusWindowItems.filter { item ->
                val key = "${item.mediaType}_${item.id}"
                !hasCachedLogo(key) && logoFetchInFlight.add(key)
            }

            if (itemsToLoad.isEmpty()) return@launch

            // Fetch logos for focused window
            val logoJobs = itemsToLoad.map { item ->
                async(networkDispatcher) {
                    val key = "${item.mediaType}_${item.id}"
                    try {
                        val logoUrl = mediaRepository.getLogoUrl(item.mediaType, item.id)
                        if (logoUrl != null) key to logoUrl else null
                    } catch (e: Exception) {
                        null
                    } finally {
                        logoFetchInFlight.remove(key)
                    }
                }
            }
            val newLogos = logoJobs.awaitAll().filterNotNull().toMap()

            if (newLogos.isNotEmpty()) {
                if (putCachedLogos(newLogos)) {
                    scheduleLogoCachePublish(highPriority = true)
                }
                // Preload actual images
                preloadLogoImages(newLogos.values.toList())
            }

            // Also preload backdrops for focused window
            val backdropUrls = focusWindowItems
                .take(incrementalBackdropPrefetchItems + 1)
                .mapNotNull { it.backdrop ?: it.image }
            preloadBackdropImages(backdropUrls)
        }
    }

    /**
     * Phase 1.1: Preload logos for category + next 2 categories
     */
    fun preloadLogosForCategory(categoryIndex: Int, prioritizeVisible: Boolean = false) {
        if (prioritizeVisible) {
            preloadCategoryPriorityJob?.cancel()
        } else {
            preloadCategoryJob?.cancel()
        }
        val targetJob = viewModelScope.launch(networkDispatcher) {
            delay(
                if (prioritizeVisible) {
                    if (isLowRamDevice) 60L else 30L
                } else {
                    if (isLowRamDevice) 200L else 100L
                }
            )
            val categories = _uiState.value.categories
            if (categoryIndex < 0 || categoryIndex >= categories.size) return@launch
            val category = categories[categoryIndex]
            if (category.id.startsWith("collection_row_")) return@launch
            val maxLogoItems = if (prioritizeVisible) prioritizedLogoPrefetchItems else incrementalLogoPrefetchItems

            val itemsToLoad = category.items.take(maxLogoItems).filter { item ->
                if (!isActionableMediaItem(item)) return@filter false
                if (isIptvItem(item)) return@filter false  // IPTV items use channel logo directly
                val key = "${item.mediaType}_${item.id}"
                !hasCachedLogo(key) && logoFetchInFlight.add(key)
            }

            if (itemsToLoad.isNotEmpty()) {
                val logoJobs = itemsToLoad.map { item ->
                    async(networkDispatcher) {
                        val key = "${item.mediaType}_${item.id}"
                        try {
                            val logoUrl = mediaRepository.getLogoUrl(item.mediaType, item.id)
                            if (logoUrl != null) key to logoUrl else null
                        } catch (e: Exception) {
                            null
                        } finally {
                            logoFetchInFlight.remove(key)
                        }
                    }
                }
                val newLogos = logoJobs.awaitAll().filterNotNull().toMap()
                if (newLogos.isNotEmpty()) {
                    if (putCachedLogos(newLogos)) {
                        scheduleLogoCachePublish(highPriority = prioritizeVisible)
                    }
                    // Preload actual images
                    preloadLogoImages(newLogos.values.toList())
                }
            }

            // Also preload backdrops
            val backdropItems = if (prioritizeVisible) {
                incrementalBackdropPrefetchItems + 1
            } else {
                incrementalBackdropPrefetchItems
            }
            val backdropUrls = category.items.take(backdropItems).mapNotNull { it.backdrop ?: it.image }
            preloadBackdropImages(backdropUrls)
        }
        if (prioritizeVisible) {
            preloadCategoryPriorityJob = targetJob
        } else {
            preloadCategoryJob = targetJob
        }
    }

    /**
     * Clear hero transition state after animation completes
     */
    fun onHeroTransitionComplete() {
        _uiState.value = _uiState.value.copy(
            previousHeroItem = null,
            previousHeroLogoUrl = null,
            isHeroTransitioning = false
        )
    }

    fun toggleWatchlist(item: MediaItem) {
        viewModelScope.launch {
            try {
                val isInWatchlist = watchlistRepository.isInWatchlist(item.mediaType, item.id)
                if (isInWatchlist) {
                    watchlistRepository.removeFromWatchlist(item.mediaType, item.id)
                } else {
                    watchlistRepository.addToWatchlist(item.mediaType, item.id)
                }
                runCatching { cloudSyncRepository.pushToCloud() }
                _uiState.value = _uiState.value.copy(
                    toastMessage = if (isInWatchlist) "Removed from watchlist" else "Added to watchlist",
                    toastType = ToastType.SUCCESS
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Failed to update watchlist",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun toggleWatched(item: MediaItem) {
        viewModelScope.launch {
            try {
                if (item.mediaType == MediaType.MOVIE) {
                    if (item.isWatched) {
                        traktRepository.markMovieUnwatched(item.id)
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "Marked as unwatched",
                            toastType = ToastType.SUCCESS
                        )
                    } else {
                        traktRepository.markMovieWatched(item.id)
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "Marked as watched",
                            toastType = ToastType.SUCCESS
                        )
                    }
                } else {
                    val nextEp = item.nextEpisode
                    if (nextEp != null) {
                        // OPTIMISTIC UI UPDATE: Remove from CW and show toast immediately
                        val updatedCategories = _uiState.value.categories.map { category ->
                            if (category.id == "continue_watching") {
                                category.copy(items = category.items.filter { it.id != item.id })
                            } else {
                                category
                            }
                        }.filter { category ->
                            category.id != "continue_watching" || category.items.isNotEmpty()
                        }

                        _uiState.value = _uiState.value.copy(
                            categories = updatedCategories,
                            toastMessage = "S${nextEp.seasonNumber}E${nextEp.episodeNumber} marked as watched",
                            toastType = ToastType.SUCCESS
                        )

                        // Sync to backend after UI update (these may be slow for non-Trakt/non-Cloud profiles)
                        traktRepository.markEpisodeWatched(item.id, nextEp.seasonNumber, nextEp.episodeNumber)
                        watchHistoryRepository.removeFromHistory(item.id, nextEp.seasonNumber, nextEp.episodeNumber)

                        // Save the NEXT episode to CW (local + cloud) so it appears on all devices
                        try {
                            // Handle season boundaries: if this was the last episode of the season, move to next season
                            var followingSeason = nextEp.seasonNumber
                            var followingEpisode = nextEp.episodeNumber + 1
                            val seasonEpisodes = mediaRepository.getSeasonEpisodes(item.id, nextEp.seasonNumber)
                            if (seasonEpisodes != null && followingEpisode > seasonEpisodes.size) {
                                followingSeason = nextEp.seasonNumber + 1
                                followingEpisode = 1
                            }
                            traktRepository.saveLocalContinueWatching(
                                mediaType = MediaType.TV,
                                tmdbId = item.id,
                                title = item.title,
                                posterPath = item.image,
                                backdropPath = item.backdrop,
                                season = followingSeason,
                                episode = followingEpisode,
                                episodeTitle = null,
                                progress = 3,
                                positionSeconds = 0L,
                                durationSeconds = 1L,
                                year = item.year
                            )
                            watchHistoryRepository.saveProgress(
                                mediaType = MediaType.TV,
                                tmdbId = item.id,
                                title = item.title,
                                poster = item.image,
                                backdrop = item.backdrop,
                                season = followingSeason,
                                episode = followingEpisode,
                                episodeTitle = null,
                                progress = 0.01f,
                                duration = 0L,
                                position = 0L
                            )
                            lastContinueWatchingUpdateMs = 0L
                            refreshContinueWatchingOnly(force = true)
                        } catch (_: Exception) {}
                    } else {
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "No episode info available",
                            toastType = ToastType.ERROR
                        )
                    }
                }
                runCatching { launcherContinueWatchingRepository.refreshForCurrentProfile() }
                // Push cloud snapshot so other devices see the watched-status change
                // and the updated Continue Watching entry. Without this, the snapshot
                // (localCW, localWatchedMovies, localWatchedEpisodes, dismissedCW)
                // was never updated — only the Supabase watch_history table was.
                runCatching { cloudSyncRepository.pushToCloud() }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Failed to update watched status",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun markWatched(item: MediaItem) {
        viewModelScope.launch {
            try {
                if (item.mediaType == MediaType.MOVIE) {
                    if (!item.isWatched) {
                        traktRepository.markMovieWatched(item.id)
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "Marked as watched",
                            toastType = ToastType.SUCCESS
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "Already watched",
                            toastType = ToastType.INFO
                        )
                    }
                } else {
                    val nextEp = item.nextEpisode
                    if (nextEp != null) {
                        // OPTIMISTIC UI UPDATE: Remove from CW and show toast immediately
                        val updatedCategories = _uiState.value.categories.map { category ->
                            if (category.id == "continue_watching") {
                                category.copy(items = category.items.filter { it.id != item.id })
                            } else {
                                category
                            }
                        }.filter { category ->
                            category.id != "continue_watching" || category.items.isNotEmpty()
                        }

                        _uiState.value = _uiState.value.copy(
                            categories = updatedCategories,
                            toastMessage = "S${nextEp.seasonNumber}E${nextEp.episodeNumber} marked as watched",
                            toastType = ToastType.SUCCESS
                        )

                        // Sync to backend after UI update (these may be slow for non-Trakt/non-Cloud profiles)
                        try {
                            traktRepository.markEpisodeWatched(item.id, nextEp.seasonNumber, nextEp.episodeNumber)
                        } catch (_: Exception) {}
                        try {
                            watchHistoryRepository.removeFromHistory(item.id, nextEp.seasonNumber, nextEp.episodeNumber)
                        } catch (_: Exception) {}

                        // Save the NEXT episode to CW (local + cloud) so it appears on all devices
                        try {
                            var followingSeason = nextEp.seasonNumber
                            var followingEpisode = nextEp.episodeNumber + 1
                            val seasonEps = mediaRepository.getSeasonEpisodes(item.id, nextEp.seasonNumber)
                            if (seasonEps != null && followingEpisode > seasonEps.size) {
                                followingSeason = nextEp.seasonNumber + 1
                                followingEpisode = 1
                            }
                            traktRepository.saveLocalContinueWatching(
                                mediaType = MediaType.TV,
                                tmdbId = item.id,
                                title = item.title,
                                posterPath = item.image,
                                backdropPath = item.backdrop,
                                season = followingSeason,
                                episode = followingEpisode,
                                episodeTitle = null,
                                progress = 1,
                                positionSeconds = 0L,
                                durationSeconds = 1L,
                                year = item.year
                            )
                            // Also save to Supabase for cross-device sync
                            watchHistoryRepository.saveProgress(
                                mediaType = MediaType.TV,
                                tmdbId = item.id,
                                title = item.title,
                                poster = item.image,
                                backdrop = item.backdrop,
                                season = followingSeason,
                                episode = followingEpisode,
                                episodeTitle = null,
                                progress = 0.01f,
                                duration = 0L,
                                position = 0L
                            )
                            // Reset throttle so refresh actually runs
                            lastContinueWatchingUpdateMs = 0L
                            refreshContinueWatchingOnly(force = true)
                        } catch (_: Exception) {}
                    } else {
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "No episode info available",
                            toastType = ToastType.ERROR
                        )
                    }
                }
                runCatching { launcherContinueWatchingRepository.refreshForCurrentProfile() }
                // Push cloud snapshot so other devices see watched status + CW update
                runCatching { cloudSyncRepository.pushToCloud() }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Failed to update watched status",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun dismissToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    suspend fun isInWatchlist(item: MediaItem): Boolean {
        return watchlistRepository.isInWatchlist(item.mediaType, item.id)
    }

    fun removeFromContinueWatching(item: MediaItem) {
        viewModelScope.launch {
            try {
                val season = if (item.mediaType == MediaType.TV) item.nextEpisode?.seasonNumber else null
                val episode = if (item.mediaType == MediaType.TV) item.nextEpisode?.episodeNumber else null
                dismissedContinueWatchingKeys.add(continueWatchingKey(item.mediaType, item.id))

                watchHistoryRepository.removeFromHistory(item.id, season, episode)
                traktRepository.deletePlaybackForContent(item.id, item.mediaType)
                traktRepository.removeFromContinueWatchingCache(item.id, null, null)
                traktRepository.dismissContinueWatching(item)
                runCatching { cloudSyncRepository.pushToCloud() }

                val updatedCategories = _uiState.value.categories.map { category ->
                    if (category.id == "continue_watching") {
                        category.copy(items = category.items.filter { it.id != item.id })
                    } else {
                        category
                    }
                }.filter { category ->
                    category.id != "continue_watching" || category.items.isNotEmpty()
                }

                _uiState.value = _uiState.value.copy(
                    categories = updatedCategories,
                    toastMessage = "Removed from Continue Watching",
                    toastType = ToastType.SUCCESS
                )
                runCatching { launcherContinueWatchingRepository.refreshForCurrentProfile() }
                updatedCategories.firstOrNull { it.id == "continue_watching" }?.let { category ->
                    lastContinueWatchingItems = category.items
                    lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
                } ?: run {
                    lastContinueWatchingItems = emptyList()
                    lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Failed to remove from Continue Watching",
                    toastType = ToastType.ERROR
                )
            }
        }
    }
}



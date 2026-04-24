package com.arflix.tv.ui.screens.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.model.IptvSnapshot
import com.arflix.tv.data.repository.CloudSyncRepository
import com.arflix.tv.data.repository.IptvConfig
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.data.repository.IptvTvSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal const val FAVORITES_GROUP_NAME = "My Favorites"

data class TvUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val loadingMessage: String? = null,
    val loadingPercent: Int = 0,
    val config: IptvConfig = IptvConfig(),
    val snapshot: IptvSnapshot = IptvSnapshot(),
    val channelLookup: Map<String, IptvChannel> = emptyMap(),
    val groups: List<String> = emptyList(),
    val channelsByGroup: Map<String, List<IptvChannel>> = emptyMap(),
    val tvSession: IptvTvSessionState = IptvTvSessionState(),
    val favoritesOnly: Boolean = false,
    val query: String = ""
) {
    val isConfigured: Boolean get() =
        config.m3uUrl.isNotBlank() ||
            config.stalkerPortalUrl.isNotBlank() ||
            config.playlists.any { it.enabled && it.m3uUrl.isNotBlank() }
}

@HiltViewModel
class TvViewModel @Inject constructor(
    val iptvRepository: IptvRepository,
    private val cloudSyncRepository: CloudSyncRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvUiState())
    val uiState: StateFlow<TvUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var epgRefreshJob: Job? = null
    private var warmVodJob: Job? = null
    private var pendingForcedReload: Boolean = false
    private var periodicEpgJob: Job? = null
    private var iptvCloudSyncJob: Job? = null
    private var lastObservedConfigSignature: String? = null
    private var lastAutomaticEpgReloadAt: Long = 0L
    private var visibleEpgRefreshJob: Job? = null
    private var lastVisibleEpgRefreshKey: String? = null
    private var lastVisibleEpgRefreshAt: Long = 0L
    private var tvSessionSaveJob: Job? = null
    private var startupGuideWarmupKey: String? = null

    /**
     * In-memory cache of the live-TV enriched channel list + category tree.
     * Persists across screen visits for the lifetime of the ViewModel (which
     * Hilt scopes to the nav backstack entry). Keeps the sub-second first
     * paint when returning to the TV screen — the costly enrichment of 52k
     * channels only runs once per session.
     */
    @Volatile var cachedEnrichedChannels: Any? = null
    @Volatile var cachedChannelsSignature: String? = null

    init {
        observeConfigAndFavorites()
        observeTvSession()
        viewModelScope.launch {
            runCatching { iptvRepository.warmupFromCacheOnly() }
            // Try fast non-blocking in-memory read first; fall back to mutex-guarded disk read
            val cached = iptvRepository.getMemoryCachedSnapshot()
                ?: iptvRepository.getCachedSnapshotOrNull()
            if (cached != null) {
                val config = iptvRepository.observeConfig().first()
                // The observeConfigAndFavorites() coroutine may have already read fresh
                // favorites from DataStore before this cached snapshot was loaded from disk.
                // Prefer those in-memory favorites over whatever was baked into the cache,
                // which can be stale if the user added/removed favorites since the last save.
                val liveSnapshot = _uiState.value.snapshot
                val snapshotToUse = if (liveSnapshot.favoriteChannels.isNotEmpty() || liveSnapshot.favoriteGroups.isNotEmpty()) {
                    cached.copy(
                        favoriteChannels = liveSnapshot.favoriteChannels,
                        favoriteGroups = liveSnapshot.favoriteGroups,
                        hiddenGroups = liveSnapshot.hiddenGroups,
                        groupOrder = liveSnapshot.groupOrder
                    )
                } else {
                    cached
                }
                setUiState(
                    _uiState.value.copy(
                        isLoading = false,
                        error = null,
                        snapshot = snapshotToUse,
                        loadingMessage = null,
                        loadingPercent = 0
                    )
                )
                maybeWarmStartupGuide()
                warmXtreamVodCache()
                val hasPotentialEpg = config.epgUrl.isNotBlank() || config.m3uUrl.contains("get.php", ignoreCase = true) || config.m3uUrl.contains("player_api.php", ignoreCase = true)
                val needsChannelReload = config.m3uUrl.isNotBlank() && cached.channels.isEmpty()
                // Only force EPG refresh if cached EPG is older than 2 minutes.
                // When navigating from Home, EPG was likely just loaded — no need to re-fetch.
                val epgAgeMs = iptvRepository.cachedEpgAgeMs()
                val epgIsRecent = epgAgeMs < 120_000L
                val epgCoverage = epgCoverageRatio(cached)
                if (needsChannelReload) {
                    // Only situation that still requires a blocking refresh:
                    // there are literally no channels to show.
                    refresh(force = true, showLoading = false, forceEpg = false)
                } else {
                    // In every other case render the warm cache instantly —
                    // never block the TV page on EPG. The active category
                    // will request guide data on demand once the user lands
                    // there, instead of broad startup sweeps.
                    if (iptvRepository.isSnapshotStale(cached)) {
                        refresh(force = false, showLoading = false, forceEpg = false)
                    } else {
                        System.err.println("[EPG] Startup: using warm cached EPG (age=${epgAgeMs / 1000}s)")
                    }
                }
            } else {
                refresh(force = false, showLoading = false, forceEpg = false)
            }
            startPeriodicEpgRefresh()
        }
    }

    private fun observeTvSession() {
        viewModelScope.launch {
            iptvRepository.observeTvSessionState()
                .distinctUntilChanged()
                .collect { session ->
                    _uiState.value = _uiState.value.copy(tvSession = session)
                    maybeWarmStartupGuide()
                }
        }
    }

    private fun observeConfigAndFavorites() {
        viewModelScope.launch {
            combine(
                combine(iptvRepository.observeConfig(), iptvRepository.observeFavoriteGroups(), iptvRepository.observeFavoriteChannels()) { a, b, c -> Triple(a, b, c) },
                iptvRepository.observeHiddenGroups(),
                iptvRepository.observeGroupOrder()
            ) { triple, hiddenGroups, groupOrder ->
                Triple(triple, hiddenGroups, groupOrder)
            }
                .distinctUntilChanged()
                .collect { (triple, hiddenGroups, groupOrder) ->
                val (config, favoriteGroups, favoriteChannels) = triple
                val newConfigSignature = config.syncSignature()
                val configChanged = lastObservedConfigSignature != null &&
                    lastObservedConfigSignature != newConfigSignature
                lastObservedConfigSignature = newConfigSignature
                val snapshot = _uiState.value.snapshot.copy(
                    favoriteGroups = favoriteGroups,
                    favoriteChannels = favoriteChannels,
                    hiddenGroups = hiddenGroups,
                    groupOrder = groupOrder
                )
                setUiState(_uiState.value.copy(config = config, snapshot = snapshot))
                maybeWarmStartupGuide()

                val hasAnyIptvConfig = config.m3uUrl.isNotBlank() ||
                    config.stalkerPortalUrl.isNotBlank() ||
                    config.playlists.any { it.enabled && it.m3uUrl.isNotBlank() }

                // Auto-heal cases where the app has IPTV config but an empty in-memory snapshot.
                if (hasAnyIptvConfig && snapshot.channels.isEmpty() && refreshJob?.isActive != true) {
                    refresh(force = false, showLoading = false)
                } else if (configChanged && refreshJob?.isActive != true) {
                    cachedEnrichedChannels = null
                    cachedChannelsSignature = null
                    refresh(force = true, showLoading = false, forceEpg = false)
                }
            }
        }
    }

    fun refresh(force: Boolean, showLoading: Boolean = true, forceEpg: Boolean = false) {
        if (refreshJob?.isActive == true) return
        if (force) {
            epgRefreshJob?.cancel()
        }

        refreshJob = viewModelScope.launch {
            val hasExistingChannels = _uiState.value.snapshot.channels.isNotEmpty()
            if (showLoading && !hasExistingChannels) {
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    error = null,
                    loadingMessage = "Starting IPTV load...",
                    loadingPercent = 2
                )
            }
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(45_000L) {
                    iptvRepository.loadSnapshot(
                        forcePlaylistReload = force,
                        forceEpgReload = forceEpg,
                        allowNetworkEpgFetch = false,
                        onProgress = { progress ->
                            if (showLoading && !hasExistingChannels) {
                                _uiState.value = _uiState.value.copy(
                                    isLoading = true,
                                    loadingMessage = progress.message,
                                    loadingPercent = progress.percent ?: _uiState.value.loadingPercent
                                )
                            }
                        },
                        onChannelsReady = { channels ->
                            // Publish channels to UI immediately — don't wait for EPG.
                            // This makes the TV page responsive even on cold start with no cache.
                            val cachedNowNext = withContext(Dispatchers.Default) {
                                iptvRepository.reDeriveCachedNowNext(
                                    channels.asSequence().map { it.id }.toSet()
                                ).orEmpty()
                            }
                            val currentSnapshot = _uiState.value.snapshot
                            // Rebuild grouped from the new channels so that all playlist
                            // groups (Norway, Sweden, Denmark …) appear immediately rather
                            // than inheriting the stale groups from the previous snapshot.
                            val freshGrouped = channels.groupBy { it.group.ifBlank { "Uncategorized" } }
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                error = null,
                                snapshot = currentSnapshot.copy(
                                    channels = channels,
                                    grouped = freshGrouped,
                                    nowNext = if (cachedNowNext.isNotEmpty()) {
                                        currentSnapshot.nowNext.toMutableMap().apply { putAll(cachedNowNext) }
                                    } else {
                                        currentSnapshot.nowNext
                                    }
                                ),
                                loadingMessage = null,
                                loadingPercent = 0
                            )
                        }
                    )
                } ?: throw IllegalStateException("IPTV load timed out")
            }.onSuccess { snapshot ->
                cachedEnrichedChannels = null
                cachedChannelsSignature = null
                setUiState(
                    _uiState.value.copy(
                        isLoading = false,
                        error = null,
                        snapshot = snapshot,
                        loadingMessage = null,
                        loadingPercent = 0
                    )
                )
                maybeWarmStartupGuide()
                warmXtreamVodCache()
                if (!force && _uiState.value.isConfigured && snapshot.channels.isEmpty()) {
                    // Soft refresh returned empty even though IPTV is configured:
                    // schedule one forced reload to bypass stale in-memory paths.
                    pendingForcedReload = true
                }
            }.onFailure { error ->
                val fallback = runCatching {
                    iptvRepository.getMemoryCachedSnapshot() ?: iptvRepository.getCachedSnapshotOrNull()
                }.getOrNull()
                if (fallback != null && fallback.channels.isNotEmpty()) {
                    setUiState(
                        _uiState.value.copy(
                            isLoading = false,
                            error = null,
                            snapshot = fallback,
                            loadingMessage = null,
                            loadingPercent = 0
                        )
                    )
                    maybeWarmStartupGuide()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "Failed to load IPTV",
                        loadingMessage = null,
                        loadingPercent = 0
                    )
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                refreshJob = null
                if (pendingForcedReload) {
                    pendingForcedReload = false
                    refresh(force = true, showLoading = false, forceEpg = false)
                }
            }
        }
    }

    private fun warmXtreamVodCache() {
        if (warmVodJob?.isActive == true) return
        warmVodJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { iptvRepository.warmXtreamVodCachesIfPossible() }
        }.also { job ->
            job.invokeOnCompletion { warmVodJob = null }
        }
    }

    private fun hasAnyEpgData(snapshot: IptvSnapshot): Boolean {
        if (snapshot.nowNext.isEmpty()) return false
        return snapshot.nowNext.values.any { item ->
            item.now != null || item.next != null || item.later != null || item.upcoming.isNotEmpty()
        }
    }

    private fun hasProgramData(item: com.arflix.tv.data.model.IptvNowNext?): Boolean {
        return item != null && (
            item.now != null ||
                item.next != null ||
                item.later != null ||
                item.upcoming.isNotEmpty()
            )
    }

    private suspend fun refreshGuideFromCache() {
        val channelIds = _uiState.value.snapshot.channels.asSequence().map { it.id }.toSet()
        if (channelIds.isEmpty()) return
        val updated = withContext(Dispatchers.Default) {
            iptvRepository.reDeriveCachedNowNext(channelIds)
        } ?: return
        val current = _uiState.value
        _uiState.value = current.copy(
            snapshot = current.snapshot.copy(
                nowNext = current.snapshot.nowNext.toMutableMap().apply { putAll(updated) }
            )
        )
    }

    private fun epgCoverageRatio(snapshot: IptvSnapshot): Float {
        if (snapshot.channels.isEmpty()) return 0f
        val covered = snapshot.channels.count { ch ->
            val item = snapshot.nowNext[ch.id]
            item != null && (item.now != null || item.next != null || item.later != null || item.upcoming.isNotEmpty())
        }
        return covered.toFloat() / snapshot.channels.size.toFloat()
    }

    private fun startPeriodicEpgRefresh() {
        if (periodicEpgJob?.isActive == true) return
        periodicEpgJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000L)
                val state = _uiState.value
                if (state.isConfigured && state.snapshot.channels.isNotEmpty()) {
                    refreshGuideFromCache()
                }
            }
        }
    }

    fun setQuery(query: String) {
        setUiState(_uiState.value.copy(query = query))
    }

    fun toggleFavoriteGroup(groupName: String) {
        viewModelScope.launch {
            iptvRepository.toggleFavoriteGroup(groupName)
            scheduleIptvCloudSync()
        }
    }

    fun toggleFavoriteChannel(channelId: String) {
        viewModelScope.launch {
            iptvRepository.toggleFavoriteChannel(channelId)
            scheduleIptvCloudSync()
        }
    }

    fun toggleHiddenGroup(groupName: String) {
        viewModelScope.launch { iptvRepository.toggleHiddenGroup(groupName); scheduleIptvCloudSync() }
    }

    fun prefetchVisibleCategoryEpg(
        channelIds: List<String>,
        selectedChannelId: String?,
        eagerLimit: Int = 96,
        backgroundLimit: Int = 640
    ) {
        if (channelIds.isEmpty()) return
        val orderedIds = buildList {
            selectedChannelId?.takeIf { it in channelIds }?.let { add(it) }
            channelIds.forEach { id ->
                if (id != selectedChannelId) add(id)
            }
        }
        if (orderedIds.isEmpty()) return

        val currentNowNext = _uiState.value.snapshot.nowNext
        val missingCount = orderedIds.count { !hasProgramData(currentNowNext[it]) }
        if (missingCount == 0) return

        val refreshKey = buildString {
            append(selectedChannelId.orEmpty())
            append('|')
            append(orderedIds.size)
            append('|')
            append(eagerLimit)
            append('|')
            append(orderedIds.firstOrNull().orEmpty())
            append('|')
            append(orderedIds.lastOrNull().orEmpty())
        }
        val now = System.currentTimeMillis()
        if (refreshKey == lastVisibleEpgRefreshKey && now - lastVisibleEpgRefreshAt < 20_000L) return

        lastVisibleEpgRefreshKey = refreshKey
        lastVisibleEpgRefreshAt = now
        visibleEpgRefreshJob?.cancel()
        visibleEpgRefreshJob = viewModelScope.launch {
            val eagerIds = orderedIds.take(eagerLimit.coerceAtLeast(1))
            System.err.println("[EPG-Category] eager=${eagerIds.size} totalVisible=${orderedIds.size} selected=${selectedChannelId.orEmpty()}")
            val eagerRefreshed = runCatching {
                iptvRepository.refreshEpgForChannels(
                    eagerIds.toSet(),
                    maxChannels = eagerIds.size
                )
            }.getOrNull()

            if (!eagerRefreshed.isNullOrEmpty()) {
                val current = _uiState.value
                _uiState.value = current.copy(
                    snapshot = current.snapshot.copy(
                        nowNext = current.snapshot.nowNext.toMutableMap().apply { putAll(eagerRefreshed) }
                    )
                )
            }

            val backgroundIds = orderedIds
                .drop(eagerIds.size)
                .take(backgroundLimit.coerceAtLeast(0))
                .filterNot { id -> hasProgramData(_uiState.value.snapshot.nowNext[id]) }
            if (backgroundIds.isEmpty()) return@launch

            System.err.println("[EPG-Category] background=${backgroundIds.size} selected=${selectedChannelId.orEmpty()}")
            val backgroundRefreshed = runCatching {
                iptvRepository.refreshEpgForChannels(
                    backgroundIds.toSet(),
                    maxChannels = backgroundIds.size
                )
            }.getOrNull()

            if (!backgroundRefreshed.isNullOrEmpty()) {
                val current = _uiState.value
                _uiState.value = current.copy(
                    snapshot = current.snapshot.copy(
                        nowNext = current.snapshot.nowNext.toMutableMap().apply { putAll(backgroundRefreshed) }
                    )
                )
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (visibleEpgRefreshJob === job) {
                    visibleEpgRefreshJob = null
                }
            }
        }
    }

    fun moveGroupUp(groupName: String) {
        viewModelScope.launch {
            val current = _uiState.value.groups.filterNot { it == FAVORITES_GROUP_NAME }
            iptvRepository.moveGroupUp(groupName, current)
            scheduleIptvCloudSync()
        }
    }

    fun moveGroupDown(groupName: String) {
        viewModelScope.launch {
            val current = _uiState.value.groups.filterNot { it == FAVORITES_GROUP_NAME }
            iptvRepository.moveGroupDown(groupName, current)
            scheduleIptvCloudSync()
        }
    }

    fun rememberTvSession(
        lastChannelId: String?,
        lastGroupName: String?,
        lastFocusedZone: String,
        markOpened: Boolean = false
    ) {
        val current = _uiState.value.tvSession
        val normalizedChannelId = lastChannelId.orEmpty().trim().ifBlank { current.lastChannelId }
        val normalizedGroupName = lastGroupName.orEmpty().trim().ifBlank { current.lastGroupName }
        val normalizedFocusZone = lastFocusedZone.trim().ifBlank { current.lastFocusedZone.ifBlank { "GUIDE" } }
        val channelChanged = normalizedChannelId.isNotBlank() && normalizedChannelId != current.lastChannelId
        val next = current.copy(
            lastChannelId = normalizedChannelId,
            lastGroupName = normalizedGroupName,
            lastFocusedZone = normalizedFocusZone,
            lastOpenedAt = if (markOpened || channelChanged) System.currentTimeMillis() else current.lastOpenedAt
        )
        if (next == current) return

        _uiState.value = _uiState.value.copy(tvSession = next)
        maybeWarmStartupGuide()
        tvSessionSaveJob?.cancel()
        tvSessionSaveJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(if (markOpened || channelChanged) 0L else 220L)
            iptvRepository.saveTvSessionState(next)
            if (markOpened || channelChanged) {
                scheduleIptvCloudSync()
            }
        }
    }

    private fun setUiState(nextState: TvUiState) {
        val previous = _uiState.value
        _uiState.value = if (previous.snapshot == nextState.snapshot && previous.query == nextState.query) {
            nextState.copy(
                channelLookup = previous.channelLookup,
                groups = previous.groups,
                channelsByGroup = previous.channelsByGroup
            )
        } else {
            setPreparedContent(nextState)
        }
    }

    private fun maybeWarmStartupGuide() {
        val state = _uiState.value
        if (state.channelsByGroup.isEmpty()) return

        val warmGroups = buildStartupWarmGroups(state)
        if (warmGroups.isEmpty()) return
        val warmChannels = buildList {
            warmGroups.forEachIndexed { index, groupName ->
                val limit = if (groupName == FAVORITES_GROUP_NAME || index == 0) 96 else 56
                addAll(state.channelsByGroup[groupName].orEmpty().take(limit))
            }
        }
            .distinctBy { it.id }
        if (warmChannels.isEmpty()) return

        val coverage = warmChannels.count { hasProgramData(state.snapshot.nowNext[it.id]) }
        if (coverage >= minOf(warmChannels.size, 24)) return

        val preferredSelectedId = state.tvSession.lastChannelId
            .takeIf { id -> id.isNotBlank() && warmChannels.any { channel -> channel.id == id } }
            ?: warmChannels.firstOrNull()?.id
        val warmupKey = buildString {
            append(warmGroups.joinToString(","))
            append('|')
            append(warmChannels.firstOrNull()?.id.orEmpty())
            append('|')
            append(warmChannels.size)
            append('|')
            append(preferredSelectedId.orEmpty())
        }
        if (warmupKey == startupGuideWarmupKey) return
        startupGuideWarmupKey = warmupKey

        prefetchVisibleCategoryEpg(
            channelIds = warmChannels.map { it.id },
            selectedChannelId = preferredSelectedId,
            eagerLimit = minOf(warmChannels.size, 520),
            backgroundLimit = minOf(warmChannels.size, 1600)
        )
    }

    private fun scheduleIptvCloudSync() {
        iptvCloudSyncJob?.cancel()
        iptvCloudSyncJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(350L)
            val firstAttempt = runCatching { cloudSyncRepository.pushToCloud() }.getOrNull()
            if (firstAttempt?.isFailure != false) {
                kotlinx.coroutines.delay(1_200L)
                runCatching { cloudSyncRepository.pushToCloud() }
            }
        }
    }
}

private fun buildStartupWarmGroups(state: TvUiState): List<String> {
    if (state.channelsByGroup.isEmpty()) return emptyList()
    val favoritesFirst = state.channelsByGroup[FAVORITES_GROUP_NAME]
        .orEmpty()
        .takeIf { it.isNotEmpty() }
        ?.let { listOf(FAVORITES_GROUP_NAME) }
        .orEmpty()
    val sessionGroup = state.tvSession.lastGroupName
        .takeIf { it.isNotBlank() && state.channelsByGroup[it].orEmpty().isNotEmpty() }
        ?.let(::listOf)
        .orEmpty()
    val favoriteGroups = state.snapshot.favoriteGroups
        .filter { state.channelsByGroup[it].orEmpty().isNotEmpty() }
    val netherlandsGroups = state.groups.filter { groupName ->
        state.channelsByGroup[groupName].orEmpty().isNotEmpty() && groupName.isPriorityStartupGroup()
    }
    val fallbackGroups = state.groups.filter { state.channelsByGroup[it].orEmpty().isNotEmpty() }
    return (favoritesFirst + sessionGroup + favoriteGroups + netherlandsGroups + fallbackGroups)
        .distinct()
        .take(16)
}

private fun setPreparedContent(state: TvUiState): TvUiState {
    val preparedGroups = buildPreparedGroups(state.snapshot)
    val preparedChannelsByGroup = buildPreparedChannelsByGroup(
        snapshot = state.snapshot,
        query = state.query,
        groups = preparedGroups
    )
    return state.copy(
        channelLookup = state.snapshot.channels.associateBy { it.id },
        groups = preparedGroups,
        channelsByGroup = preparedChannelsByGroup
    )
}

private fun buildPreparedGroups(snapshot: IptvSnapshot): List<String> {
    val dynamicGroups = snapshot.grouped.keys.toList()
    val hiddenSet = snapshot.hiddenGroups.toHashSet()
    val visibleGroups = dynamicGroups.filterNot { hiddenSet.contains(it) }
    val favorites = snapshot.favoriteGroups.filter { visibleGroups.contains(it) }
    val others = visibleGroups.filterNot { snapshot.favoriteGroups.contains(it) }
    val baseOrdered = if (snapshot.groupOrder.isNotEmpty()) {
        val orderMap = snapshot.groupOrder.withIndex().associate { (i, groupName) -> groupName to i }
        (favorites + others).sortedBy { orderMap[it] ?: Int.MAX_VALUE }
    } else {
        favorites + others
    }
    val hasFavoriteChannelsInSnapshot = snapshot.favoriteChannels
        .toHashSet()
        .let { ids -> snapshot.channels.any { ids.contains(it.id) } }
    return if (hasFavoriteChannelsInSnapshot) {
        listOf(FAVORITES_GROUP_NAME) + baseOrdered
    } else {
        baseOrdered
    }
}

private fun buildPreparedChannelsByGroup(
    snapshot: IptvSnapshot,
    query: String,
    groups: List<String>
): Map<String, List<IptvChannel>> {
    if (groups.isEmpty()) return emptyMap()
    val trimmedQuery = query.trim().lowercase()
    val favoriteChannelIds = snapshot.favoriteChannels.toHashSet()
    return buildMap(groups.size) {
        groups.forEach { group ->
            val source = if (group == FAVORITES_GROUP_NAME) {
                if (favoriteChannelIds.isEmpty()) {
                    emptyList()
                } else {
                    val favoriteOrder = snapshot.favoriteChannels
                        .withIndex()
                        .associate { (index, id) -> id to index }
                    snapshot.channels
                        .filter { favoriteChannelIds.contains(it.id) }
                        .sortedBy { favoriteOrder[it.id] ?: Int.MAX_VALUE }
                }
            } else {
                snapshot.grouped[group].orEmpty()
            }
            put(group, filterTvChannels(source, trimmedQuery))
        }
    }
}

private fun String.isNetherlandsGroup(): Boolean {
    val tokens = lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotBlank() }
        .toSet()
    return "netherlands" in tokens || "nederland" in tokens || "nl" in tokens
}

private fun String.isPriorityStartupGroup(): Boolean {
    return isNetherlandsGroup() || lowercase().contains("4k")
}

private fun filterTvChannels(
    source: List<IptvChannel>,
    trimmedQuery: String
): List<IptvChannel> {
    if (trimmedQuery.isBlank()) return source
    return source.mapNotNull { channel ->
        val name = channel.name.lowercase()
        val groupName = channel.group.lowercase()
        val score = when {
            name.startsWith(trimmedQuery) -> 100
            name.contains(trimmedQuery) -> 80
            groupName.startsWith(trimmedQuery) -> 60
            groupName.contains(trimmedQuery) -> 45
            else -> 0
        }
        if (score > 0) channel to score else null
    }
        .sortedByDescending { it.second }
        .map { it.first }
}

private fun IptvConfig.syncSignature(): String {
    val playlistsSignature = playlists
        .sortedBy { it.id }
        .joinToString("|") { playlist ->
            listOf(
                playlist.id,
                playlist.name,
                playlist.m3uUrl,
                playlist.epgUrl,
                playlist.enabled.toString()
            ).joinToString("~")
        }
    return listOf(
        m3uUrl,
        epgUrl,
        stalkerPortalUrl,
        stalkerMacAddress,
        playlistsSignature
    ).joinToString("||")
}

package com.arflix.tv.ui.screens.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.model.IptvSnapshot
import com.arflix.tv.data.repository.CloudSyncRepository
import com.arflix.tv.data.repository.IptvConfig
import com.arflix.tv.data.repository.IptvRepository
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

private const val FAVORITES_GROUP_NAME = "My Favorites"

data class TvUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val loadingMessage: String? = null,
    val loadingPercent: Int = 0,
    val config: IptvConfig = IptvConfig(),
    val snapshot: IptvSnapshot = IptvSnapshot(),
    val channelLookup: Map<String, IptvChannel> = emptyMap(),
    val favoritesOnly: Boolean = false,
    val query: String = ""
) {
    val isConfigured: Boolean get() = config.m3uUrl.isNotBlank() || config.stalkerPortalUrl.isNotBlank()

    fun filteredChannels(group: String): List<IptvChannel> {
        val source = if (group == FAVORITES_GROUP_NAME) {
            val favorites = snapshot.favoriteChannels.toHashSet()
            if (favorites.isEmpty()) emptyList() else snapshot.channels.filter { favorites.contains(it.id) }
        } else {
            snapshot.grouped[group].orEmpty()
        }

        val trimmed = query.trim().lowercase()
        if (trimmed.isBlank()) return source

        return source.mapNotNull { channel ->
            val name = channel.name.lowercase()
            val groupName = channel.group.lowercase()
            val score = when {
                name.startsWith(trimmed) -> 100
                name.contains(trimmed) -> 80
                groupName.startsWith(trimmed) -> 60
                groupName.contains(trimmed) -> 45
                else -> 0
            }
            if (score > 0) channel to score else null
        }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun groups(): List<String> {
        val dynamicGroups = snapshot.grouped.keys.toList()
        val hiddenSet = snapshot.hiddenGroups.toHashSet()
        val visibleGroups = dynamicGroups.filterNot { hiddenSet.contains(it) }
        val favorites = snapshot.favoriteGroups.filter { visibleGroups.contains(it) }
        val others = visibleGroups.filterNot { snapshot.favoriteGroups.contains(it) }
        val baseOrdered = if (snapshot.groupOrder.isNotEmpty()) {
            val orderMap = snapshot.groupOrder.withIndex().associate { (i, g) -> g to i }
            (favorites + others).sortedBy { orderMap[it] ?: Int.MAX_VALUE }
        } else { favorites + others }
        val hasFavoriteChannelsInSnapshot = snapshot.favoriteChannels
            .toHashSet()
            .let { ids -> snapshot.channels.any { ids.contains(it.id) } }
        return if (hasFavoriteChannelsInSnapshot) {
            listOf(FAVORITES_GROUP_NAME) + baseOrdered
        } else {
            baseOrdered
        }
    }
}

@HiltViewModel
class TvViewModel @Inject constructor(
    val iptvRepository: IptvRepository,
    private val cloudSyncRepository: CloudSyncRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvUiState(isLoading = true))
    val uiState: StateFlow<TvUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var epgRefreshJob: Job? = null
    private var warmVodJob: Job? = null
    private var pendingForcedReload: Boolean = false
    private var periodicEpgJob: Job? = null
    private var lastObservedConfigSignature: String? = null
    private var lastAutomaticEpgReloadAt: Long = 0L

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
        viewModelScope.launch {
            // Try fast non-blocking in-memory read first; fall back to mutex-guarded disk read
            val cached = iptvRepository.getMemoryCachedSnapshot()
                ?: iptvRepository.getCachedSnapshotOrNull()
            if (cached != null) {
                val config = iptvRepository.observeConfig().first()
                val lookup = withContext(Dispatchers.Default) {
                    cached.channels.associateBy { it.id }
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = null,
                    snapshot = cached,
                    channelLookup = lookup,
                    loadingMessage = null,
                    loadingPercent = 0
                )
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
                    refresh(force = true, showLoading = cached.channels.isEmpty(), forceEpg = true)
                } else {
                    // In every other case render the warm cache instantly —
                    // never block the TV page on EPG. Refresh what we have
                    // silently in the background so coverage grows over time
                    // without ever showing a loading screen to the user.
                    val epgStale = !epgIsRecent || epgCoverage < 0.92f
                    if (epgStale || iptvRepository.isSnapshotStale(cached)) {
                        refresh(force = false, showLoading = false, forceEpg = epgStale)
                    } else {
                        System.err.println("[EPG] Startup: using warm cached EPG (age=${epgAgeMs / 1000}s)")
                    }
                }
            } else {
                refresh(force = false, showLoading = true, forceEpg = true)
            }
            startPeriodicEpgRefresh()
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
                _uiState.value = _uiState.value.copy(config = config, snapshot = snapshot)

                val hasAnyIptvConfig = config.m3uUrl.isNotBlank() ||
                    config.stalkerPortalUrl.isNotBlank() ||
                    config.playlists.any { it.enabled && it.m3uUrl.isNotBlank() }

                // Auto-heal cases where the app has IPTV config but an empty in-memory snapshot.
                if (hasAnyIptvConfig && snapshot.channels.isEmpty() && refreshJob?.isActive != true) {
                    refresh(force = true, showLoading = true)
                } else if (configChanged && refreshJob?.isActive != true) {
                    cachedEnrichedChannels = null
                    cachedChannelsSignature = null
                    refresh(force = true, showLoading = false, forceEpg = true)
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
                iptvRepository.loadSnapshot(
                    forcePlaylistReload = force,
                    forceEpgReload = forceEpg,
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
                        val lookup = withContext(Dispatchers.Default) {
                            channels.associateBy { it.id }
                        }
                        val currentSnapshot = _uiState.value.snapshot
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = null,
                            snapshot = currentSnapshot.copy(
                                channels = channels,
                                grouped = channels.groupBy { it.group.ifBlank { "Uncategorized" } },
                                nowNext = if (cachedNowNext.isNotEmpty()) {
                                    currentSnapshot.nowNext.toMutableMap().apply { putAll(cachedNowNext) }
                                } else {
                                    currentSnapshot.nowNext
                                }
                            ),
                            channelLookup = lookup,
                            loadingMessage = null,
                            loadingPercent = 0
                        )
                    }
                )
            }.onSuccess { snapshot ->
                val lookup = withContext(Dispatchers.Default) {
                    snapshot.channels.associateBy { it.id }
                }
                cachedEnrichedChannels = null
                cachedChannelsSignature = null
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = null,
                    snapshot = snapshot,
                    channelLookup = lookup,
                    loadingMessage = null,
                    loadingPercent = 0
                )
                warmXtreamVodCache()
                if (!forceEpg) {
                    maybeRefreshEpgInBackground(snapshot)
                }
                if (!force && _uiState.value.isConfigured && snapshot.channels.isEmpty()) {
                    // Soft refresh returned empty even though IPTV is configured:
                    // schedule one forced reload to bypass stale in-memory paths.
                    pendingForcedReload = true
                }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = error.message ?: "Failed to load IPTV",
                    loadingMessage = null,
                    loadingPercent = 0
                )
            }
        }.also { job ->
            job.invokeOnCompletion {
                refreshJob = null
                if (pendingForcedReload) {
                    pendingForcedReload = false
                    refresh(force = true, showLoading = true, forceEpg = true)
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

    private fun maybeRefreshEpgInBackground(snapshot: IptvSnapshot, forceRefresh: Boolean = false) {
        val config = _uiState.value.config
        val hasPotentialEpg = config.epgUrl.isNotBlank() || config.m3uUrl.contains("get.php", ignoreCase = true) || config.m3uUrl.contains("player_api.php", ignoreCase = true)
        if (!hasPotentialEpg) return
        if (!forceRefresh && (snapshot.channels.isEmpty() || epgCoverageRatio(snapshot) >= 0.92f)) return
        if (epgRefreshJob?.isActive == true) return

        epgRefreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loadingMessage = "Loading EPG in background...",
                loadingPercent = 90
            )
            runCatching {
                iptvRepository.loadSnapshot(
                    forcePlaylistReload = false,
                    forceEpgReload = true,
                    onProgress = { progress ->
                        _uiState.value = _uiState.value.copy(
                            loadingMessage = progress.message,
                            loadingPercent = progress.percent ?: _uiState.value.loadingPercent
                        )
                    }
                )
            }.onSuccess { refreshed ->
                val lookup = withContext(Dispatchers.Default) {
                    refreshed.channels.associateBy { it.id }
                }
                _uiState.value = _uiState.value.copy(
                    error = null,
                    snapshot = refreshed,
                    channelLookup = lookup,
                    loadingMessage = null,
                    loadingPercent = 0
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    loadingMessage = null,
                    loadingPercent = 0
                )
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (epgRefreshJob === job) {
                    epgRefreshJob = null
                }
            }
        }
    }

    private fun hasAnyEpgData(snapshot: IptvSnapshot): Boolean {
        if (snapshot.nowNext.isEmpty()) return false
        return snapshot.nowNext.values.any { item ->
            item.now != null || item.next != null || item.later != null || item.upcoming.isNotEmpty()
        }
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
                    val refreshedState = _uiState.value
                    val epgAgeMs = iptvRepository.cachedEpgAgeMs()
                    val epgCoverage = epgCoverageRatio(refreshedState.snapshot)
                    val now = System.currentTimeMillis()
                    val shouldReloadFromNetwork = !hasAnyEpgData(refreshedState.snapshot) ||
                        epgCoverage < 0.92f ||
                        epgAgeMs >= 5 * 60_000L
                    if (shouldReloadFromNetwork &&
                        refreshJob?.isActive != true &&
                        now - lastAutomaticEpgReloadAt >= 5 * 60_000L
                    ) {
                        lastAutomaticEpgReloadAt = now
                        refresh(force = false, showLoading = false, forceEpg = true)
                    }
                }
            }
        }
    }

    fun setQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun toggleFavoriteGroup(groupName: String) {
        viewModelScope.launch {
            iptvRepository.toggleFavoriteGroup(groupName)
            syncIptvFavoritesToCloud()
        }
    }

    fun toggleFavoriteChannel(channelId: String) {
        viewModelScope.launch {
            iptvRepository.toggleFavoriteChannel(channelId)
            syncIptvFavoritesToCloud()
        }
    }

    fun toggleHiddenGroup(groupName: String) {
        viewModelScope.launch { iptvRepository.toggleHiddenGroup(groupName); syncIptvFavoritesToCloud() }
    }

    fun moveGroupUp(groupName: String) {
        viewModelScope.launch {
            val current = _uiState.value.groups().filterNot { it == FAVORITES_GROUP_NAME }
            iptvRepository.moveGroupUp(groupName, current)
            syncIptvFavoritesToCloud()
        }
    }

    fun moveGroupDown(groupName: String) {
        viewModelScope.launch {
            val current = _uiState.value.groups().filterNot { it == FAVORITES_GROUP_NAME }
            iptvRepository.moveGroupDown(groupName, current)
            syncIptvFavoritesToCloud()
        }
    }

    private suspend fun syncIptvFavoritesToCloud() {
        runCatching { cloudSyncRepository.pushToCloud() }
    }
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

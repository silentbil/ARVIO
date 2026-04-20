@file:Suppress("UnsafeOptInUsageError")

package com.arflix.tv.ui.screens.tv.live

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.model.Profile
import com.arflix.tv.ui.screens.tv.TvUiState
import com.arflix.tv.ui.screens.tv.TvViewModel
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.ui.components.AppTopBar
import com.arflix.tv.ui.components.AppTopBarHeight
import com.arflix.tv.ui.components.SidebarItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Live TV screen — Arvio spec §1. Three focus regions: Sidebar ↔ MiniPlayer ↔ EPG.
 * Preserves every IPTV feature from the legacy [com.arflix.tv.ui.screens.tv.TvScreen]
 * (favorites, hidden groups, EPG refresh, cloud sync) — only the UI shell is new.
 */
@Composable
fun LiveTvScreen(
    viewModel: TvViewModel = hiltViewModel(),
    currentProfile: Profile? = null,
    initialChannelId: String? = null,
    initialStreamUrl: String? = null,
    onNavigateToHome: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToWatchlist: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Enrichment runs on a background dispatcher and is published through state
    // — avoids blocking recomposition for 10k+ playlists. Result is cached in
    // the ViewModel so re-visits to the TV page are instant (no 2-3s stall).
    val enrichedState = remember {
        mutableStateOf<EnrichedChannels>(
            (viewModel.cachedEnrichedChannels as? EnrichedChannels) ?: EnrichedChannels.Empty
        )
    }
    LaunchedEffect(state.snapshot.channels) {
        val snapshot = state.snapshot.channels
        if (snapshot.isEmpty()) return@LaunchedEffect
        // Skip re-enrichment if we already have a cache for the same playlist.
        val signature = "${snapshot.size}:${snapshot.firstOrNull()?.id}:${snapshot.lastOrNull()?.id}"
        if (viewModel.cachedChannelsSignature == signature &&
            viewModel.cachedEnrichedChannels is EnrichedChannels
        ) {
            enrichedState.value = viewModel.cachedEnrichedChannels as EnrichedChannels
            return@LaunchedEffect
        }

        val enriched = withContext(Dispatchers.Default) {
            snapshot.mapIndexed { idx, ch -> ch.enrich(100 + idx) }
        }
        val favSet = state.snapshot.favoriteChannels.toSet()
        val tree = withContext(Dispatchers.Default) {
            buildCategoryTree(
                channels = enriched,
                favoritesCount = enriched.count { it.id in favSet && !it.isAdult },
                recentCount = 0,
            )
        }
        val value = EnrichedChannels(all = enriched, tree = tree)
        enrichedState.value = value
        viewModel.cachedEnrichedChannels = value
        viewModel.cachedChannelsSignature = signature
    }
    // Re-evaluate tree counts when favorites change without re-enriching.
    LaunchedEffect(state.snapshot.favoriteChannels) {
        val current = enrichedState.value
        if (current === EnrichedChannels.Empty) return@LaunchedEffect
        val favSet = state.snapshot.favoriteChannels.toSet()
        val tree = withContext(Dispatchers.Default) {
            buildCategoryTree(
                channels = current.all,
                favoritesCount = current.all.count { it.id in favSet && !it.isAdult },
                recentCount = 0,
            )
        }
        enrichedState.value = current.copy(tree = tree)
    }

    // Selected category (persist across nav). Defaults to "all".
    var selectedCategoryId by rememberSaveable { mutableStateOf("all") }

    // Track recently-tuned channels (session-scope for now; persistence is a
    // follow-up — doing it here would mean touching IptvRepository).
    val recents = remember { mutableStateOf<LinkedHashSet<String>>(LinkedHashSet()) }

    val favSet = remember(state.snapshot.favoriteChannels) { state.snapshot.favoriteChannels.toSet() }

    // Filter runs on Default dispatcher; the result is published through a
    // state so recomposition is never blocked on a 52k-channel scan. Without
    // this, DPAD navigation triggers a recompose that stalls the UI thread
    // long enough to ANR on large playlists.
    val filteredChannelsState = remember { mutableStateOf<List<EnrichedChannel>>(emptyList()) }
    LaunchedEffect(enrichedState.value, selectedCategoryId, favSet, recents.value) {
        val enriched = enrichedState.value.all
        val matcher = categoryMatcher(selectedCategoryId, favSet, recents.value)
        val result = withContext(Dispatchers.Default) { enriched.filter(matcher) }
        filteredChannelsState.value = result
    }
    val filteredChannels = filteredChannelsState.value

    // Playing channel — default to the one we were navigated to, else the first
    // channel of the first non-empty category.
    var playingChannelId by rememberSaveable { mutableStateOf<String?>(initialChannelId) }
    val playingChannel = remember(playingChannelId, enrichedState.value) {
        enrichedState.value.all.firstOrNull { it.id == playingChannelId }
    }

    // Pick a default channel to play when data arrives.
    LaunchedEffect(filteredChannels, playingChannelId) {
        if (playingChannelId == null && filteredChannels.isNotEmpty()) {
            playingChannelId = filteredChannels.first().id
        }
    }

    // Sidebar stays expanded now that the whole TV page is dense enough.
    // Collapse animation removed per user request — `sidebarExpanded` held
    // as a const so existing callers still compile.
    val sidebarExpanded = true
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    // Focus requesters for the three regions.
    val sidebarFocus = remember { FocusRequester() }
    val miniFocus = remember { FocusRequester() }
    val epgFocus = remember { FocusRequester() }

    // ExoPlayer lifecycle — mirrors the legacy screen's setup verbatim so live
    // IPTV behaviour (buffer, retries, chunkless HLS) stays identical.
    val iptvHttpClient = remember {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .dns(OkHttpProvider.dns)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }
    val mediaSourceFactory = remember(iptvHttpClient) {
        DefaultMediaSourceFactory(context)
            .setDataSourceFactory(
                OkHttpDataSource.Factory(iptvHttpClient)
                    .setUserAgent("ARVIO/1.2.0 (Android TV)")
            )
    }
    val exoPlayer = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 120_000, 1_000, 3_000)
            .setTargetBufferBytes(80 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(10_000, true)
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> if (playingChannelId != null) exoPlayer.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // When the selected channel changes, swap media item.
    val currentStreamUrl by rememberUpdatedState(playingChannel?.streamUrl ?: initialStreamUrl)
    LaunchedEffect(currentStreamUrl) {
        val stream = currentStreamUrl ?: return@LaunchedEffect
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.setMediaItem(
            MediaItem.Builder()
                .setUri(stream)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMinPlaybackSpeed(1.0f).setMaxPlaybackSpeed(1.0f)
                        .setTargetOffsetMs(4_000).build()
                )
                .build()
        )
        exoPlayer.prepare()
        exoPlayer.play()
        // Persist "recent" as soon as playback starts.
        playingChannelId?.let { id ->
            val set = LinkedHashSet(recents.value)
            set.remove(id); set.add(id)
            while (set.size > 40) set.remove(set.first())
            recents.value = set
        }
    }

    // Make sure focus lands on the EPG when the screen settles — matches the
    // spec's default-focus diagram ("mini → EPG on DPAD_DOWN").
    LaunchedEffect(enrichedState.value !== EnrichedChannels.Empty) {
        if (enrichedState.value !== EnrichedChannels.Empty) {
            runCatching { epgFocus.requestFocus() }
        }
    }

    BackHandler(enabled = searchOpen) { searchOpen = false }
    BackHandler(enabled = !searchOpen) { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LiveColors.Bg)
    ) {
        // Content area starts below the translucent top bar so it doesn't get
        // overwritten.
        if (state.isLoading && state.snapshot.channels.isEmpty()) {
            LoadingPane(message = state.loadingMessage, percent = state.loadingPercent)
        } else if (!state.isConfigured && state.snapshot.channels.isEmpty()) {
            EmptyStatePane(
                message = "No IPTV playlist configured.",
                actionLabel = "Open settings",
                onAction = onNavigateToSettings,
            )
        } else {
            // Content starts right under the pill row — 52 dp puts the first
            // row/search field 4 dp below the pills. The remaining top-bar
            // gradient tail is transparent enough to vanish over our near-
            // black Bg so the two regions read as one surface.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 52.dp),
            ) {
                CategorySidebar(
                    tree = enrichedState.value.tree,
                    selectedId = selectedCategoryId,
                    expanded = sidebarExpanded,
                    onSelect = { id -> selectedCategoryId = id },
                    onOpenSearch = { searchOpen = true },
                    modifier = Modifier
                        .fillMaxHeight()
                        .focusRequester(sidebarFocus),
                )

                Column(modifier = Modifier.fillMaxSize()) {
                    MiniPlayerRow(
                        exoPlayer = exoPlayer,
                        channel = playingChannel,
                        nowNext = playingChannelId?.let { state.snapshot.nowNext[it] },
                        onFavoriteToggle = { viewModel.toggleFavoriteChannel(it) },
                        favoriteSet = favSet,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(miniFocus),
                    )
                    EpgGrid(
                        channels = filteredChannels,
                        nowNext = state.snapshot.nowNext,
                        selectedChannelId = playingChannelId,
                        onChannelSelect = { channel -> playingChannelId = channel.id },
                        onChannelFavoriteToggle = { id -> viewModel.toggleFavoriteChannel(id) },
                        favorites = favSet,
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(epgFocus),
                    )
                }
            }
        }

        // Translucent top bar — shows profile avatar + nav + clock without
        // stealing focus from the sidebar/EPG. Sits above the content via
        // the Box ordering; content below already has top padding so nothing
        // overlaps.
        AppTopBar(
            selectedItem = SidebarItem.TV,
            isFocused = false,
            focusedIndex = -1,
            profile = currentProfile,
            profileCount = 1,
        )

        AnimatedVisibility(
            visible = searchOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            SearchOverlay(
                channels = enrichedState.value.all,
                onDismiss = { searchOpen = false },
                onPick = { channel ->
                    playingChannelId = channel.id
                    searchOpen = false
                },
            )
        }
    }
}

/** State bundle of the enriched channel list + category tree. */
data class EnrichedChannels(
    val all: List<EnrichedChannel>,
    val tree: LiveCategoryTree,
) {
    companion object {
        val Empty = EnrichedChannels(
            all = emptyList(),
            tree = LiveCategoryTree(
                top = emptyList(),
                global = LiveSection("global", "GLOBAL", emptyList()),
                countries = LiveSection("countries", "COUNTRIES", emptyList()),
                adult = LiveSection("adult", "ADULT", emptyList()),
            ),
        )
    }
}

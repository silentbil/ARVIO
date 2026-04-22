@file:Suppress("UnsafeOptInUsageError")

package com.arflix.tv.ui.screens.tv.live

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    // Lifecycle-aware collection so the screen stops draining state updates
    // the instant the user backs out — matters on a long-running IPTV flow
    // where the ViewModel pushes EPG refreshes every few seconds.
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
    // Only let `recents` invalidate the filter when Recent is the active
    // category; otherwise every channel tune would re-scan a 50k-entry
    // enriched list and stutter DPAD travel.
    val recentsFilterKey = if (selectedCategoryId == "recent") recents.value else Unit
    LaunchedEffect(enrichedState.value, selectedCategoryId, favSet, recentsFilterKey) {
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

    // Pick a default channel to play when data arrives. Prefer a favorite
    // from the current filter so opening the TV page lands on "your"
    // channel; fall back to the first filtered entry.
    LaunchedEffect(filteredChannels, playingChannelId) {
        if (playingChannelId == null && filteredChannels.isNotEmpty()) {
            playingChannelId = filteredChannels.firstOrNull { it.id in favSet }?.id
                ?: filteredChannels.first().id
        }
    }

    val sidebarExpanded = true
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // Full-screen playback mode — pressing OK on an EPG row expands the
    // mini-player to cover the whole screen. Back collapses back to the grid.
    var isFullScreen by rememberSaveable { mutableStateOf(initialStreamUrl != null) }

    // Focus requesters for the three regions.
    val sidebarFocus = remember { FocusRequester() }
    val miniFocus = remember { FocusRequester() }
    val epgFocus = remember { FocusRequester() }
    val fsFocus = remember { FocusRequester() }

    // Monotonic counter bumped on every DPAD key while in fullscreen —
    // the HUD observes this to re-show and reset its auto-hide timer.
    var hudPokeSignal by remember { mutableStateOf(0) }

    // Prev/next zapping across the full enriched list (not the filtered
    // category) per user spec. Wraps around.
    fun zap(delta: Int) {
        val all = enrichedState.value.all
        if (all.isEmpty()) return
        val currentIdx = all.indexOfFirst { it.id == playingChannelId }
        val start = if (currentIdx >= 0) currentIdx else 0
        val size = all.size
        val nextIdx = ((start + delta) % size + size) % size
        playingChannelId = all[nextIdx].id
    }

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
    BackHandler(enabled = !searchOpen && isFullScreen) { isFullScreen = false }
    BackHandler(enabled = !searchOpen && !isFullScreen) { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LiveColors.Bg)
    ) {
        // Content area starts below the translucent top bar so it doesn't get
        // overwritten.
        // Keep the loading pane up until enrichment has actually produced
        // channels — avoids the "empty TV page for 10s" gap the user saw.
        val isEnriching = enrichedState.value === EnrichedChannels.Empty &&
            state.snapshot.channels.isNotEmpty()
        if (isFullScreen) {
            // Full-screen playback only — no grid rendered so the single
            // PlayerView owns ExoPlayer.
        } else if (
            state.isLoading && state.snapshot.channels.isEmpty() ||
            isEnriching
        ) {
            LoadingPane(
                message = state.loadingMessage ?: "Preparing channels…",
                percent = state.loadingPercent,
            )
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
                        onChannelSelect = { channel ->
                            // Two-step activation:
                            //  1st tap on a channel → tune it in the mini-
                            //      player so the user can preview without
                            //      committing to fullscreen.
                            //  2nd tap on the same (already-playing) channel
                            //      → enlarge to fullscreen.
                            // Picking a different channel while already full-
                            // screen swaps the stream but keeps fullscreen.
                            if (channel.id == playingChannelId && !isFullScreen) {
                                isFullScreen = true
                            } else {
                                playingChannelId = channel.id
                            }
                        },
                        onChannelFavoriteToggle = { id -> viewModel.toggleFavoriteChannel(id) },
                        favorites = favSet,
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(epgFocus),
                    )
                }
            }
        }

        // Full-screen playback: same ExoPlayer, covers the entire screen.
        //
        // The overlay animates a scale+alpha transition so it looks like the
        // mini-player is growing into fullscreen. The transform pivot is
        // roughly the mini-player's center (sidebar ≈ 20% of width, mini-
        // player sits just below the 52dp top bar), which keeps the grow
        // anchored visually to where the user tapped instead of from screen
        // center. fsProgress stays mounted until it reaches 0, so the
        // reverse animation also plays on Back.
        val fsProgress by animateFloatAsState(
            targetValue = if (isFullScreen) 1f else 0f,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            label = "tv-fullscreen-progress",
        )
        if (fsProgress > 0f && playingChannel != null) {
            val scale = 0.35f + 0.65f * fsProgress
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(
                            pivotFractionX = 0.22f,
                            pivotFractionY = 0.18f,
                        )
                        scaleX = scale
                        scaleY = scale
                        alpha = fsProgress
                    }
                    .background(Color.Black)
                    .focusRequester(fsFocus)
                    .focusable()
                    .onPreviewKeyEvent { ev ->
                        if (!isFullScreen || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.DirectionUp -> { zap(-1); hudPokeSignal++; true }
                            Key.DirectionDown -> { zap(+1); hudPokeSignal++; true }
                            Key.DirectionCenter, Key.Enter -> { hudPokeSignal++; true }
                            Key.DirectionLeft, Key.DirectionRight -> { hudPokeSignal++; false }
                            else -> false
                        }
                    },
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        androidx.media3.ui.PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                        }
                    },
                    update = { it.player = exoPlayer },
                    modifier = Modifier.fillMaxSize(),
                )
                if (isFullScreen) {
                    FullscreenHud(
                        channel = playingChannel,
                        nowNext = playingChannelId?.let { state.snapshot.nowNext[it] },
                        pokeSignal = hudPokeSignal,
                    )
                }
            }
        }

        LaunchedEffect(isFullScreen) {
            if (isFullScreen) {
                runCatching { fsFocus.requestFocus() }
            }
        }

        // Top bar only shows when NOT in full-screen playback.
        // Fade with the fullscreen progress so it doesn't pop in/out — looks
        // natural next to the grow animation below.
        if (fsProgress < 1f) {
            Box(modifier = Modifier.graphicsLayer { alpha = 1f - fsProgress }) {
                AppTopBar(
                    selectedItem = SidebarItem.TV,
                    isFocused = false,
                    focusedIndex = -1,
                    profile = currentProfile,
                    profileCount = 1,
                )
            }
        }

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

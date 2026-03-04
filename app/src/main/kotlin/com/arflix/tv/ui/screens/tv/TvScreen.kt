
@file:Suppress("UnsafeOptInUsageError")

package com.arflix.tv.ui.screens.tv

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.model.IptvNowNext
import com.arflix.tv.data.model.IptvProgram
import com.arflix.tv.ui.components.Sidebar
import com.arflix.tv.ui.components.SidebarItem
import com.arflix.tv.ui.components.TopBarClock
import com.arflix.tv.ui.theme.AccentGreen
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.BackgroundCard
import com.arflix.tv.ui.theme.BackgroundDark
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private enum class TvFocusZone {
    SIDEBAR,
    GROUPS,
    GUIDE
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvScreen(
    viewModel: TvViewModel = hiltViewModel(),
    currentProfile: com.arflix.tv.data.model.Profile? = null,
    initialChannelId: String? = null,
    initialStreamUrl: String? = null,
    onNavigateToHome: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToWatchlist: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var focusZone by rememberSaveable { mutableStateOf(if (uiState.isConfigured) TvFocusZone.GROUPS else TvFocusZone.SIDEBAR) }
    val hasProfile = currentProfile != null
    val maxSidebarIndex = if (hasProfile) SidebarItem.entries.size else SidebarItem.entries.size - 1
    var sidebarFocusIndex by rememberSaveable { mutableIntStateOf(if (hasProfile) 4 else 3) }
    var groupIndex by rememberSaveable { mutableIntStateOf(0) }
    var channelIndex by rememberSaveable { mutableIntStateOf(0) }
    var selectedChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var playingChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    // When launched from Home with a stream URL, start in fullscreen immediately
    // to avoid a flash of the TV page channel list.
    var isFullScreen by rememberSaveable { mutableStateOf(initialStreamUrl != null) }
    var showFullscreenOverlay by remember { mutableStateOf(false) }
    var fullscreenOverlayTrigger by remember { mutableStateOf(0L) } // timestamp to reset auto-hide timer
    var centerDownAtMs by remember { mutableStateOf<Long?>(null) }

    val groupsListState = rememberLazyListState()
    val channelsListState = rememberLazyListState()

    val groups by remember(uiState.snapshot.grouped, uiState.snapshot.favoriteGroups, uiState.snapshot.favoriteChannels) {
        derivedStateOf { uiState.groups() }
    }
    val safeGroupIndex = groupIndex.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
    val selectedGroup = groups.getOrNull(safeGroupIndex).orEmpty()
    val channels = uiState.filteredChannels(selectedGroup)
    val safeChannelIndex = channelIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
    val selectedChannel = selectedChannelId?.let { uiState.channelLookup[it] }
    val playingChannel = selectedChannel ?: playingChannelId?.let { uiState.channelLookup[it] }

    // Auto-select channel when navigated from Home "Favorite TV" row.
    // If initialStreamUrl was provided, playback already started instantly —
    // this just updates selectedChannelId once the lookup is ready.
    LaunchedEffect(initialChannelId, uiState.snapshot.channels.size) {
        if (initialChannelId != null && uiState.snapshot.channels.isNotEmpty()) {
            val channel = uiState.channelLookup[initialChannelId]
            if (channel != null) {
                selectedChannelId = channel.id
                // Only set playingChannelId if not already playing (instant start already did it)
                if (playingChannelId != channel.id) {
                    playingChannelId = channel.id
                }
                isFullScreen = true
            }
        }
    }

    LaunchedEffect(groups.size) {
        if (groupIndex >= groups.size) groupIndex = 0
    }
    LaunchedEffect(uiState.isConfigured) {
        if (uiState.isConfigured && focusZone == TvFocusZone.SIDEBAR && groups.isNotEmpty()) {
            focusZone = TvFocusZone.GROUPS
        }
    }
    LaunchedEffect(channels.size) {
        if (channelIndex >= channels.size) channelIndex = 0
        if (selectedChannelId != null && uiState.snapshot.channels.none { it.id == selectedChannelId }) {
            selectedChannelId = null
        }
    }
    LaunchedEffect(safeGroupIndex, focusZone, groups.size) {
        if (focusZone == TvFocusZone.GROUPS && groups.isNotEmpty()) {
            smoothScrollTo(groupsListState, safeGroupIndex)
        }
    }
    LaunchedEffect(safeChannelIndex, focusZone, channels.size) {
        if (focusZone == TvFocusZone.GUIDE && channels.isNotEmpty()) {
            smoothScrollTo(channelsListState, safeChannelIndex)
        }
    }
    // Auto-play focused channel in mini player (with debounce to avoid spam during fast scrolling)
    LaunchedEffect(safeChannelIndex, focusZone) {
        if (focusZone == TvFocusZone.GUIDE && channels.isNotEmpty()) {
            kotlinx.coroutines.delay(300L) // debounce: wait 300ms after focus settles
            val focusedChannel = channels.getOrNull(safeChannelIndex)
            if (focusedChannel != null && playingChannelId != focusedChannel.id) {
                selectedChannelId = focusedChannel.id
                playingChannelId = focusedChannel.id
            }
        }
    }
    LaunchedEffect(uiState.isConfigured, uiState.isLoading, uiState.snapshot.channels.size, groups.size) {
        if (uiState.isConfigured && !uiState.isLoading && uiState.snapshot.channels.isEmpty()) {
            viewModel.refresh(force = true, showLoading = true)
        }
    }
    LaunchedEffect(groups, selectedGroup, channels.size) {
        if (selectedGroup == "My Favorites" && channels.isEmpty() && groups.size > 1 && groupIndex == 0) {
            groupIndex = 1
        }
    }

    // OkHttp with connection pooling for faster channel switching
    val iptvHttpClient = remember {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    val iptvDataSourceFactory = remember(iptvHttpClient) {
        OkHttpDataSource.Factory(iptvHttpClient)
            .setUserAgent("ARVIO/1.2.0 (Android TV)")
    }
    // HLS factory with chunkless preparation (used when stream is detected as HLS)
    val iptvHlsFactory = remember(iptvDataSourceFactory) {
        HlsMediaSource.Factory(iptvDataSourceFactory)
            .setAllowChunklessPreparation(true)
    }
    // Default factory handles all formats (MPEG-TS, HLS, DASH, progressive, etc.)
    val iptvDefaultFactory = remember(iptvDataSourceFactory) {
        DefaultMediaSourceFactory(context)
            .setDataSourceFactory(iptvDataSourceFactory)
    }

    // Track whether ExoPlayer has been released to guard against post-dispose calls
    var isPlayerReleased by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(12_000, 60_000, 2_000, 4_000)
            .setTargetBufferBytes(50 * 1024 * 1024) // 50 MB cap for IPTV (live streams need less buffer)
            .setPrioritizeTimeOverSizeThresholds(false) // respect byte limit over time limit
            .setBackBuffer(8_000, true)
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(iptvDefaultFactory)
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }
    }

    var miniPlayerView by remember { mutableStateOf<PlayerView?>(null) }
    var fullPlayerView by remember { mutableStateOf<PlayerView?>(null) }

    // Keep an always-current reference to the playing channel's stream URL
    // so the error listener never captures a stale closure.
    val currentStreamUrl by rememberUpdatedState(playingChannel?.streamUrl)

    DisposableEffect(Unit) {
        onDispose {
            isPlayerReleased = true
            exoPlayer.release()
        }
    }

    // Helper: prepare ExoPlayer with a stream URL (shared by normal play + error retry)
    fun prepareStream(stream: String) {
        if (isPlayerReleased) return
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        val mediaItem = MediaItem.Builder()
            .setUri(stream)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMinPlaybackSpeed(1.0f)
                    .setMaxPlaybackSpeed(1.0f)
                    .setTargetOffsetMs(4_000)
                    .build()
            )
            .build()
        val streamLower = stream.lowercase()
        if (streamLower.contains(".m3u8") || streamLower.contains("/hls") || streamLower.contains("format=hls")) {
            exoPlayer.setMediaSource(iptvHlsFactory.createMediaSource(mediaItem))
        } else {
            exoPlayer.setMediaItem(mediaItem)
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // Track the last stream URL prepared to avoid redundant prepareStream calls
    var lastPreparedStreamUrl by remember { mutableStateOf<String?>(null) }

    // Instant playback: if we have a stream URL from Home, start playing immediately
    // before the full channel list is loaded.
    LaunchedEffect(Unit) {
        if (initialStreamUrl != null && initialChannelId != null) {
            playingChannelId = initialChannelId
            isFullScreen = true
            lastPreparedStreamUrl = initialStreamUrl
            prepareStream(initialStreamUrl)
        }
    }

    LaunchedEffect(playingChannelId, playingChannel?.streamUrl) {
        val stream = playingChannel?.streamUrl ?: return@LaunchedEffect
        if (isPlayerReleased) return@LaunchedEffect
        // Skip if this exact stream was already prepared (e.g., by instant playback above)
        if (stream == lastPreparedStreamUrl) return@LaunchedEffect
        lastPreparedStreamUrl = stream
        prepareStream(stream)
    }

    LaunchedEffect(isFullScreen, miniPlayerView, fullPlayerView) {
        if (isPlayerReleased) return@LaunchedEffect
        if (isFullScreen) {
            miniPlayerView?.player = null
            fullPlayerView?.post {
                if (!isPlayerReleased) {
                    fullPlayerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    fullPlayerView?.player = exoPlayer
                }
            }
        } else {
            fullPlayerView?.player = null
            miniPlayerView?.post {
                if (!isPlayerReleased) {
                    miniPlayerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    miniPlayerView?.player = exoPlayer
                }
            }
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (isPlayerReleased) return
                // Use the always-current stream URL (not a stale captured value)
                val stream = currentStreamUrl ?: return
                exoPlayer.clearMediaItems()
                val mediaItem = MediaItem.Builder()
                    .setUri(stream)
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setMinPlaybackSpeed(1.0f)
                            .setMaxPlaybackSpeed(1.0f)
                            .setTargetOffsetMs(4_000)
                            .build()
                    )
                    .build()
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (isPlayerReleased) return
                // Force PlayerView to re-apply resize mode once real video
                // dimensions are known, preventing the initial stretched frame.
                miniPlayerView?.let { pv ->
                    pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                fullPlayerView?.let { pv ->
                    pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (isFullScreen) {
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.Back, Key.Escape -> {
                                if (initialChannelId != null) {
                                    // Launched from Home — back goes directly to previous screen
                                    onBack()
                                } else {
                                    isFullScreen = false
                                    showFullscreenOverlay = false
                                }
                                return@onPreviewKeyEvent true
                            }
                            Key.Enter, Key.DirectionCenter -> {
                                // Toggle EPG info overlay
                                showFullscreenOverlay = !showFullscreenOverlay
                                if (showFullscreenOverlay) {
                                    fullscreenOverlayTrigger = System.currentTimeMillis()
                                }
                                return@onPreviewKeyEvent true
                            }
                            Key.DirectionUp -> {
                                // Switch to next channel (up = next in list)
                                if (channels.isNotEmpty()) {
                                    val currentIdx = channels.indexOfFirst { it.id == playingChannelId }
                                    val nextIdx = if (currentIdx < 0) 0 else (currentIdx + 1) % channels.size
                                    val nextChannel = channels[nextIdx]
                                    channelIndex = nextIdx
                                    selectedChannelId = nextChannel.id
                                    playingChannelId = nextChannel.id
                                    // Show overlay briefly on channel switch
                                    showFullscreenOverlay = true
                                    fullscreenOverlayTrigger = System.currentTimeMillis()
                                }
                                return@onPreviewKeyEvent true
                            }
                            Key.DirectionDown -> {
                                // Switch to previous channel (down = previous in list)
                                if (channels.isNotEmpty()) {
                                    val currentIdx = channels.indexOfFirst { it.id == playingChannelId }
                                    val prevIdx = if (currentIdx <= 0) channels.lastIndex else currentIdx - 1
                                    val prevChannel = channels[prevIdx]
                                    channelIndex = prevIdx
                                    selectedChannelId = prevChannel.id
                                    playingChannelId = prevChannel.id
                                    // Show overlay briefly on channel switch
                                    showFullscreenOverlay = true
                                    fullscreenOverlayTrigger = System.currentTimeMillis()
                                }
                                return@onPreviewKeyEvent true
                            }
                            else -> return@onPreviewKeyEvent false
                        }
                    }
                    return@onPreviewKeyEvent false
                }

                val isSelect = event.key == Key.Enter || event.key == Key.DirectionCenter
                if (event.type == KeyEventType.KeyDown && isSelect) {
                    if (centerDownAtMs == null) centerDownAtMs = SystemClock.elapsedRealtime()
                    return@onPreviewKeyEvent true
                }
                if (event.type == KeyEventType.KeyUp && isSelect) {
                    val pressMs = centerDownAtMs?.let { SystemClock.elapsedRealtime() - it } ?: 0L
                    centerDownAtMs = null
                    if (pressMs >= 550L) {
                        when (focusZone) {
                            TvFocusZone.GROUPS -> groups.getOrNull(safeGroupIndex)?.let {
                                viewModel.toggleFavoriteGroup(it)
                                return@onPreviewKeyEvent true
                            }

                            TvFocusZone.GUIDE -> channels.getOrNull(safeChannelIndex)?.let {
                                viewModel.toggleFavoriteChannel(it.id)
                                return@onPreviewKeyEvent true
                            }

                            TvFocusZone.SIDEBAR -> Unit
                        }
                    }

                    when (focusZone) {
                        TvFocusZone.SIDEBAR -> {
                            if (hasProfile && sidebarFocusIndex == 0) {
                                onSwitchProfile()
                            } else {
                                val itemIndex = if (hasProfile) sidebarFocusIndex - 1 else sidebarFocusIndex
                                when (SidebarItem.entries[itemIndex]) {
                                    SidebarItem.SEARCH -> onNavigateToSearch()
                                    SidebarItem.HOME -> onNavigateToHome()
                                    SidebarItem.WATCHLIST -> onNavigateToWatchlist()
                                    SidebarItem.TV -> Unit
                                    SidebarItem.SETTINGS -> onNavigateToSettings()
                                }
                            }
                            true
                        }

                        TvFocusZone.GROUPS -> {
                            channelIndex = 0
                            focusZone = TvFocusZone.GUIDE
                            true
                        }

                        TvFocusZone.GUIDE -> {
                            channels.getOrNull(safeChannelIndex)?.let { channel ->
                                // Focus already starts playback in mini player,
                                // so OK always goes fullscreen
                                selectedChannelId = channel.id
                                playingChannelId = channel.id
                                isFullScreen = true
                            }
                            true
                        }
                    }
                } else if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Back, Key.Escape -> {
                            when (focusZone) {
                                TvFocusZone.SIDEBAR -> onBack()
                                TvFocusZone.GROUPS -> focusZone = TvFocusZone.SIDEBAR
                                TvFocusZone.GUIDE -> focusZone = TvFocusZone.GROUPS
                            }
                            true
                        }

                        Key.DirectionLeft -> {
                            when (focusZone) {
                                TvFocusZone.SIDEBAR -> Unit
                                TvFocusZone.GROUPS -> focusZone = TvFocusZone.SIDEBAR
                                TvFocusZone.GUIDE -> focusZone = TvFocusZone.GROUPS
                            }
                            true
                        }

                        Key.DirectionRight -> {
                            when (focusZone) {
                                TvFocusZone.SIDEBAR -> if (groups.isNotEmpty()) focusZone = TvFocusZone.GROUPS
                                TvFocusZone.GROUPS -> if (channels.isNotEmpty()) focusZone = TvFocusZone.GUIDE
                                TvFocusZone.GUIDE -> Unit
                            }
                            true
                        }

                        Key.DirectionUp -> {
                            when (focusZone) {
                                TvFocusZone.SIDEBAR -> if (sidebarFocusIndex > 0) {
                                    sidebarFocusIndex = (sidebarFocusIndex - 1).coerceIn(0, maxSidebarIndex)
                                }

                                TvFocusZone.GROUPS -> if (groupIndex > 0) groupIndex-- else focusZone = TvFocusZone.SIDEBAR
                                TvFocusZone.GUIDE -> if (channelIndex > 0) channelIndex--
                            }
                            true
                        }

                        Key.DirectionDown -> {
                            when (focusZone) {
                                TvFocusZone.SIDEBAR -> if (sidebarFocusIndex < maxSidebarIndex) {
                                    sidebarFocusIndex = (sidebarFocusIndex + 1).coerceIn(0, maxSidebarIndex)
                                }

                                TvFocusZone.GROUPS -> if (groupIndex < groups.size - 1) groupIndex++
                                TvFocusZone.GUIDE -> if (channelIndex < channels.size - 1) channelIndex++
                            }
                            true
                        }

                        Key.Menu, Key.Bookmark -> {
                            when (focusZone) {
                                TvFocusZone.GROUPS -> groups.getOrNull(safeGroupIndex)?.let {
                                    viewModel.toggleFavoriteGroup(it)
                                    true
                                } ?: false

                                TvFocusZone.GUIDE -> channels.getOrNull(safeChannelIndex)?.let {
                                    viewModel.toggleFavoriteChannel(it.id)
                                    true
                                } ?: false

                                TvFocusZone.SIDEBAR -> false
                            }
                        }

                        Key.Enter, Key.DirectionCenter -> true
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Sidebar(
                selectedItem = SidebarItem.TV,
                isSidebarFocused = focusZone == TvFocusZone.SIDEBAR,
                focusedIndex = sidebarFocusIndex,
                profile = currentProfile,
                onProfileClick = onSwitchProfile
            )

            if (!uiState.isConfigured) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 24.dp, vertical = 24.dp)
                ) {
                    NotConfiguredPanel()
                }
            } else {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 16.dp, top = 18.dp, end = 20.dp, bottom = 16.dp)
                ) {
                    CategoryRail(
                        groups = groups,
                        favoriteGroups = uiState.snapshot.favoriteGroups.toSet(),
                        focusedGroupIndex = safeGroupIndex,
                        isFocused = focusZone == TvFocusZone.GROUPS,
                        listState = groupsListState,
                        modifier = Modifier
                            .width(180.dp)
                            .fillMaxHeight()
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        if (uiState.isLoading || !uiState.loadingMessage.isNullOrBlank()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                val msg = uiState.loadingMessage?.takeIf { it.isNotBlank() } ?: "Refreshing Live TV..."
                                Text(
                                    text = "$msg ${uiState.loadingPercent}%",
                                    style = ArflixTypography.caption,
                                    color = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.12f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                        HeroPreviewPanel(
                            channel = playingChannel,
                            nowProgram = playingChannel?.id?.let { uiState.snapshot.nowNext[it]?.now },
                            nextProgram = playingChannel?.id?.let { uiState.snapshot.nowNext[it]?.next },
                            miniPlayer = {
                                if (playingChannel != null && !isFullScreen) {
                                    AndroidView(
                                        factory = { ctx ->
                                            PlayerView(ctx).apply {
                                                miniPlayerView = this
                                                player = null
                                                useController = false
                                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                                setKeepContentOnPlayerReset(true)
                                                // Black shutter hides the initial stretched frame until
                                                // ExoPlayer resolves the video dimensions.
                                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(12.dp)),
                                        update = { playerView ->
                                            miniPlayerView = playerView
                                            if (!isFullScreen) {
                                                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                                if (playerView.player !== exoPlayer) playerView.player = exoPlayer
                                            }
                                        }
                                    )
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        GuidePanel(
                            channels = channels,
                            nowNext = uiState.snapshot.nowNext,
                            isLoading = uiState.isLoading,
                            focusedChannelIndex = safeChannelIndex,
                            guideFocused = focusZone == TvFocusZone.GUIDE,
                            playingChannelId = playingChannelId,
                            favoriteChannels = uiState.snapshot.favoriteChannels.toSet(),
                            listState = channelsListState,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        TopBarClock(modifier = Modifier.align(Alignment.TopEnd))

        if (isFullScreen) {
            // Show black screen immediately when fullscreen is active.
            // Player and EPG overlay only render once playingChannel resolves.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                if (playingChannel != null) {
                    val fsNowNext = uiState.snapshot.nowNext[playingChannel.id]
                    val fsNow = fsNowNext?.now
                    val fsNext = fsNowNext?.next

                    // Auto-hide overlay after 5 seconds
                    LaunchedEffect(fullscreenOverlayTrigger, showFullscreenOverlay) {
                        if (showFullscreenOverlay && fullscreenOverlayTrigger > 0L) {
                            kotlinx.coroutines.delay(5000L)
                            showFullscreenOverlay = false
                        }
                    }

                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                fullPlayerView = this
                                player = null
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setKeepContentOnPlayerReset(true)
                                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { playerView ->
                            fullPlayerView = playerView
                            if (isFullScreen) {
                                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                if (playerView.player !== exoPlayer) playerView.player = exoPlayer
                            }
                        }
                    )

                    // Premium EPG overlay (toggle with OK, auto-hides after 5s)
                    AnimatedVisibility(
                        visible = showFullscreenOverlay,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        FullscreenEpgOverlay(
                            channel = playingChannel,
                            nowProgram = fsNow,
                            nextProgram = fsNext
                        )
                    }
                }
            }
        }

        uiState.error?.let { err ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .background(Color(0xFF4A1D1D), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFFB91C1C), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(text = err, style = ArflixTypography.caption, color = Color(0xFFFECACA))
            }
        }
    }
}

private suspend fun smoothScrollTo(state: LazyListState, targetIndex: Int) {
    val safe = targetIndex.coerceAtLeast(0)
    val firstVisible = state.firstVisibleItemIndex
    val lastVisible = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisible
    val outsideViewport = safe < firstVisible || safe > lastVisible
    val distance = abs(firstVisible - safe)
    if (safe == 0 || outsideViewport || distance > 12) {
        state.scrollToItem(safe)
    } else {
        state.animateScrollToItem(safe)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryRail(
    groups: List<String>,
    favoriteGroups: Set<String>,
    focusedGroupIndex: Int,
    isFocused: Boolean,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        Text(
            text = "Categories",
            style = ArflixTypography.caption.copy(fontSize = 12.sp, letterSpacing = 0.8.sp),
            color = TextSecondary.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp, top = 2.dp)
        )
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(groups, key = { _, group -> group }, contentType = { _, _ -> "category_group" }) { index, group ->
                GroupRailItem(
                    name = group,
                    isFocused = isFocused && index == focusedGroupIndex,
                    isFavorite = favoriteGroups.contains(group)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GroupRailItem(name: String, isFocused: Boolean, isFavorite: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) Color.White.copy(alpha = 0.12f) else Color.Transparent
            )
            .then(
                if (isFocused) Modifier.border(
                    width = 1.5.dp,
                    color = Color.White.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(8.dp)
                ) else Modifier
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarOutline,
            contentDescription = null,
            tint = if (isFavorite) Color(0xFFF5C518) else TextSecondary.copy(alpha = 0.4f),
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = name,
            style = ArflixTypography.caption.copy(
                fontSize = 13.sp,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
                lineHeight = 16.sp
            ),
            color = if (isFocused) Color.White else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FullscreenEpgOverlay(
    channel: IptvChannel,
    nowProgram: IptvProgram?,
    nextProgram: IptvProgram?
) {
    val topScrimBrush = remember {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Black.copy(alpha = 0.8f),
                0.7f to Color.Black.copy(alpha = 0.4f),
                1.0f to Color.Transparent
            )
        )
    }
    val bottomScrimBrush = remember {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.3f to Color.Black.copy(alpha = 0.4f),
                1.0f to Color.Black.copy(alpha = 0.85f)
            )
        )
    }
    Box(modifier = Modifier.fillMaxSize()) {
        // Top scrim: channel info
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(topScrimBrush)
                .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 40.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                if (!channel.logo.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logo,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                }
                Column {
                    Text(
                        text = channel.name,
                        style = ArflixTypography.sectionTitle.copy(fontSize = 22.sp),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = channel.group,
                        style = ArflixTypography.caption,
                        color = Color.White.copy(alpha = 0.5f),
                        maxLines = 1
                    )
                }
            }
            // Clock on the right
            Text(
                text = programTimeFormatter.format(java.time.LocalTime.now()),
                style = ArflixTypography.sectionTitle.copy(fontSize = 18.sp),
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }

        // Bottom scrim: NOW / NEXT program info
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(bottomScrimBrush)
                .padding(start = 32.dp, end = 32.dp, top = 40.dp, bottom = 28.dp)
        ) {
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                // NOW program
                if (nowProgram != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "NOW",
                            style = ArflixTypography.caption.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                            color = Color.Black,
                            modifier = Modifier
                                .background(AccentGreen, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "${formatProgramTime(nowProgram.startUtcMillis)} - ${formatProgramTime(nowProgram.endUtcMillis)}",
                            style = ArflixTypography.caption.copy(fontSize = 14.sp),
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = nowProgram.title,
                        style = ArflixTypography.sectionTitle.copy(fontSize = 20.sp),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Progress bar
                    val progDuration = (nowProgram.endUtcMillis - nowProgram.startUtcMillis).coerceAtLeast(1L)
                    val progElapsed = (System.currentTimeMillis() - nowProgram.startUtcMillis).coerceIn(0, progDuration)
                    val progFraction = (progElapsed.toFloat() / progDuration.toFloat()).coerceIn(0f, 1f)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.15f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progFraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(AccentGreen)
                        )
                    }
                    nowProgram.description?.let { desc ->
                        if (desc.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = desc,
                                style = ArflixTypography.caption.copy(fontSize = 13.sp),
                                color = Color.White.copy(alpha = 0.55f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(0.6f)
                            )
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "LIVE",
                            style = ArflixTypography.caption.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                            color = Color.Black,
                            modifier = Modifier
                                .background(AccentGreen, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = channel.name,
                            style = ArflixTypography.sectionTitle.copy(fontSize = 20.sp),
                            color = Color.White
                        )
                    }
                }
                // NEXT program
                if (nextProgram != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "NEXT",
                            style = ArflixTypography.caption.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                            color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = formatProgramTime(nextProgram.startUtcMillis),
                            style = ArflixTypography.caption.copy(fontSize = 14.sp),
                            color = Color.White.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = nextProgram.title,
                            style = ArflixTypography.body.copy(fontSize = 16.sp),
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroPreviewPanel(
    channel: IptvChannel?,
    nowProgram: IptvProgram?,
    nextProgram: IptvProgram? = null,
    miniPlayer: @Composable () -> Unit
) {
    if (channel == null) {
        // No channel selected – show placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(BackgroundCard)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.LiveTv,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Select a channel to start preview", style = ArflixTypography.body, color = TextSecondary)
                Spacer(modifier = Modifier.height(2.dp))
                Text("OK: play  |  OK again: fullscreen", style = ArflixTypography.caption, color = TextSecondary.copy(alpha = 0.8f))
            }
        }
        return
    }

    // Channel selected – side-by-side: info left, video right
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.03f))
    ) {
        // Left: channel & EPG info (aligned with guide rows: 8dp outer + 10dp inner = 18dp start)
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            // Channel logo + name (logo 40dp + 8dp gap = matches guide row alignment)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!channel.logo.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logo,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.3f))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = channel.name,
                    style = ArflixTypography.sectionTitle,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            // Now playing
            if (nowProgram != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "NOW",
                        style = ArflixTypography.caption,
                        color = AccentGreen,
                        modifier = Modifier
                            .background(AccentGreen.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${formatProgramTime(nowProgram.startUtcMillis)} - ${formatProgramTime(nowProgram.endUtcMillis)}",
                        style = ArflixTypography.caption,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = nowProgram.title,
                    style = ArflixTypography.body,
                    color = Color.White.copy(alpha = 0.95f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = "Live",
                    style = ArflixTypography.body,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
            // Up next
            if (nextProgram != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "NEXT",
                        style = ArflixTypography.caption,
                        color = TextSecondary,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formatProgramTime(nextProgram.startUtcMillis),
                        style = ArflixTypography.caption,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = nextProgram.title,
                        style = ArflixTypography.caption,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Right: video player (no overlap with info)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                .background(Color.Black)
        ) {
            miniPlayer()
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuidePanel(
    channels: List<IptvChannel>,
    nowNext: Map<String, IptvNowNext>,
    isLoading: Boolean,
    focusedChannelIndex: Int,
    guideFocused: Boolean,
    playingChannelId: String?,
    favoriteChannels: Set<String>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    // Refresh the current time every 30 seconds so the now-line and timeline stay accurate.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    val windowStart = now - (10 * 60_000L)   // 10 min past context
    val windowEnd = now + (120 * 60_000L)   // 2 hours future
    val nowRatio = ((now - windowStart).toFloat() / (windowEnd - windowStart).toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        GuideTimeHeader(windowStart = windowStart, now = now, windowEnd = windowEnd)
        Spacer(modifier = Modifier.height(6.dp))

        if (channels.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (isLoading) "Loading channels..." else "No channels in this group",
                    style = ArflixTypography.body,
                    color = TextSecondary
                )
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(
                    channels,
                    key = { _, ch -> ch.id },
                    contentType = { _, _ -> "guide_channel_row" }
                ) { index, channel ->
                    val focused = guideFocused && index == focusedChannelIndex
                    val slice = nowNext[channel.id]
                    val upcoming = remember(slice) {
                        buildList {
                            slice?.next?.let { add(it) }
                            slice?.later?.let { add(it) }
                            slice?.upcoming?.let { addAll(it) }
                        }.distinctBy { "${it.startUtcMillis}-${it.endUtcMillis}" }
                    }
                    GuideChannelRow(
                        channel = channel,
                        recentPrograms = slice?.recent.orEmpty(),
                        nowProgram = slice?.now,
                        upcomingPrograms = upcoming,
                        isFocused = focused,
                        isPlaying = channel.id == playingChannelId,
                        isFavoriteChannel = favoriteChannels.contains(channel.id),
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        now = now,
                        nowRatio = nowRatio
                    )
                }
            }
        }
    }
}

private val guideDateFormatter = DateTimeFormatter.ofPattern("EEE dd MMM")

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideTimeHeader(windowStart: Long, now: Long, windowEnd: Long) {
    val dateText = guideDateFormatter.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
    val timeStyle = ArflixTypography.caption.copy(fontSize = 12.sp, letterSpacing = 0.3.sp)

    // Generate half-hour markers within the window
    val halfHourMs = 30 * 60_000L
    val firstMark = ((windowStart / halfHourMs) + 1) * halfHourMs
    val hourMarkers = mutableListOf<Long>()
    var h = firstMark
    while (h < windowEnd) {
        hourMarkers.add(h)
        h += halfHourMs
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(170.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = dateText,
                style = timeStyle,
                color = TextSecondary.copy(alpha = 0.8f)
            )
        }

        BoxWithConstraints(modifier = Modifier.weight(1f).height(20.dp)) {
            val totalMs = (windowEnd - windowStart).coerceAtLeast(1L).toFloat()
            val totalWidth = maxWidth
            hourMarkers.forEach { marker ->
                val fraction = ((marker - windowStart).toFloat() / totalMs).coerceIn(0f, 0.95f)
                val isNearNow = abs(marker - now) < 15 * 60_000L
                val isHour = (marker % (60 * 60_000L)) == 0L
                Text(
                    formatProgramTime(marker),
                    style = timeStyle.copy(
                        fontWeight = if (isHour) FontWeight.Medium else FontWeight.Normal
                    ),
                    color = if (isNearNow) Color.White.copy(alpha = 0.9f)
                           else if (isHour) TextSecondary.copy(alpha = 0.7f)
                           else TextSecondary.copy(alpha = 0.45f),
                    modifier = Modifier
                        .offset(x = totalWidth * fraction)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideChannelRow(
    channel: IptvChannel,
    recentPrograms: List<IptvProgram>,
    nowProgram: IptvProgram?,
    upcomingPrograms: List<IptvProgram>,
    isFocused: Boolean,
    isPlaying: Boolean,
    isFavoriteChannel: Boolean,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    nowRatio: Float
) {
    val context = LocalContext.current
    val rowBg = when {
        isFocused -> Color(0xFF1C1C1E)
        isPlaying -> Color(0xFF141816)
        else -> Color(0xFF121214)
    }
    val borderColor = when {
        isFocused -> Color.White.copy(alpha = 0.85f)
        isPlaying -> AccentGreen.copy(alpha = 0.4f)
        else -> Color.Transparent
    }
    val primaryText = Color.White.copy(alpha = if (isFocused) 1f else 0.88f)
    val secondaryText = Color(0xFFA0A0A0)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(rowBg)
            .then(
                if (isFocused || isPlaying) Modifier.border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(8.dp)
                ) else Modifier
            )
    ) {
        // Compact channel info column
        Row(
            modifier = Modifier
                .width(170.dp)
                .fillMaxHeight()
                .padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(40.dp)) {
                if (!channel.logo.isNullOrBlank()) {
                    val logoRequest = remember(channel.logo) {
                        ImageRequest.Builder(context)
                            .data(channel.logo)
                            .size(80, 80)  // 40dp * 2x density - avoid decoding full-res logos
                            .precision(Precision.INEXACT)
                            .crossfade(false)
                            .allowHardware(true)
                            .build()
                    }
                    AsyncImage(
                        model = logoRequest,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.3f))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.LiveTv, contentDescription = null, tint = secondaryText, modifier = Modifier.size(20.dp))
                    }
                }
                // Favorite star overlay on logo
                if (isFavoriteChannel) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = Color(0xFFF5C518),
                        modifier = Modifier
                            .size(14.dp)
                            .align(Alignment.TopEnd)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = channel.name,
                        style = ArflixTypography.cardTitle.copy(fontSize = 14.sp),
                        color = primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isPlaying) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "LIVE",
                            style = ArflixTypography.caption.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier
                                .background(AccentGreen.copy(alpha = 0.8f), RoundedCornerShape(3.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(
                    text = channel.group,
                    style = ArflixTypography.caption.copy(fontSize = 11.sp),
                    color = secondaryText.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        TimelineProgramLane(
            recentPrograms = recentPrograms,
            nowProgram = nowProgram,
            upcomingPrograms = upcomingPrograms,
            windowStart = windowStart,
            windowEnd = windowEnd,
            now = now,
            nowRatio = nowRatio,
            isRowFocused = isFocused,
            isRowPlaying = isPlaying,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 4.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TimelineProgramLane(
    recentPrograms: List<IptvProgram>,
    nowProgram: IptvProgram?,
    upcomingPrograms: List<IptvProgram>,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    nowRatio: Float,
    isRowFocused: Boolean,
    isRowPlaying: Boolean = false,
    modifier: Modifier = Modifier
) {
    val laneTextColor = Color(0xFFF1F1F1)
    val nowAccent = AccentGreen
    Box(modifier = modifier.clip(RoundedCornerShape(6.dp))) {
        Row(modifier = Modifier.fillMaxSize()) {
            val segments = remember(recentPrograms, nowProgram, upcomingPrograms, windowStart, windowEnd) {
                buildProgramSegments(recentPrograms, nowProgram, upcomingPrograms, windowStart, windowEnd)
            }
            if (segments.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.03f))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        "No EPG data",
                        style = ArflixTypography.caption.copy(fontSize = 12.sp),
                        color = laneTextColor.copy(alpha = 0.4f)
                    )
                }
            } else {
                segments.forEach { seg ->
                    val fillColor = when {
                        seg.isFiller -> Color.White.copy(alpha = 0.02f)
                        seg.isPast -> Color.White.copy(alpha = if (isRowFocused) 0.04f else 0.02f)
                        seg.isNow && isRowPlaying -> nowAccent.copy(alpha = 0.12f)
                        seg.isNow && isRowFocused -> Color.White.copy(alpha = 0.10f)
                        seg.isNow -> Color.White.copy(alpha = 0.06f)
                        isRowFocused -> Color.White.copy(alpha = 0.05f)
                        else -> Color.White.copy(alpha = 0.03f)
                    }
                    Box(
                        modifier = Modifier
                            .weight(seg.weight)
                            .fillMaxHeight()
                            .padding(horizontal = 1.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(fillColor)
                            .then(
                                if (seg.isNow) Modifier.border(
                                    width = 1.dp,
                                    color = if (isRowFocused) Color.White.copy(alpha = 0.35f)
                                        else if (isRowPlaying) nowAccent.copy(alpha = 0.3f)
                                        else Color.White.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(4.dp)
                                ) else Modifier
                            )
                    ) {
                        // Program text
                        if (seg.label.isNotBlank()) {
                            Text(
                                text = seg.label,
                                style = ArflixTypography.caption.copy(
                                    fontSize = 13.sp,
                                    fontWeight = if (seg.isNow) FontWeight.Medium else FontWeight.Normal,
                                    lineHeight = 16.sp
                                ),
                                color = laneTextColor.copy(
                                    alpha = when {
                                        seg.isFiller -> 0.4f
                                        seg.isPast -> 0.35f
                                        seg.isNow -> 0.95f
                                        else -> 0.7f
                                    }
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        // Progress bar at bottom of "now" segment
                        if (seg.isNow && nowProgram != null) {
                            val progDuration = (nowProgram.endUtcMillis - nowProgram.startUtcMillis).coerceAtLeast(1L)
                            val progElapsed = (now - nowProgram.startUtcMillis).coerceIn(0, progDuration)
                            val progFraction = (progElapsed.toFloat() / progDuration.toFloat()).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(Color.White.copy(alpha = 0.08f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(progFraction)
                                        .fillMaxHeight()
                                        .background(
                                            if (isRowPlaying) nowAccent.copy(alpha = 0.7f)
                                            else Color.White.copy(alpha = 0.45f)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Now-line indicator: accent colored vertical line
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(nowRatio)
                .align(Alignment.CenterStart),
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(
                        if (isRowFocused) nowAccent.copy(alpha = 0.9f)
                        else nowAccent.copy(alpha = 0.5f)
                    )
            )
        }
    }
}

private data class ProgramSegment(
    val label: String,
    val weight: Float,
    val isNow: Boolean,
    val isFiller: Boolean = false,
    val isPast: Boolean = false
)

private fun buildProgramSegments(
    recentPrograms: List<IptvProgram>,
    nowProgram: IptvProgram?,
    upcomingPrograms: List<IptvProgram>,
    windowStart: Long,
    windowEnd: Long
): List<ProgramSegment> {
    val totalWindow = (windowEnd - windowStart).coerceAtLeast(1L).toFloat()
    fun weight(start: Long, end: Long): Float {
        val s = start.coerceIn(windowStart, windowEnd)
        val e = end.coerceIn(windowStart, windowEnd)
        val clamped = (e - s).coerceAtLeast(0L)
        return (clamped / totalWindow).coerceIn(0f, 1f)
    }
    fun labelWithTime(program: IptvProgram, w: Float): String {
        // Only prefix time when the segment is wide enough to show it
        return if (w >= 0.12f) {
            val time = formatProgramTime(program.startUtcMillis)
            "$time  ${program.title}"
        } else {
            program.title
        }
    }

    // Build a chronological list of all programs with their absolute times
    data class TimedProgram(val start: Long, val end: Long, val program: IptvProgram, val isNow: Boolean, val isPast: Boolean)
    val allPrograms = mutableListOf<TimedProgram>()
    recentPrograms.forEach { allPrograms += TimedProgram(it.startUtcMillis, it.endUtcMillis, it, isNow = false, isPast = true) }
    nowProgram?.let { allPrograms += TimedProgram(it.startUtcMillis, it.endUtcMillis, it, isNow = true, isPast = false) }
    upcomingPrograms.forEach { allPrograms += TimedProgram(it.startUtcMillis, it.endUtcMillis, it, isNow = false, isPast = false) }
    allPrograms.sortBy { it.start }

    // Build segments with gap fillers between programs
    val items = mutableListOf<ProgramSegment>()
    var cursor = windowStart
    for (tp in allPrograms) {
        val segStart = tp.start.coerceIn(windowStart, windowEnd)
        val segEnd = tp.end.coerceIn(windowStart, windowEnd)
        if (segEnd <= segStart) continue
        // Insert gap filler if there's space between cursor and this segment
        if (segStart > cursor) {
            val gapW = ((segStart - cursor).toFloat() / totalWindow).coerceIn(0f, 1f)
            if (gapW > 0.01f) items += ProgramSegment(label = "", weight = gapW, isNow = false, isFiller = true)
        }
        val w = ((segEnd - segStart).toFloat() / totalWindow).coerceIn(0f, 1f)
        if (w > 0.02f) items += ProgramSegment(labelWithTime(tp.program, w), w, isNow = tp.isNow, isPast = tp.isPast)
        cursor = segEnd.coerceAtLeast(cursor)
    }
    // Trailing filler
    if (cursor < windowEnd) {
        val trailW = ((windowEnd - cursor).toFloat() / totalWindow).coerceIn(0f, 1f)
        if (trailW > 0.01f) items += ProgramSegment(label = "", weight = trailW, isNow = false, isFiller = true)
    }

    val mergedItems = mergeDuplicateSegments(items)
    return ensureReadableProgramWidths(mergedItems)
}

private fun mergeDuplicateSegments(items: List<ProgramSegment>): List<ProgramSegment> {
    if (items.isEmpty()) return items
    val merged = mutableListOf<ProgramSegment>()
    items.forEach { seg ->
        val last = merged.lastOrNull()
        if (
            last != null &&
            last.label.equals(seg.label, ignoreCase = true) &&
            last.isNow == seg.isNow &&
            last.isFiller == seg.isFiller
        ) {
            merged[merged.lastIndex] = last.copy(weight = last.weight + seg.weight)
        } else {
            merged += seg
        }
    }
    return merged
}

private fun ensureReadableProgramWidths(items: List<ProgramSegment>): List<ProgramSegment> {
    if (items.isEmpty()) return items
    val labeled = items.filter { it.label.isNotBlank() }
    if (labeled.isEmpty()) return items

    val minReadable = 0.14f

    // Boost small labeled segments to minimum readable width
    val boosted = items.map { seg ->
        if (seg.label.isNotBlank()) seg.copy(weight = maxOf(seg.weight, minReadable)) else seg
    }.toMutableList()

    // Normalize: ensure total weights equal 1.0 by adjusting filler segments proportionally
    val totalWeight = boosted.sumOf { it.weight.toDouble() }.toFloat()
    if (totalWeight > 1.01f) {
        // Shrink fillers first to compensate for boosted labels
        val fillerTotal = boosted.filter { it.isFiller }.sumOf { it.weight.toDouble() }.toFloat()
        val excess = totalWeight - 1f
        if (fillerTotal > excess) {
            val fillerFactor = (fillerTotal - excess) / fillerTotal
            for (i in boosted.indices) {
                if (boosted[i].isFiller) boosted[i] = boosted[i].copy(weight = boosted[i].weight * fillerFactor)
            }
        } else {
            // Not enough filler to absorb - proportionally shrink everything
            val factor = 1f / totalWeight
            for (i in boosted.indices) boosted[i] = boosted[i].copy(weight = boosted[i].weight * factor)
        }
    }

    // Remove zero-weight fillers
    return boosted.filter { !(it.isFiller && it.weight < 0.01f) }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NotConfiguredPanel() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundCard, RoundedCornerShape(14.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.LiveTv,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("IPTV is not configured", style = ArflixTypography.sectionTitle, color = TextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Open Settings and add your M3U URL.",
                style = ArflixTypography.body,
                color = TextSecondary
            )
        }
    }
}

private val programTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

private fun formatProgramTime(utcMillis: Long): String {
    return programTimeFormatter.format(Instant.ofEpochMilli(utcMillis).atZone(ZoneId.systemDefault()))
}


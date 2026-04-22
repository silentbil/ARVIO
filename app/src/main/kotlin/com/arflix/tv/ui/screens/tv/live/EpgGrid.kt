package com.arflix.tv.ui.screens.tv.live

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.IptvNowNext
import com.arflix.tv.data.model.IptvProgram
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * EPG grid per spec §3.4.
 * Window: (now - 1h rounded to :30) → +9h = 10h wide.
 * Constants: 5dp/min, 150dp per 30min, rows 84dp tall.
 * Scroll sync: header ↔ body (horizontal) + channel column ↔ body (vertical).
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EpgGrid(
    channels: List<EnrichedChannel>,
    nowNext: Map<String, IptvNowNext>,
    selectedChannelId: String?,
    onChannelSelect: (EnrichedChannel) -> Unit,
    onChannelFavoriteToggle: (String) -> Unit,
    favorites: Set<String>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pxPerMin = LiveDims.EpgPxPerMinute

    // Window: now − 30 min → now + 2 h = 2.5 h total.
    // Past is limited to 30 min so most of the ruler is future programmes
    // (what you're about to watch), not what already aired.
    val windowStartMillis = remember { roundedWindowStart() }
    val windowEndMillis = remember(windowStartMillis) {
        windowStartMillis + 150L * 60 * 1000 // 2h30m
    }
    // 5 half-hour slots across the window.
    val slots = remember { buildHalfHourSlots(windowStartMillis, 5) }

    // Shared horizontal scroll state between header and body rows.
    val hScroll = rememberScrollState()
    // Two separate vertical list states: one per LazyColumn.
    // A single LazyListState cannot be shared across two LazyColumns —
    // Compose asserts exclusive ownership and crashes on recomposition
    // when it detects two attached hosts. We keep them in lock-step via
    // snapshotFlow below.
    val channelListState = rememberLazyListState()
    val programListState = rememberLazyListState()

    // One-way scroll sync: the program grid leads, the channel column
    // mirrors it. A bidirectional setup caused a feedback loop and
    // noticeable DPAD jank on long lists.
    LaunchedEffect(channelListState, programListState) {
        snapshotFlow { programListState.firstVisibleItemIndex to programListState.firstVisibleItemScrollOffset }
            .collect { (idx, off) ->
                if (channelListState.firstVisibleItemIndex != idx ||
                    channelListState.firstVisibleItemScrollOffset != off
                ) {
                    channelListState.scrollToItem(idx, off)
                }
            }
    }

    val scope = rememberCoroutineScope()

    // Park NOW ~30 dp from the left so only a thin slice of the past is
    // visible and the rest of the viewport holds upcoming programmes.
    LaunchedEffect(Unit) {
        with(density) {
            val nowOffsetMin = ((System.currentTimeMillis() - windowStartMillis) / 60_000L).toInt()
            val targetPx = (nowOffsetMin * pxPerMin).dp.toPx().toInt() - 30.dp.toPx().toInt()
            hScroll.scrollTo(targetPx.coerceAtLeast(0))
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(LiveColors.Bg),
    ) {
        // ─── Header row ─────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(LiveDims.EpgHeaderHeight)
                .background(LiveColors.PanelDeep),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Sticky channel-column label + current CH indicator
            Row(
                modifier = Modifier
                    .width(LiveDims.EpgChannelColWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("CHANNELS", style = LiveType.SectionTag.copy(color = LiveColors.FgMute))
                    Text(channels.size.toString(),
                        style = LiveType.NumberMono.copy(color = LiveColors.FgDim))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("CH", style = LiveType.SectionTag.copy(color = LiveColors.Accent))
                    val currentNumber = channels.firstOrNull { it.id == selectedChannelId }?.number
                    Text(
                        currentNumber?.toString() ?: "—",
                        style = LiveType.NumberMono.copy(color = LiveColors.Accent),
                    )
                }
            }
            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(LiveColors.DividerStrong)
            )
            // Scrolling time ruler with NOW pill pinned to the current minute.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll),
            ) {
                Row {
                    slots.forEach { slot ->
                        Box(
                            modifier = Modifier
                                .width(LiveDims.EpgHalfHourWidth)
                                .fillMaxHeight()
                                .padding(start = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = slot.label,
                                style = LiveType.TimeMono.copy(color = LiveColors.FgDim),
                            )
                        }
                    }
                }
                // Cyan "NOW hh:mm" pill hovering above the now-line inside the header.
                val nowMin = ((System.currentTimeMillis() - windowStartMillis) / 60_000L).toInt()
                val nowOffset = (nowMin * pxPerMin).dp
                Box(
                    modifier = Modifier
                        .offset(x = nowOffset - 46.dp, y = 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(LiveColors.Accent)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "NOW " + formatClock(System.currentTimeMillis()),
                        style = LiveType.Badge.copy(color = LiveColors.Bg),
                    )
                }
            }
        }

        // Thin divider under header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LiveColors.Divider),
        )

        // ─── Body ───────────────────────────────────────────────────
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Channel column (sticky left, vertical scroll only)
                LazyColumn(
                    state = channelListState,
                    modifier = Modifier
                        .width(LiveDims.EpgChannelColWidth)
                        .fillMaxHeight()
                        .background(LiveColors.PanelDeep),
                ) {
                    itemsIndexed(
                        channels,
                        key = { _, ch -> ch.id },
                        contentType = { _, _ -> "channel" }
                    ) { idx, ch ->
                        ChannelRow(
                            channel = ch,
                            isActive = ch.id == selectedChannelId,
                            nowNext = nowNext[ch.id],
                            isFavorite = ch.id in favorites,
                            stripe = idx % 2 == 1,
                            onClick = { onChannelSelect(ch) },
                            onFavoriteToggle = { onChannelFavoriteToggle(ch.id) },
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(LiveColors.Divider)
                )
                // Program grid (scrolls both ways, synced with above)
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = programListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(hScroll),
                    ) {
                        itemsIndexed(
                            channels,
                            key = { _, ch -> ch.id },
                            contentType = { _, _ -> "programsRow" }
                        ) { idx, ch ->
                            // Memoise the windowed program list per channel.
                            // Without this, every vertical scroll tick triggers
                            // a full recomputation across every visible row —
                            // which turns the category-expand interaction into
                            // a stutter/ANR on lower-end TV boxes.
                            val rowPrograms = remember(
                                ch.id,
                                nowNext[ch.id],
                                windowStartMillis,
                                windowEndMillis,
                            ) {
                                programsInWindow(nowNext[ch.id], windowStartMillis, windowEndMillis)
                            }
                            ProgramsRow(
                                channel = ch,
                                programs = rowPrograms,
                                windowStartMillis = windowStartMillis,
                                pxPerMin = pxPerMin,
                                stripe = idx % 2 == 1,
                                isActive = ch.id == selectedChannelId,
                                onClick = { onChannelSelect(ch) },
                            )
                        }
                    }
                    // NOW glow line across full body
                    NowLine(
                        windowStartMillis = windowStartMillis,
                        pxPerMin = pxPerMin,
                        hScrollOffsetPx = hScroll.value,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProgramsRow(
    channel: EnrichedChannel,
    programs: List<IptvProgram>,
    windowStartMillis: Long,
    pxPerMin: Int,
    stripe: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val totalWidth = LiveDims.EpgHalfHourWidth * 5
    val nowMillis = System.currentTimeMillis()
    Box(
        modifier = Modifier
            .width(totalWidth)
            .height(LiveDims.EpgRowHeight)
            .background(
                when {
                    isActive -> LiveColors.FocusBg
                    stripe -> LiveColors.RowStripe
                    else -> Color.Transparent
                }
            ),
    ) {
        if (programs.isNotEmpty()) {
            programs.forEach { p ->
                val startMin = ((p.startUtcMillis - windowStartMillis) / 60_000L).toInt().coerceAtLeast(0)
                // 30-min floor → 150dp min block width, enough room to render
                // LIVE badge + title + time without immediate ellipsis.
                val durationMin = ((p.endUtcMillis - p.startUtcMillis) / 60_000L).toInt().coerceAtLeast(30)
                val offset = (startMin * pxPerMin).dp
                val width = (durationMin * pxPerMin).dp
                val isNow = nowMillis in p.startUtcMillis..p.endUtcMillis
                val isPast = p.endUtcMillis < nowMillis
                ProgramCell(
                    program = p,
                    width = width,
                    isNow = isNow,
                    isPast = isPast,
                    isFocusTarget = isNow,
                    onClick = onClick,
                    modifier = Modifier.offset(x = offset),
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NowLine(
    windowStartMillis: Long,
    pxPerMin: Int,
    hScrollOffsetPx: Int,
) {
    val density = LocalDensity.current
    val nowMin = ((System.currentTimeMillis() - windowStartMillis) / 60_000L).toInt()
    val xDp = with(density) { ((nowMin * pxPerMin).dp.toPx() - hScrollOffsetPx).toDp() }
    if (xDp < 0.dp) return
    Box(
        modifier = Modifier
            .offset(x = xDp)
            .fillMaxHeight()
            .width(2.dp)
            .background(LiveColors.Accent),
    )
    // Glow behind the 2dp line
    Box(
        modifier = Modifier
            .offset(x = xDp - 3.dp)
            .fillMaxHeight()
            .width(8.dp)
            .background(LiveColors.Accent.copy(alpha = 0.22f)),
    )
}

private data class TimeSlot(val millis: Long, val label: String, val isNow: Boolean)

private fun buildHalfHourSlots(startMillis: Long, count: Int): List<TimeSlot> {
    val out = ArrayList<TimeSlot>(count)
    val now = System.currentTimeMillis()
    for (i in 0 until count) {
        val t = startMillis + i * 30L * 60_000L
        val isNow = now in t..(t + 30L * 60_000L - 1)
        out += TimeSlot(t, formatClock(t), isNow)
    }
    return out
}

/** Round down to the nearest half-hour, shifted 30 min back so the user
 *  can still see what just aired without the past dominating the viewport. */
private fun roundedWindowStart(): Long {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = System.currentTimeMillis()
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    val min = cal.get(java.util.Calendar.MINUTE)
    cal.set(java.util.Calendar.MINUTE, if (min >= 30) 30 else 0)
    return cal.timeInMillis - 30L * 60_000L
}

private fun programsInWindow(
    item: IptvNowNext?,
    start: Long,
    end: Long,
): List<IptvProgram> {
    if (item == null) return emptyList()
    val buf = ArrayList<IptvProgram>(16)
    fun add(p: IptvProgram?) {
        if (p == null) return
        if (p.endUtcMillis > start && p.startUtcMillis < end) buf.add(p)
    }
    item.recent.forEach(::add)
    add(item.now)
    add(item.next)
    add(item.later)
    item.upcoming.forEach(::add)
    // De-dup by start time
    return buf.distinctBy { it.startUtcMillis }
        .sortedBy { it.startUtcMillis }
}

# TV Page Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix six defects/gaps on the Live TV screen: one-press collapse, EPG fallback cells, scroll-sync jank, default-to-favorite channel, premium fullscreen HUD, up/down zapping.

**Architecture:** In-place Compose edits to existing files under `app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/`. One new file — `FullscreenHud.kt` — for the HUD overlay. No ViewModel changes, no data-layer changes.

**Tech Stack:** Jetpack Compose, TV Material3, ExoPlayer (unchanged), existing `LiveColors`/`LiveType`/`LiveDims` tokens.

**Testing note:** These are Compose UI/UX changes on Android TV with DPAD focus and ExoPlayer playback. No unit-testable pure logic is introduced — verification is manual on device/APK per the spec's §Testing checklist. Each task ends with a build-green gate (`./gradlew :app:compileDebugKotlin`) and a commit. Final task runs the APK and walks the 6 manual checks.

---

## File Structure

- **Modify** `app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/CategorySidebar.kt` — one-press toggle (Fix 1).
- **Modify** `app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/EpgGrid.kt` — drop name-fallback block (Fix 2), one-way scroll sync (Fix 3a).
- **Modify** `app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/LiveTvScreen.kt` — filter-trigger (Fix 3b), default-to-fav (Fix 4), fullscreen key handling + HUD hosting (Fix 6 + part of Fix 5).
- **Create** `app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/FullscreenHud.kt` — new HUD composable (Fix 5).

---

### Task 1: One-press country-group collapse

**Files:**
- Modify: `app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/CategorySidebar.kt:147-154`

- [ ] **Step 1: Replace the `onClick` body on the country `SidebarRow`**

Locate (lines ~147-154 inside the `tree.countries.categories` block):

```kotlin
onClick = {
    // First press selects the country; second press toggles children.
    if (selectedId != country.id) {
        onSelect(country.id)
    } else {
        expandedCountry = if (isExpanded) null else country.id
    }
},
```

Replace with:

```kotlin
onClick = {
    // Tap always toggles expansion. Opening also selects so the grid
    // reflects the group the user just opened; collapsing leaves the
    // selection untouched so the user can close a group without losing
    // their current filter.
    if (isExpanded) {
        expandedCountry = null
    } else {
        expandedCountry = country.id
        onSelect(country.id)
    }
},
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/CategorySidebar.kt
git commit -m "fix(tv): one-press collapse on sidebar country groups"
```

---

### Task 2: Remove EPG name-fallback block

**Files:**
- Modify: `app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/EpgGrid.kt:337-362`

- [ ] **Step 1: Delete the `programs.isEmpty()` name-fallback block**

Locate the `if (programs.isEmpty()) { … } else { programs.forEach { … } }` inside `ProgramsRow` (around lines 337-385).

Replace the `if/else` with just the programmes branch; when empty we render nothing over the striped background:

```kotlin
        if (programs.isNotEmpty()) {
            programs.forEach { p ->
                val startMin = ((p.startUtcMillis - windowStartMillis) / 60_000L).toInt().coerceAtLeast(0)
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
```

The outer `Box` (which applies row background/stripe) stays in place so empty rows still render their stripe.

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/EpgGrid.kt
git commit -m "fix(tv): drop misleading channel-name block in empty EPG rows"
```

---

### Task 3: One-way scroll sync (fix ping-pong)

**Files:**
- Modify: `app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/EpgGrid.kt:108-127`

- [ ] **Step 1: Delete the channel→program `LaunchedEffect`**

Delete the first `LaunchedEffect` block (the one whose `snapshotFlow` reads `channelListState.firstVisibleItemIndex`). Keep the second one (`programListState → channelListState`).

After the edit, this region of the file should contain only:

```kotlin
    // Bidirectional scroll sync replaced with a single leader to avoid
    // feedback loops on DPAD travel. The program grid leads; the channel
    // column mirrors it.
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
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/EpgGrid.kt
git commit -m "perf(tv): one-way EPG scroll sync to fix DPAD feedback loop"
```

---

### Task 4: Narrow the filter-recompute trigger on `recents`

**Files:**
- Modify: `app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/LiveTvScreen.kt:162-168`

- [ ] **Step 1: Swap the `recents.value` key for a category-gated key**

Locate:

```kotlin
    val filteredChannelsState = remember { mutableStateOf<List<EnrichedChannel>>(emptyList()) }
    LaunchedEffect(enrichedState.value, selectedCategoryId, favSet, recents.value) {
        val enriched = enrichedState.value.all
        val matcher = categoryMatcher(selectedCategoryId, favSet, recents.value)
        val result = withContext(Dispatchers.Default) { enriched.filter(matcher) }
        filteredChannelsState.value = result
    }
```

Replace with:

```kotlin
    val filteredChannelsState = remember { mutableStateOf<List<EnrichedChannel>>(emptyList()) }
    // Only let `recents` invalidate the filter when Recent is the active
    // category; otherwise every channel tune would trigger a full scan of
    // the (potentially 50k-entry) enriched list and stutter DPAD travel.
    val recentsFilterKey = if (selectedCategoryId == "recent") recents.value else Unit
    LaunchedEffect(enrichedState.value, selectedCategoryId, favSet, recentsFilterKey) {
        val enriched = enrichedState.value.all
        val matcher = categoryMatcher(selectedCategoryId, favSet, recents.value)
        val result = withContext(Dispatchers.Default) { enriched.filter(matcher) }
        filteredChannelsState.value = result
    }
```

Note: `matcher` still reads the current `recents.value` at evaluation time — correctness is preserved; we only stop *invalidating* outside the Recent category.

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/LiveTvScreen.kt
git commit -m "perf(tv): gate filter recompute on recents to current category"
```

---

### Task 5: Default playing channel to first favorite

**Files:**
- Modify: `app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/LiveTvScreen.kt:179-183`

- [ ] **Step 1: Prefer a favorite when seeding `playingChannelId`**

Locate:

```kotlin
    LaunchedEffect(filteredChannels, playingChannelId) {
        if (playingChannelId == null && filteredChannels.isNotEmpty()) {
            playingChannelId = filteredChannels.first().id
        }
    }
```

Replace with:

```kotlin
    LaunchedEffect(filteredChannels, playingChannelId) {
        if (playingChannelId == null && filteredChannels.isNotEmpty()) {
            // Prefer the first favorite if any of the currently-filtered
            // channels is favorited — matches user expectation that
            // opening TV starts on "their" channel.
            playingChannelId = filteredChannels.firstOrNull { it.id in favSet }?.id
                ?: filteredChannels.first().id
        }
    }
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/LiveTvScreen.kt
git commit -m "feat(tv): default mini-player to first favorite on open"
```

---

### Task 6: Create FullscreenHud composable

**Files:**
- Create: `app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/FullscreenHud.kt`

- [ ] **Step 1: Write the HUD file**

Create the file with exactly this content:

```kotlin
@file:Suppress("UnsafeOptInUsageError")

package com.arflix.tv.ui.screens.tv.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.IptvNowNext
import kotlinx.coroutines.delay

/**
 * Fullscreen playback HUD. Shows channel identity, NOW programme info,
 * NEXT row, and a wall clock. Auto-hides after 5s of no interaction;
 * `pokeSignal` is a monotonic counter — bump it from outside (on any
 * DPAD key) to re-show and reset the auto-hide timer.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FullscreenHud(
    channel: EnrichedChannel?,
    nowNext: IptvNowNext?,
    pokeSignal: Int,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(true) }
    var lastPoke by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // React to pokes: show the HUD and record the poke time.
    LaunchedEffect(pokeSignal) {
        visible = true
        lastPoke = System.currentTimeMillis()
    }

    // Auto-hide 5 s after the last poke.
    LaunchedEffect(pokeSignal) {
        delay(5_000)
        if (System.currentTimeMillis() - lastPoke >= 5_000) {
            visible = false
        }
    }

    // Re-tick the wall clock each minute so the top-right clock stays fresh.
    var clockMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            clockMillis = System.currentTimeMillis()
            delay(30_000)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Subtle bottom gradient so the HUD reads against bright content.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color(0xCC000000),
                        )
                    ),
            )

            // Top-left channel identity chip.
            if (channel != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(20.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x66000000))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ChannelLogo(channel = channel, size = 40.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "CH ${channel.number}",
                            style = LiveType.SectionTag.copy(color = LiveColors.FgMute),
                        )
                        Text(
                            text = channel.name,
                            style = LiveType.ChannelName.copy(
                                color = LiveColors.Fg,
                                fontSize = 16.sp,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            HudBadge(channel.quality.label, LiveColors.Fg, LiveColors.Panel)
                            channel.country?.takeIf { it != channel.lang }?.let {
                                HudBadge(it.uppercase(), LiveColors.FgDim, LiveColors.Panel)
                            }
                            HudBadge(channel.lang.uppercase(), LiveColors.FgDim, LiveColors.Panel)
                        }
                    }
                }
            }

            // Top-right wall clock.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x66000000))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = formatClock(clockMillis),
                    style = LiveType.TimeMono.copy(
                        color = LiveColors.Fg,
                        fontSize = 18.sp,
                    ),
                )
            }

            // Bottom card: NOW / NEXT.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.6f)
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .wrapContentHeight()
                    .clip(RoundedCornerShape(LiveDims.CardRadius))
                    .background(LiveColors.PanelRaised.copy(alpha = 0.85f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                val now = nowNext?.now
                val next = nowNext?.next
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("NOW", style = LiveType.SectionTag.copy(color = LiveColors.Accent))
                        Text(
                            text = formatTimeWindow(now),
                            style = LiveType.TimeMono.copy(color = LiveColors.Fg),
                        )
                        Spacer(Modifier.weight(1f))
                        val remaining = remainingLabel(now)
                        if (remaining.isNotBlank()) {
                            Text(
                                text = remaining,
                                style = LiveType.TimeMono.copy(color = LiveColors.Accent),
                            )
                        }
                    }
                    Text(
                        text = now?.title
                            ?: channel?.name
                            ?: "No programme data",
                        style = LiveType.ProgramTitle.copy(
                            color = LiveColors.Fg,
                            fontSize = 18.sp,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!now?.description.isNullOrBlank()) {
                        Text(
                            text = now!!.description!!,
                            style = LiveType.BodySynopsis.copy(
                                color = LiveColors.FgDim,
                                fontSize = 12.sp,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val progress = progressOf(now)
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = LiveColors.Accent,
                            trackColor = LiveColors.Panel,
                        )
                    }
                    if (next != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(LiveColors.Divider),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("NEXT", style = LiveType.SectionTag.copy(color = LiveColors.FgMute))
                            Text(
                                text = formatClock(next.startUtcMillis),
                                style = LiveType.TimeMono.copy(color = LiveColors.FgDim),
                            )
                            Text(
                                text = next.title,
                                style = LiveType.CellTitle.copy(
                                    color = LiveColors.FgDim,
                                    fontSize = 12.sp,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HudBadge(label: String, fg: Color, bg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = LiveType.Badge.copy(color = fg, fontSize = 10.sp))
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (The file uses only symbols already defined in the same package: `EnrichedChannel`, `ChannelLogo`, `IptvNowNext`, `LiveColors`, `LiveType`, `LiveDims`, `formatClock`, `formatTimeWindow`, `remainingLabel`, `progressOf`.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/FullscreenHud.kt
git commit -m "feat(tv): premium fullscreen HUD composable"
```

---

### Task 7: Wire HUD + up/down zapping into LiveTvScreen

**Files:**
- Modify: `app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/LiveTvScreen.kt:284-416`

- [ ] **Step 1: Add poke-signal state, key handling, and HUD host**

Above the `BackHandler` block (around line 282) add:

```kotlin
    // Monotonic counter bumped on every DPAD key while in fullscreen.
    // The HUD observes this and resets its auto-hide timer.
    var hudPokeSignal by remember { mutableStateOf(0) }

    // Prev/next zapping across the full enriched list (not the filtered
    // category) per user spec. Wraps around at both ends.
    fun zap(delta: Int) {
        val all = enrichedState.value.all
        if (all.isEmpty()) return
        val currentIdx = all.indexOfFirst { it.id == playingChannelId }
        val start = if (currentIdx >= 0) currentIdx else 0
        val size = all.size
        val nextIdx = ((start + delta) % size + size) % size
        playingChannelId = all[nextIdx].id
    }
```

Now find the `Box` that hosts the fullscreen `PlayerView` (at lines ~391-415, wrapped in `if (fsProgress > 0f && playingChannel != null)`). Attach an `onPreviewKeyEvent` to its `Modifier` chain and add the HUD as a sibling inside the same `Box`. Replace that block with:

```kotlin
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
```

- [ ] **Step 2: Ensure required imports are present**

Near the existing key/focus imports at the top of the file, make sure the following are imported (add any that are missing):

```kotlin
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
```

- [ ] **Step 3: Request focus on the fullscreen box so keys reach it**

At the bottom of the same outer `Box` (after the HUD block, before the closing brace of the outer `Box` in `LiveTvScreen`), add a focus requester and trigger it when fullscreen engages. Update the Box declaration above:

Add `val fsFocus = remember { FocusRequester() }` near the other focus requesters (around line 194) and chain `.focusRequester(fsFocus)` onto the fullscreen Box's modifier before `.focusable()`. Then, immediately after the fullscreen Box, add:

```kotlin
        LaunchedEffect(isFullScreen) {
            if (isFullScreen) {
                runCatching { fsFocus.requestFocus() }
            }
        }
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/LiveTvScreen.kt
git commit -m "feat(tv): fullscreen HUD + DPAD up/down channel zapping"
```

---

### Task 8: Build APK and manual verification walk-through

- [ ] **Step 1: Assemble debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Install on the connected Android TV**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: `Success`.

- [ ] **Step 3: Walk the 6-point manual check on-device**

Open the TV page and verify each of the following. If any fails, capture the symptom, go back to the relevant task, fix, and re-run from Step 1.

1. Expand any country group in the sidebar; a single DPAD-center press on the *same* country collapses the group.
2. Channels without EPG show an empty striped row under the time ruler (no channel-name block sitting where programmes should be). Channels with EPG show programme cells with title + time.
3. DPAD travel through Favorites → Sports → News → a country group feels smooth; rapidly changing channels does not cause visible stutter.
4. Cold-launch the app → open TV page → mini-player lands on the first favorite channel (if any favorites exist), else on the first channel of the selected category.
5. Press OK on an already-playing row → playback fills screen → HUD appears with channel identity (top-left), wall clock (top-right), NOW/NEXT card (bottom). HUD fades out ~5s after last key press; any DPAD key re-shows it.
6. While in fullscreen, DPAD-Up and DPAD-Down move to previous/next channel across the entire playlist and HUD refreshes.

- [ ] **Step 4: Final commit (only if any tweak was needed)**

If Steps 1-3 passed without code changes, nothing to commit here. Otherwise, commit the final fix with a message describing what was adjusted.

---

## Self-Review

**Spec coverage:**
- Fix 1 (one-press collapse): Task 1 ✓
- Fix 2 (EPG name-fallback): Task 2 ✓
- Fix 3a (scroll-sync loop): Task 3 ✓
- Fix 3b (filter trigger): Task 4 ✓
- Fix 4 (default to favorite): Task 5 ✓
- Fix 5 (HUD): Tasks 6 + 7 ✓ (no pulsing LIVE dot — excluded per user)
- Fix 6 (up/down zap across all channels): Task 7 ✓
- Non-goals honored: no EPG-source work, no recents persistence, no sidebar redesign, no top-bar/search changes.

**Placeholders:** No TBDs, no "handle edge cases," every code block is concrete and drop-in.

**Type consistency:** `FullscreenHud` parameters in Task 6 match the call site in Task 7 (`channel`, `nowNext`, `pokeSignal`). `zap(delta: Int)` matches call sites `zap(-1)`/`zap(+1)`. `hudPokeSignal: Int` incremented with `++` in Task 7. `Key.DirectionCenter`/`Key.Enter` keys used for HUD toggle — consistent with existing code patterns in `CategorySidebar.kt`.

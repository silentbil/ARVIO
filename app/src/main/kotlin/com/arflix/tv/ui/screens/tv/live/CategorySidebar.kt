package com.arflix.tv.ui.screens.tv.live

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/**
 * Left-hand category sidebar. Spec §3.1.
 * Width = 260dp (expanded). Rows 44dp tall with a left active indicator,
 * section headers use mono 10sp tracking +16%.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CategorySidebar(
    tree: LiveCategoryTree,
    selectedId: String,
    expanded: Boolean,
    onSelect: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onFocusEnter: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val targetWidth = if (expanded) LiveDims.SidebarExpanded else LiveDims.SidebarCollapsed
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(durationMillis = 240),
        label = "sidebar-width",
    )
    var expandedCountry by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .width(animatedWidth)
            .fillMaxHeight()
            .background(LiveColors.PanelDeep)
            .onFocusChanged { if (it.hasFocus) onFocusEnter() }
            // Trap DPAD_LEFT at the sidebar edge so the key doesn't bubble
            // up to the Activity and back out to the Android launcher.
            .onPreviewKeyEvent { ev ->
                ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SearchEntry(onClick = onOpenSearch, expanded = expanded)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(tree.top, key = { it.id }) { cat ->
                SidebarRow(
                    label = cat.label,
                    count = cat.count,
                    icon = iconFor(cat),
                    active = selectedId == cat.id,
                    expanded = expanded,
                    onClick = { onSelect(cat.id) },
                )
            }
            if (tree.global.categories.isNotEmpty()) {
                item { SectionHeader(tree.global.label, expanded) }
                items(tree.global.categories, key = { it.id }) { cat ->
                    SidebarRow(
                        label = cat.label,
                        count = cat.count,
                        icon = iconFor(cat),
                        active = selectedId == cat.id,
                        expanded = expanded,
                        onClick = { onSelect(cat.id) },
                    )
                }
            }
            if (tree.countries.categories.isNotEmpty()) {
                item { SectionHeader(tree.countries.label, expanded) }
                items(tree.countries.categories, key = { it.id }) { country ->
                    val isExpanded = expandedCountry == country.id
                    SidebarRow(
                        label = country.label,
                        count = country.count,
                        icon = null,
                        leadingCode = country.id,
                        active = selectedId == country.id,
                        expanded = expanded,
                        hasChildren = country.children.isNotEmpty(),
                        isOpenGroup = isExpanded,
                        onClick = {
                            // Tap always toggles expansion. Opening also selects so
                            // the grid reflects the just-opened group; collapsing
                            // leaves selection alone so the user can close a group
                            // without losing their filter.
                            if (isExpanded) {
                                expandedCountry = null
                            } else {
                                expandedCountry = country.id
                                onSelect(country.id)
                            }
                        },
                    )
                    if (isExpanded && expanded) {
                        country.children.forEach { child ->
                            SidebarRow(
                                label = child.label,
                                count = child.count,
                                icon = null,
                                active = selectedId == child.id,
                                expanded = true,
                                indent = 40.dp,
                                labelSize = 13.sp,
                                onClick = { onSelect(child.id) },
                            )
                        }
                    }
                }
            }
            if (tree.adult.categories.isNotEmpty()) {
                item { SectionHeader(tree.adult.label, expanded) }
                items(tree.adult.categories, key = { it.id }) { cat ->
                    SidebarRow(
                        label = cat.label,
                        count = cat.count,
                        icon = Icons.Filled.Lock,
                        active = selectedId == cat.id,
                        expanded = expanded,
                        onClick = { onSelect(cat.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchEntry(onClick: () -> Unit, expanded: Boolean) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) LiveColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) LiveColors.FocusBg else LiveColors.Panel)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown &&
                    (ev.key == Key.DirectionCenter || ev.key == Key.Enter)) {
                    onClick(); true
                } else false
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = "Search",
            tint = LiveColors.FgDim,
            modifier = Modifier.size(14.dp),
        )
        if (expanded) {
            Text(
                text = "Search",
                style = LiveType.CatLabel.copy(color = LiveColors.FgDim),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "/",
                style = LiveType.NumberMono.copy(color = LiveColors.FgMute),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionHeader(label: String, expanded: Boolean) {
    if (!expanded) {
        Spacer(Modifier.height(8.dp))
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 4.dp, start = 8.dp, end = 8.dp),
    ) {
        Text(
            text = label,
            style = LiveType.SectionTag.copy(color = LiveColors.FgMute),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SidebarRow(
    label: String,
    count: Int,
    icon: ImageVector?,
    active: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    flagEmoji: String? = null,
    leadingCode: String? = null,
    hasChildren: Boolean = false,
    isOpenGroup: Boolean = false,
    indent: androidx.compose.ui.unit.Dp = 0.dp,
    labelSize: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        active && focused -> LiveColors.FocusBg
        active -> LiveColors.FocusBg
        focused -> LiveColors.Panel
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LiveDims.SidebarRowHeight)
            .padding(start = indent),
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(LiveDims.ActiveIndicator)
                    .background(LiveColors.Accent),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(start = if (active) 12.dp else 10.dp, end = 12.dp)
                .onFocusChanged { focused = it.isFocused }
                .border(
                    width = if (focused) 3.dp else 0.dp,
                    color = if (focused) LiveColors.FocusRing else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                )
                .clip(RoundedCornerShape(8.dp))
                .background(if (focused) LiveColors.PanelRaised else bg)
                .focusable()
                .onKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown &&
                        (ev.key == Key.DirectionCenter || ev.key == Key.Enter)) {
                        onClick(); true
                    } else false
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                leadingCode != null -> Text(
                    text = leadingCode,
                    style = LiveType.NumberMono.copy(
                        color = if (active) LiveColors.Accent else LiveColors.FgMute,
                    ),
                    modifier = Modifier.width(20.dp),
                )
                flagEmoji != null -> Text(
                    text = flagEmoji,
                    style = LiveType.CatLabel.copy(fontSize = 14.sp),
                )
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (active) LiveColors.Accent else LiveColors.FgDim,
                    modifier = Modifier.size(14.dp),
                )
                else -> Spacer(Modifier.size(14.dp))
            }
            if (expanded) {
                Text(
                    text = label,
                    style = LiveType.CatLabel.copy(
                        color = if (active) LiveColors.Fg else LiveColors.FgDim,
                        fontSize = labelSize,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (count > 0) {
                    Text(
                        text = formatCount(count),
                        style = LiveType.NumberMono.copy(color = LiveColors.FgMute),
                    )
                }
                if (hasChildren) {
                    Icon(
                        imageVector = if (isOpenGroup)
                            Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = LiveColors.FgMute,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

private fun iconFor(cat: LiveCategory): ImageVector? = when (cat.iconToken) {
    CategoryIcon.Favorite -> Icons.Filled.Star
    CategoryIcon.Recent -> Icons.Filled.History
    CategoryIcon.All -> Icons.Filled.Apps
    CategoryIcon.Grid -> Icons.Filled.GridView
    CategoryIcon.Sport -> Icons.Filled.SportsSoccer
    CategoryIcon.Movie -> Icons.Filled.Movie
    CategoryIcon.News -> Icons.Filled.Newspaper
    CategoryIcon.Kids -> Icons.Filled.ChildCare
    CategoryIcon.Docs -> Icons.Filled.LibraryBooks
    CategoryIcon.Music -> Icons.Filled.LibraryMusic
    CategoryIcon.Lock -> Icons.Filled.Lock
    CategoryIcon.Country -> Icons.Filled.Public
    CategoryIcon.SubEntry -> null
}

/** Compact human count: `4821` → `4.8k`. */
fun formatCount(n: Int): String {
    if (n < 1000) return n.toString()
    val k = n / 1000.0
    return if (k < 10) String.format("%.1fk", k) else "${k.toInt()}k"
}

package com.arflix.tv.ui.screens.search

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.arflix.tv.R

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.Category
import com.arflix.tv.ui.components.LoadingIndicator
import com.arflix.tv.ui.components.CardLayoutMode
import com.arflix.tv.ui.components.AppTopBar
import com.arflix.tv.ui.components.AppTopBarContentTopInset
import com.arflix.tv.ui.components.MediaCard
import com.arflix.tv.ui.components.SidebarItem
import com.arflix.tv.ui.components.topBarFocusedItem
import com.arflix.tv.ui.components.topBarMaxIndex
import com.arflix.tv.ui.components.rememberCatalogueRowLayoutMode
import com.arflix.tv.ui.focus.arvioDpadFocusGroup
import com.arflix.tv.ui.skin.ArvioFocusableSurface
import com.arflix.tv.ui.skin.ArvioSkin
import com.arflix.tv.ui.skin.rememberArvioCardShape
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.BackgroundCard
import com.arflix.tv.ui.theme.appBackgroundDark
import com.arflix.tv.ui.theme.AccentGreen
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import com.arflix.tv.util.LocalDeviceType

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    currentProfile: com.arflix.tv.data.model.Profile? = null,
    onNavigateToDetails: (MediaType, Int) -> Unit = { _, _ -> },
    onNavigateToHome: () -> Unit = {},
    onNavigateToWatchlist: () -> Unit = {},
    onNavigateToTv: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val aiUsePosterCards = rememberCatalogueRowLayoutMode("search:ai") == CardLayoutMode.POSTER
    val configuration = LocalConfiguration.current
    val isCompactHeight = configuration.screenHeightDp <= 780
    val isTouchDevice = LocalDeviceType.current.isTouchDevice()
    val searchBarWidth = if (isTouchDevice) configuration.screenWidthDp.dp - 24.dp
        else (configuration.screenWidthDp.dp * 0.56f).coerceIn(500.dp, 760.dp)

    val hasSearchResults = uiState.movieResults.isNotEmpty() || uiState.tvResults.isNotEmpty()
    val hasAiResults = uiState.isAiSearch && uiState.aiResults.isNotEmpty()

    // Determine which categories to show in rows (filter out empty ones)
    val activeCategories: List<Category> = when {
        hasSearchResults -> {
            val list = mutableListOf<Category>()
            if (uiState.movieResults.isNotEmpty()) list.add(Category("s_m", "${stringResource(R.string.movies)} (${uiState.movieResults.size})", uiState.movieResults))
            if (uiState.tvResults.isNotEmpty()) list.add(Category("s_t", "${stringResource(R.string.tv_shows)} (${uiState.tvResults.size})", uiState.tvResults))
            list
        }
        uiState.query.isEmpty() -> uiState.discoverCategories.filter { it.items.isNotEmpty() }
        else -> emptyList()
    }
    val activeLogoUrls: Map<String, String> = when {
        hasSearchResults -> uiState.cardLogoUrls
        else -> uiState.discoverLogoUrls
    }

    var focusZone by remember { mutableStateOf(FocusZone.SEARCH_INPUT) }
    val hasProfile = currentProfile != null
    val maxSidebarIndex = topBarMaxIndex(hasProfile)
    var sidebarFocusIndex by remember { mutableIntStateOf(if (hasProfile) 1 else 0) }
    var isSearchInputFocused by remember { mutableStateOf(false) }
    var suppressSelectUntilMs by remember { mutableLongStateOf(0L) }
    val fastScrollThresholdMs = 220L

    // Manual row/item focus tracking (like HomeScreen)
    var currentRowIndex by remember { mutableIntStateOf(0) }
    var currentItemIndex by remember { mutableIntStateOf(0) }
    var resultsLastNavEventTime by remember { mutableLongStateOf(0L) }

    val searchFocusRequester = remember { FocusRequester() }
    val filtersFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // LaunchedEffect to restore RESULTS focus when results become available
    LaunchedEffect(activeCategories, hasAiResults) {
        if ((activeCategories.isNotEmpty() || hasAiResults) && focusZone == FocusZone.SEARCH_INPUT && isSearchInputFocused.not()) {
            // If we have results and just returned from details, stay in search input but prepare for results
            // This prevents the "back to keyboard" issue when returning from details
        }
    }
    
    LaunchedEffect(Unit) {
        // FocusRequester can throw IllegalStateException if the target composable
        // hasn't been placed yet (e.g. zero-sized keyboard on cold start, or when
        // the screen is composed then immediately navigated away). Swallow that
        // specific case so it doesn't surface to the user as a crash — TalkBack
        // focus will re-claim on next frame.
        runCatching { searchFocusRequester.requestFocus() }
        suppressSelectUntilMs = SystemClock.elapsedRealtime() + 150L
    }

    val showFilters = uiState.query.isEmpty()

    // D-pad handler: manages zone transitions. FILTERS zone lets native focus handle Left/Right.
    val dpadModifier = if (!isTouchDevice) {
        Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.Back, Key.Escape -> when (focusZone) {
                    FocusZone.RESULTS -> {
                        if (showFilters) focusZone = FocusZone.FILTERS
                        else { focusZone = FocusZone.SEARCH_INPUT; searchFocusRequester.requestFocus() }
                        true
                    }
                    FocusZone.FILTERS -> { focusZone = FocusZone.SEARCH_INPUT; searchFocusRequester.requestFocus(); true }
                    FocusZone.SEARCH_INPUT -> {
                        // Always progress toward sidebar so repeated Back presses can exit Search.
                        focusZone = FocusZone.SIDEBAR
                        true
                    }
                    FocusZone.SIDEBAR -> { onBack(); true }
                }
                Key.DirectionUp -> when (focusZone) {
                    FocusZone.SIDEBAR -> true
                    FocusZone.SEARCH_INPUT -> { focusZone = FocusZone.SIDEBAR; true }
                    FocusZone.FILTERS -> false // Let native focus handle up/down between 3 filter chip rows
                    FocusZone.RESULTS -> {
                        if (hasAiResults) false // AI grid: let native focus handle navigation
                        else if (currentRowIndex > 0) {
                            resultsLastNavEventTime = SystemClock.elapsedRealtime()
                            currentRowIndex--
                            currentItemIndex = 0
                            true
                        }
                        else if (showFilters) { focusZone = FocusZone.FILTERS; try { filtersFocusRequester.requestFocus() } catch (_: Exception) {}; true }
                        else { focusZone = FocusZone.SEARCH_INPUT; searchFocusRequester.requestFocus(); true }
                    }
                }
                Key.DirectionDown -> when (focusZone) {
                    FocusZone.SIDEBAR -> { focusZone = FocusZone.SEARCH_INPUT; searchFocusRequester.requestFocus(); true }
                    FocusZone.SEARCH_INPUT -> {
                        if (showFilters) { focusZone = FocusZone.FILTERS; try { filtersFocusRequester.requestFocus() } catch (_: Exception) {} }
                        else if (activeCategories.isNotEmpty() || hasAiResults) {
                            resultsLastNavEventTime = SystemClock.elapsedRealtime()
                            focusZone = FocusZone.RESULTS
                            currentRowIndex = 0
                            currentItemIndex = 0
                        }
                        true
                    }
                    FocusZone.FILTERS -> false // Let native focus handle up/down between 3 filter chip rows
                    FocusZone.RESULTS -> {
                        if (hasAiResults) false // AI grid: let native focus handle navigation
                        else if (currentRowIndex < activeCategories.size - 1) {
                            resultsLastNavEventTime = SystemClock.elapsedRealtime()
                            currentRowIndex++
                            currentItemIndex = 0
                            true
                        }
                        else true
                    }
                }
                Key.DirectionLeft -> when (focusZone) {
                    FocusZone.SIDEBAR -> { if (sidebarFocusIndex > 0) sidebarFocusIndex--; true }
                    FocusZone.RESULTS -> {
                        if (hasAiResults) false else {
                            if (currentItemIndex > 0) {
                                resultsLastNavEventTime = SystemClock.elapsedRealtime()
                                currentItemIndex--
                            }
                            true
                        }
                    }
                    FocusZone.FILTERS -> false
                    else -> false
                }
                Key.DirectionRight -> when (focusZone) {
                    FocusZone.SIDEBAR -> { if (sidebarFocusIndex < maxSidebarIndex) sidebarFocusIndex++; true }
                    FocusZone.RESULTS -> {
                        if (hasAiResults) false // AI grid: let native focus handle navigation
                        else {
                            val cats = activeCategories.filter { it.items.isNotEmpty() }
                            val maxItem = (cats.getOrNull(currentRowIndex)?.items?.size ?: 1) - 1
                            if (currentItemIndex < maxItem) {
                                resultsLastNavEventTime = SystemClock.elapsedRealtime()
                                currentItemIndex++
                            }
                            true
                        }
                    }
                    FocusZone.FILTERS -> false
                    else -> false
                }
                Key.Enter, Key.DirectionCenter -> {
                    when (focusZone) {
                        FocusZone.SIDEBAR -> {
                            if (hasProfile && sidebarFocusIndex == 0) onSwitchProfile()
                            else when (topBarFocusedItem(sidebarFocusIndex, hasProfile)) { SidebarItem.SEARCH -> Unit; SidebarItem.HOME -> onNavigateToHome(); SidebarItem.WATCHLIST -> onNavigateToWatchlist(); SidebarItem.TV -> onNavigateToTv(); SidebarItem.SETTINGS -> onNavigateToSettings(); null -> Unit }
                            true
                        }
                        FocusZone.SEARCH_INPUT -> false
                        FocusZone.FILTERS -> false
                        FocusZone.RESULTS -> {
                            if (hasAiResults) false
                            else {
                                // Use stable category lookup to avoid race condition with dynamic list updates
                                val cats = activeCategories.filter { it.items.isNotEmpty() }
                                val item = cats.getOrNull(currentRowIndex)?.items?.getOrNull(currentItemIndex)
                                if (item != null) onNavigateToDetails(item.mediaType, item.id)
                                true
                            }
                        }
                    }
                }
                else -> false
            }
        }
    } else Modifier

    Box(modifier = Modifier.fillMaxSize().background(appBackgroundDark()).then(dpadModifier)) {
        if (!isTouchDevice) AppTopBar(selectedItem = SidebarItem.SEARCH, isFocused = focusZone == FocusZone.SIDEBAR, focusedIndex = sidebarFocusIndex, profile = currentProfile)

        Column(modifier = Modifier.fillMaxSize().padding(top = if (isTouchDevice) 16.dp else AppTopBarContentTopInset).padding(horizontal = if (isTouchDevice) 12.dp else if (isCompactHeight) 20.dp else 28.dp)) {
            // ── Search Bar ──
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = if (isCompactHeight) 3.dp else 5.dp), contentAlignment = Alignment.Center) {
                if (isTouchDevice) {
                    OutlinedTextField(value = uiState.query, onValueChange = { viewModel.updateQuery(it) },
                        placeholder = { Text(stringResource(R.string.search), style = ArflixTypography.body, color = TextSecondary) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = if (isSearchInputFocused) Pink else TextSecondary, modifier = Modifier.size(22.dp)) },
                        textStyle = ArflixTypography.body.copy(color = TextPrimary), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { viewModel.search(); keyboardController?.hide() }),
                        colors = TextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Color.White, focusedContainerColor = BackgroundCard, unfocusedContainerColor = BackgroundCard, focusedIndicatorColor = Color.White, unfocusedIndicatorColor = Color.White.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(10.dp), modifier = Modifier.width(searchBarWidth).focusRequester(searchFocusRequester).onFocusChanged { isSearchInputFocused = it.isFocused })
                } else {
                    Row(modifier = Modifier.width(searchBarWidth).background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp)).border(if (isSearchInputFocused) 1.5.dp else 0.5.dp, if (isSearchInputFocused) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, null, tint = if (isSearchInputFocused) Color.White else TextSecondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        BasicTextField(value = uiState.query, onValueChange = { viewModel.updateQuery(it) },
                            textStyle = ArflixTypography.body.copy(color = TextPrimary, fontSize = 14.sp), cursorBrush = SolidColor(Color.White), singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { viewModel.search(); keyboardController?.hide() }),
                            modifier = Modifier.weight(1f).focusRequester(searchFocusRequester).onFocusChanged { s -> isSearchInputFocused = s.isFocused; if (s.isFocused) focusZone = FocusZone.SEARCH_INPUT },
                            decorationBox = { inner -> if (uiState.query.isEmpty()) Text(stringResource(R.string.search), style = ArflixTypography.body.copy(fontSize = 14.sp), color = Color.White.copy(alpha = 0.25f)); inner() })
                    }
                }
            }

            // ── Filter Chips (discover mode) - focusable with D-pad ──
            if (showFilters) {
                Column(modifier = Modifier
                    .focusRequester(filtersFocusRequester)
                    .onFocusChanged { state ->
                        if (state.hasFocus) focusZone = FocusZone.FILTERS
                        else if (focusZone == FocusZone.FILTERS && !state.hasFocus) {
                            // Focus left filters - if search bar got focus, zone will update via its onFocusChanged
                            // If nothing else caught it, transition to rows
                            if (!isSearchInputFocused && activeCategories.isNotEmpty()) {
                                focusZone = FocusZone.RESULTS
                                currentRowIndex = 0; currentItemIndex = 0
                            }
                        }
                    }
                ) {
                    LazyRow(modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp).arvioDpadFocusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        items(DiscoverType.entries.size, key = { DiscoverType.entries[it].name }) { i -> val t = DiscoverType.entries[i]; GlowChip(t.label, uiState.selectedType == t) { viewModel.selectType(t) } }
                    }
                    LazyRow(modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp).arvioDpadFocusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        val genres = viewModel.getGenresForType()
                        item(key = "all_g") { GlowChip(stringResource(R.string.all_genres), uiState.selectedGenre == null) { viewModel.selectGenre(null) } }
                        items(genres.size, key = { "g_${genres[it].id}" }) { i -> GlowChip(genres[i].name, uiState.selectedGenre == genres[i]) { viewModel.selectGenre(genres[i]) } }
                    }
                    LazyRow(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).arvioDpadFocusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        item(key = "any_l") { GlowChip(stringResource(R.string.any_language), uiState.selectedCountry == null) { viewModel.selectCountry(null) } }
                        items(COUNTRIES.size, key = { "c_${COUNTRIES[it].code}" }) { i -> GlowChip(COUNTRIES[i].name, uiState.selectedCountry == COUNTRIES[i]) { viewModel.selectCountry(COUNTRIES[i]) } }
                    }
                }
            }

            // ── Content ──
            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator(color = Pink, size = 48.dp) }

                hasAiResults -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                        Icon(Icons.Default.AutoAwesome, null, tint = AccentGreen, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text(uiState.aiInterpretation ?: "", style = ArflixTypography.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = Color.White.copy(alpha = 0.85f))
                    }
                    ContentGrid(items = uiState.aiResults, usePosterCards = aiUsePosterCards, isLoading = false, isTouchDevice = isTouchDevice, onItemClick = { onNavigateToDetails(it.mediaType, it.id) }, onLoadMore = {})
                }

                uiState.query.isNotEmpty() && !uiState.isAiSearch && !hasSearchResults -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("${stringResource(R.string.no_results_for)} \"${uiState.query}\"", style = ArflixTypography.body, color = TextSecondary) }
                }

                uiState.isDiscoverLoading && activeCategories.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator(color = Pink, size = 48.dp) }

                activeCategories.isNotEmpty() -> {
                    // Row-based content (discover rows or search results) - HomeScreen pattern
                    RowsLayer(
                        categories = activeCategories,
                        cardLogoUrls = activeLogoUrls,
                        currentRowIndex = currentRowIndex,
                        currentItemIndex = currentItemIndex,
                        lastNavEventTime = resultsLastNavEventTime,
                        fastScrollThresholdMs = fastScrollThresholdMs,
                        isFocused = focusZone == FocusZone.RESULTS,
                        isTouchDevice = isTouchDevice,
                        onItemClick = { onNavigateToDetails(it.mediaType, it.id) }
                    )
                }
            }
        }

    }
}

// ── Glow Chip ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GlowChip(label: String, isSelected: Boolean, onSelect: () -> Unit) {
    val chipShape = rememberArvioCardShape(6.dp)
    ArvioFocusableSurface(
        modifier = Modifier.padding(vertical = 1.dp),
        shape = chipShape,
        backgroundColor = if (isSelected) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.03f),
        outlineColor = ArvioSkin.colors.focusOutline,
        outlineWidth = 2.5.dp,
        focusedScale = 1.05f,
        pressedScale = 0.97f,
        enableSystemFocus = true,
        onClick = onSelect,
    ) { isFocused ->
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) {
            Text(label, style = ArflixTypography.caption.copy(fontSize = 12.sp, fontWeight = if (isSelected || isFocused) FontWeight.SemiBold else FontWeight.Normal),
                color = if (isFocused) Color.White else if (isSelected) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.5f))
        }
    }
}

// ── Rows Layer (HomeScreen pattern - manual focus, smooth scroll) ────────────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RowsLayer(
    categories: List<Category>, cardLogoUrls: Map<String, String>,
    currentRowIndex: Int, currentItemIndex: Int,
    lastNavEventTime: Long,
    fastScrollThresholdMs: Long,
    isFocused: Boolean,
    isTouchDevice: Boolean,
    onItemClick: (MediaItem) -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeight = configuration.screenHeightDp

    val focusBleedPadding = if (isTouchDevice) 14.dp else 22.dp

    val listState = rememberLazyListState()
    var lastAppliedTargetIndex by remember { mutableIntStateOf(-1) }
    val targetIndex = currentRowIndex.coerceIn(0, (categories.size - 1).coerceAtLeast(0))

    // Only move the results viewport in response to actual D-pad navigation.
    // Search result rows update frequently while typing/loading, and snapping the
    // LazyColumn on every target change makes the screen feel unstable.
    LaunchedEffect(targetIndex, lastNavEventTime) {
        val currentFirst = listState.firstVisibleItemIndex
        val initialPlacement = lastAppliedTargetIndex < 0
        if (currentFirst == targetIndex) {
            lastAppliedTargetIndex = targetIndex
            return@LaunchedEffect
        }

        val recentUserNav = lastNavEventTime > 0L &&
            (SystemClock.elapsedRealtime() - lastNavEventTime) <= fastScrollThresholdMs
        if (!initialPlacement && !recentUserNav) return@LaunchedEffect

        val jump = kotlin.math.abs(targetIndex - currentFirst)
        if (!initialPlacement && jump <= 5) {
            listState.animateScrollToItem(targetIndex)
        } else {
            listState.scrollToItem(targetIndex)
        }
        lastAppliedTargetIndex = targetIndex
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = focusBleedPadding / 2, bottom = maxHeight * 0.6f),
            modifier = Modifier.fillMaxSize().arvioDpadFocusGroup(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(categories.size, key = { categories[it].id }) { index ->
                val category = categories[index]
                val isCurrentRow = isFocused && index == currentRowIndex
                val rowKey = remember(category.id) { "search:${category.id}" }
                val rowUsePosterCards = rememberCatalogueRowLayoutMode(rowKey) == CardLayoutMode.POSTER
                val itemWidth = if (isTouchDevice) {
                    if (rowUsePosterCards) 110.dp else 170.dp
                } else {
                    if (rowUsePosterCards) 134.dp else 260.dp
                }
                val baseRowHeight = if (isTouchDevice) {
                    if (rowUsePosterCards) 220.dp else 160.dp
                } else if (rowUsePosterCards) {
                    if (screenHeight <= 640) 245.dp else 320.dp
                } else {
                    if (screenHeight <= 640) 200.dp else 260.dp
                }
                val rowHeight = baseRowHeight + focusBleedPadding
                // Fade non-current rows
                val rowAlpha by animateFloatAsState(
                    targetValue = if (!isFocused || index <= currentRowIndex) 1f else 0.3f,
                    animationSpec = tween(250), label = "rowAlpha"
                )

                Box(modifier = Modifier.fillMaxWidth().height(rowHeight).graphicsLayer { alpha = rowAlpha }) {
                    Column {
                        Row(
                            modifier = Modifier.padding(start = focusBleedPadding, bottom = 8.dp, top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                category.title,
                                style = ArvioSkin.typography.sectionTitle.copy(fontSize = 15.sp),
                                color = Color.White.copy(alpha = if (isCurrentRow) 0.9f else 0.5f)
                            )
                        }

                        val rowState = rememberLazyListState()
                        var lastScrollIndex by remember(category.id) { mutableIntStateOf(-1) }
                        var lastScrollOffset by remember(category.id) { mutableIntStateOf(Int.MIN_VALUE) }
                        // Scroll to focused item in current row
                        LaunchedEffect(isCurrentRow, currentItemIndex, lastNavEventTime) {
                            if (!isCurrentRow) return@LaunchedEffect
                            val safeIndex = currentItemIndex.coerceIn(0, (category.items.size - 1).coerceAtLeast(0))
                            val first = rowState.firstVisibleItemIndex
                            val visibleItems = rowState.layoutInfo.visibleItemsInfo
                            val last = visibleItems.lastOrNull()?.index ?: first
                            val targetInfo = visibleItems.firstOrNull { it.index == safeIndex }
                            val targetOutsideViewport = safeIndex < first || safeIndex > last
                            val viewportEnd = rowState.layoutInfo.viewportEndOffset
                            val trailingPaddingPx = rowState.layoutInfo.afterContentPadding
                            val targetNearViewportEnd = targetInfo != null &&
                                targetInfo.offset + targetInfo.size > viewportEnd - trailingPaddingPx
                            val scrollTargetIndex = safeIndex
                            val extraOffset = if (targetNearViewportEnd) {
                                (with(density) { itemWidth.roundToPx() } * 0.35f).toInt()
                            } else 0

                            if (lastScrollIndex == scrollTargetIndex && lastScrollOffset == extraOffset) {
                                return@LaunchedEffect
                            }
                            if (lastScrollIndex == -1) {
                                rowState.scrollToItem(index = scrollTargetIndex, scrollOffset = extraOffset)
                                lastScrollIndex = scrollTargetIndex
                                lastScrollOffset = extraOffset
                                return@LaunchedEffect
                            }

                            val recentUserNav = lastNavEventTime > 0L &&
                                (SystemClock.elapsedRealtime() - lastNavEventTime) <= fastScrollThresholdMs
                            if (!recentUserNav) return@LaunchedEffect

                            val jumpDistance = kotlin.math.abs(scrollTargetIndex - first)
                            if (jumpDistance > 6) {
                                rowState.scrollToItem(index = scrollTargetIndex, scrollOffset = extraOffset)
                            } else if (scrollTargetIndex != first || targetOutsideViewport) {
                                rowState.animateScrollToItem(index = scrollTargetIndex, scrollOffset = extraOffset)
                            } else {
                                rowState.scrollToItem(index = scrollTargetIndex, scrollOffset = extraOffset)
                            }
                            lastScrollIndex = scrollTargetIndex
                            lastScrollOffset = extraOffset
                        }

                        LazyRow(
                            state = rowState,
                            modifier = Modifier.arvioDpadFocusGroup(),
                            contentPadding = PaddingValues(
                                start = focusBleedPadding,
                                end = itemWidth + 56.dp,
                                top = focusBleedPadding,
                                bottom = focusBleedPadding + 12.dp
                            ),
                            horizontalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            itemsIndexed(category.items, key = { _, item -> "${item.mediaType}_${item.id}" }) { itemIdx, item ->
                                val itemIsFocused = isCurrentRow && itemIdx == currentItemIndex
                                MediaCard(
                                    item = item.copy(title = buildCardTitle(item), subtitle = buildCardSubtitle(item)),
                                    width = itemWidth,
                                    isLandscape = !rowUsePosterCards,
                                    logoImageUrl = cardLogoUrls["${item.mediaType}_${item.id}"],
                                    showProgress = false,
                                    titleMaxLines = 2,
                                    subtitleMaxLines = 1,
                                    isFocusedOverride = itemIsFocused,
                                    enableSystemFocus = false,
                                    onFocused = {},
                                    onClick = { onItemClick(item) },
                                    modifier = if (isTouchDevice) Modifier.clickable { onItemClick(item) } else Modifier
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Content Grid (AI results) ───────────────────────────────────────────────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ContentGrid(items: List<MediaItem>, usePosterCards: Boolean, isLoading: Boolean, isTouchDevice: Boolean, onItemClick: (MediaItem) -> Unit, onLoadMore: () -> Unit) {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val itemWidth = if (usePosterCards) 134.dp else 260.dp
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState.firstVisibleItemIndex, items.size) { val lv = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0; if (items.isNotEmpty() && lv >= items.size - 8) onLoadMore() }

    val focusBleedPadding = if (isTouchDevice) 14.dp else 24.dp
    LazyVerticalGrid(state = gridState, columns = GridCells.Adaptive(minSize = itemWidth + focusBleedPadding), contentPadding = PaddingValues(horizontal = focusBleedPadding, vertical = focusBleedPadding),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(26.dp), modifier = Modifier.fillMaxSize().arvioDpadFocusGroup()) {
        items(items.size, key = { "${items[it].mediaType}_${items[it].id}" }) { idx ->
            val item = items[idx]
            MediaCard(item = item.copy(title = buildCardTitle(item), subtitle = buildCardSubtitle(item)),
                width = itemWidth, isLandscape = !usePosterCards, showProgress = false, titleMaxLines = 2, subtitleMaxLines = 1,
                isFocusedOverride = false, enableSystemFocus = true, onFocused = {}, onClick = { onItemClick(item) },
                modifier = if (isTouchDevice) Modifier.clickable { onItemClick(item) } else Modifier)
        }
        if (isLoading) { item { Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { LoadingIndicator(color = Pink, size = 32.dp) } } }
    }
}

private fun buildCardTitle(item: MediaItem): String {
    val year = item.year.takeIf { it.isNotBlank() }
    return if (year != null) "${item.title} ($year)" else item.title
}

@Composable
private fun buildCardSubtitle(item: MediaItem): String {
    return when (item.mediaType) { MediaType.TV -> stringResource(R.string.series); MediaType.MOVIE -> stringResource(R.string.movie) }
}

private enum class FocusZone { SIDEBAR, SEARCH_INPUT, FILTERS, RESULTS }

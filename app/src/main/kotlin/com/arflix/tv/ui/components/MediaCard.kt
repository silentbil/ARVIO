package com.arflix.tv.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.arflix.tv.data.model.CollectionGroupKind
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.ui.skin.ArvioFocusableSurface
import com.arflix.tv.ui.skin.ArvioSkin
import com.arflix.tv.ui.skin.rememberArvioCardShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex

/**
 * Media card component for rows/grids.
 * Arctic Fuse 2 style:
 * - Large landscape cards with solid pink/magenta focus border
 * - Transform-based focus (graphicsLayer) via `ArvioFocusableSurface`
 * - No layout size changes on focus (no width/height scaling)
 * - Uses `ArvioSkin` for consistent styling
 */

private val missingArtworkBrush = Brush.linearGradient(
    colors = listOf(
        Color(0xFF1F2333),
        Color(0xFF0D0D14)
    )
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MediaCard(
    item: MediaItem,
    width: Dp = 280.dp,  // Arctic Fuse 2: larger cards by default
    isLandscape: Boolean = true,
    logoImageUrl: String? = null,
    // Collection tiles supply a separate focus-state image (animated GIF)
    // that swaps in when the card is focused, so the row stays static
    // when idle and only the hovered tile animates.
    focusImageUrl: String? = null,
    showProgress: Boolean = false,
    showTitle: Boolean = true,
    titleMaxLines: Int = 1,
    subtitleMaxLines: Int = 1,
    isFocusedOverride: Boolean = false,
    enableSystemFocus: Boolean = true,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // If this is a placeholder card, show skeleton only
    if (item.isPlaceholder) {
        PlaceholderCard(
            width = width,
            isLandscape = isLandscape,
            modifier = modifier
        )
        return
    }

    var isFocused by remember { mutableStateOf(false) }
    val visualFocused = isFocusedOverride || isFocused

    val aspectRatio = if (isLandscape) 16f / 9f else 2f / 3f
    // Plex-like behavior: landscape cards should prefer wide artwork/backdrops.
    // Poster art is portrait and looks cropped in 16:9 cards, so only use it as fallback.
    // Guard empty/blank URLs — TMDB items with no posterPath AND no backdropPath
    // produce image="" in toMediaItem(). Passing "" to Coil throws
    // "Unable to create a fetcher that supports: " and the card stays dark forever.
    // With this guard, cards with no image simply show the surface background color.
    // Collection tiles (services/franchises/genres) encode the focus-state
    // image as `item.backdrop` and the static cover as `item.image`. Swap
    // between them based on focus so the row is calm while idle and only the
    // hovered tile animates its GIF. Regular media items keep the existing
    // behavior (landscape uses backdrop art, poster uses image).
    val isCollectionTile = item.status?.startsWith("collection:") == true
    val baseImageUrl = if (isCollectionTile) {
        item.image.takeIf { it.isNotBlank() } ?: item.backdrop?.takeIf { it.isNotBlank() }
    } else if (isLandscape) {
        (item.backdrop ?: item.image).takeIf { it.isNotBlank() }
    } else {
        item.image.takeIf { it.isNotBlank() }
    }
    val explicitFocusUrl = focusImageUrl?.takeIf { it.isNotBlank() }
    val collectionFocusUrl = if (isCollectionTile) {
        item.backdrop?.takeIf { it.isNotBlank() && it != item.image }
    } else null
    val showCollectionTitleOverlay = isCollectionTile && showTitle
    val isGenreCollectionTile = item.collectionGroup == CollectionGroupKind.GENRE
    val rawImageUrl = if (visualFocused) {
        explicitFocusUrl ?: collectionFocusUrl ?: baseImageUrl
    } else {
        baseImageUrl
    }
    val shape = rememberArvioCardShape(ArvioSkin.radius.md)

    val showFocusOutline = visualFocused
    val jumpBorderWidth = if (showFocusOutline) 2.5.dp else 0.dp

    val context = LocalContext.current
    val density = LocalDensity.current
    val overlayBrush: Brush? = null  // Gradient removed per user feedback
    // Performance: Removed context/density from keys - they're stable CompositionLocals
    val imageRequest = remember(rawImageUrl, width, aspectRatio) {
        if (rawImageUrl == null) return@remember null
        val widthPx = with(density) { width.roundToPx() }
        val heightPx = (widthPx / aspectRatio).toInt().coerceAtLeast(1)
        ImageRequest.Builder(context)
            .data(rawImageUrl)
            .size(widthPx, heightPx)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            .build()
    }
    // Performance: Removed context/density from keys
    val logoRequest = remember(logoImageUrl) {
        val logoWidthPx = with(density) { 220.dp.roundToPx() }.coerceAtLeast(1)
        val logoHeightPx = with(density) { 64.dp.roundToPx() }.coerceAtLeast(1)
        if (logoImageUrl.isNullOrBlank()) {
            null
        } else {
            ImageRequest.Builder(context)
                .data(logoImageUrl)
                .size(logoWidthPx, logoHeightPx)
                .precision(Precision.INEXACT)
                .allowHardware(true)
                // Uses global crossfade(200) from ImageLoader
                .build()
        }
    }

    Column(
        modifier = modifier
            .width(width)
            .zIndex(if (visualFocused) 1f else 0f)
    ) {
        ArvioFocusableSurface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio),
            shape = shape,
            backgroundColor = ArvioSkin.colors.surface,
            outlineColor = ArvioSkin.colors.focusOutline,
            outlineWidth = jumpBorderWidth,
            focusedScale = 1.05f,
            pressedScale = 0.97f,
            focusedTransformOriginX = 0.5f,
            enableSystemFocus = enableSystemFocus,
            isFocusedOverride = isFocusedOverride,
            onClick = onClick,
            onLongClick = onLongClick,
            onFocusChanged = {
                isFocused = it
                if (it) onFocused()
            },
        ) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {
                // Only render AsyncImage when we have a valid image URL.
                // When imageRequest is null (no poster/backdrop from TMDB),
                // render a branded gradient fallback with the title centered
                // so the card conveys what it's for instead of showing as a
                // blank rectangle that used to look broken.
                if (imageRequest != null) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ArvioSkin.colors.surface),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(missingArtworkBrush),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.title,
                            style = ArvioSkin.typography.cardTitle,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        )
                    }
                }
                if (overlayBrush != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(overlayBrush)
                    )
                }

                if (showCollectionTitleOverlay) {
                    Box(
                        modifier = Modifier
                            .align(if (isGenreCollectionTile) Alignment.BottomStart else Alignment.TopStart)
                            .padding(
                                start = 12.dp,
                                end = 12.dp,
                                top = if (isGenreCollectionTile) 12.dp else 12.dp,
                                bottom = if (isGenreCollectionTile) 14.dp else 12.dp
                            )
                            .clip(RoundedCornerShape(if (isGenreCollectionTile) 12.dp else 10.dp))
                            .background(
                                Color.Black.copy(alpha = if (visualFocused) {
                                    if (isGenreCollectionTile) 0.72f else 0.62f
                                } else {
                                    if (isGenreCollectionTile) 0.58f else 0.52f
                                })
                            )
                            .border(
                                width = if (visualFocused) {
                                    if (isGenreCollectionTile) 1.dp else 1.5.dp
                                } else {
                                    1.dp
                                },
                                color = Color.White.copy(alpha = if (visualFocused) 0.9f else 0.28f),
                                shape = RoundedCornerShape(if (isGenreCollectionTile) 12.dp else 10.dp)
                            )
                            .padding(
                                horizontal = if (isGenreCollectionTile) 12.dp else 10.dp,
                                vertical = if (isGenreCollectionTile) 8.dp else 7.dp
                            )
                    ) {
                        Text(
                            text = item.title,
                            style = ArvioSkin.typography.cardTitle.copy(
                                fontSize = when {
                                    isGenreCollectionTile && isLandscape -> 18.sp
                                    isLandscape -> 15.sp
                                    else -> 14.sp
                                }
                            ),
                            color = Color.White.copy(alpha = 0.98f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Official logo/art overlay is only used on landscape cards.
                // Poster layout should stay image-first on TV, otherwise the
                // clearlogo crowds the poster art and looks double-stamped.
                // Collection tiles already embed their own branding, so they
                // stay logo-free in both layouts.
                if (logoRequest != null && isLandscape && !isCollectionTile) {
                    AsyncImage(
                        model = logoRequest,
                        contentDescription = "${item.title} logo",
                        contentScale = ContentScale.Fit,
                        alignment = if (isLandscape) Alignment.BottomStart else Alignment.BottomCenter,
                        modifier = Modifier
                            .align(if (isLandscape) Alignment.BottomStart else Alignment.BottomCenter)
                            .fillMaxWidth(if (isLandscape) 0.52f else 0.74f)
                            .height(if (isLandscape) 48.dp else 42.dp)
                            .padding(
                                start = if (isLandscape) 10.dp else 0.dp,
                                end = if (isLandscape) 0.dp else 8.dp,
                                bottom = if (isLandscape) 18.dp else 12.dp
                            )
                    )
                }

                // Subtle green watched badge
                if (item.isWatched) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                        .padding(bottom = 6.dp, end = 6.dp)
                        .size(14.dp)
                        .background(
                            color = ArvioSkin.colors.watchedGreen.copy(alpha = 0.2f),
                            shape = CircleShape
                        )
                        .border(
                            width = 1.dp,
                            color = ArvioSkin.colors.watchedGreen,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = ArvioSkin.colors.watchedGreen,
                        modifier = Modifier.size(8.dp)
                    )
                }
                }

                // Subtle progress bar for Continue Watching
                if (showProgress && !item.isWatched && item.progress in 1..94) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.26f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(item.progress / 100f)
                                .fillMaxSize()
                                .background(Color.White.copy(alpha = 0.92f))
                        )
                    }
                }
            }
        }

        if (showTitle && !showCollectionTitleOverlay) {
            Spacer(modifier = Modifier.height(ArvioSkin.spacing.x2))

            Text(
                text = item.title,
                style = ArvioSkin.typography.cardTitle,
                color = if (visualFocused) {
                    ArvioSkin.colors.textPrimary
                } else {
                    ArvioSkin.colors.textPrimary.copy(alpha = 0.85f)
                },
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )

            // Prefer release date (or year) under the title. Fall back to the
            // explicit subtitle or media-type label only when neither is set.
            val subtitle = remember(item.subtitle, item.releaseDate, item.year, item.mediaType) {
                val release = item.releaseDate?.takeIf { it.isNotBlank() }
                    ?: item.year.takeIf { it.isNotBlank() }
                release
                    ?: item.subtitle.ifBlank {
                        when (item.mediaType) {
                            MediaType.TV -> "TV Series"
                            MediaType.MOVIE -> "Movie"
                            else -> "Media"
                        }
                    }
            }
            Text(
                text = subtitle,
                style = ArvioSkin.typography.caption,
                color = ArvioSkin.colors.textMuted.copy(alpha = 0.85f),
                maxLines = subtitleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Placeholder card shown while Continue Watching data loads.
 * Displays a skeleton animation to indicate loading state.
 */
@Composable
private fun PlaceholderCard(
    width: Dp,
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    val aspectRatio = if (isLandscape) 16f / 9f else 2f / 3f
    val shape = rememberArvioCardShape(ArvioSkin.radius.md)

    Column(
        modifier = modifier.width(width)
    ) {
        // Card skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(shape)
        ) {
            SkeletonBox(
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(ArvioSkin.spacing.x2))

        // Title skeleton
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(14.dp)
                .clip(rememberArvioCardShape(ArvioSkin.radius.sm))
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Subtitle skeleton
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(12.dp)
                .clip(rememberArvioCardShape(ArvioSkin.radius.sm))
        )
    }
}

/**
 * Poster-style media card (portrait orientation).
 * Phase 5: Added proper image sizing and shimmer placeholder.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterCard(
    item: MediaItem,
    width: Dp = 140.dp,
    isFocusedOverride: Boolean = false,
    enableSystemFocus: Boolean = true,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    useWhiteBorder: Boolean = true,
) {
    var isFocused by remember { mutableStateOf(false) }
    val visualFocused = isFocusedOverride || isFocused

    val shape = rememberArvioCardShape(ArvioSkin.radius.md)
    val outlineColor = if (useWhiteBorder) ArvioSkin.colors.focusOutline else ArvioSkin.colors.accent

    val context = LocalContext.current
    val density = LocalDensity.current
    val aspectRatio = 2f / 3f
    val posterUrl = item.image.takeIf { it.isNotBlank() }
    // Performance: Removed context/density from keys
    val imageRequest = remember(posterUrl, width) {
        if (posterUrl == null) return@remember null
        val widthPx = with(density) { width.roundToPx() }
        val heightPx = (widthPx / aspectRatio).toInt().coerceAtLeast(1)
        ImageRequest.Builder(context)
            .data(posterUrl)
            .size(widthPx, heightPx)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            // Uses global crossfade(200) from ImageLoader
            .build()
    }

    Column(modifier = modifier.width(width)) {
        ArvioFocusableSurface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio),
            shape = shape,
            backgroundColor = ArvioSkin.colors.surface,
            outlineColor = outlineColor,
            enableSystemFocus = enableSystemFocus,
            isFocusedOverride = isFocusedOverride,
            onClick = onClick,
            onLongClick = onLongClick,
            onFocusChanged = {
                isFocused = it
                if (it) onFocused()
            },
        ) { _ ->
            if (imageRequest != null) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ArvioSkin.colors.surface),
                )
            }
        }

        // Only show title and year when focused
        if (visualFocused) {
            Spacer(modifier = Modifier.height(ArvioSkin.spacing.x1))

            Text(
                text = item.title,
                style = ArvioSkin.typography.caption,
                color = ArvioSkin.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (item.year.isNotBlank()) {
                Text(
                    text = item.year,
                    style = ArvioSkin.typography.caption,
                    color = ArvioSkin.colors.textMuted.copy(alpha = 0.65f),
                )
            }
        }
    }
}

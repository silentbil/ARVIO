package com.arflix.tv.ui.skin

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.arvioFocusable(
    enabled: Boolean = true,
    enableSystemFocus: Boolean = true,
    isFocusedOverride: Boolean = false,
    shape: Shape,
    focusedScale: Float,
    pressedScale: Float,
    outlineWidth: Dp,
    @Suppress("UNUSED_PARAMETER") glowWidth: Dp,
    @Suppress("UNUSED_PARAMETER") glowAlpha: Float,
    outlineColor: Color,
    focusedTransformOriginX: Float = 0.5f,
    useGradientBorder: Boolean = false,  // Arctic Fuse 2: SOLID border, not gradient
    gradientStartColor: Color = Color(0xFFFF00FF),  // Magenta (unused when solid)
    gradientEndColor: Color = Color(0xFF00D4FF),    // Cyan (unused when solid)
    animateFocus: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onFocusChanged: (Boolean) -> Unit = {},
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    var isFocused by remember { mutableStateOf(false) }
    val visualFocused = isFocusedOverride || isFocused
    val targetScale = when {
        isPressed -> pressedScale
        visualFocused -> focusedScale
        else -> 1f
    }

    val tokens = ArvioSkin.focus
    val focusScaleDurationMs = if (animateFocus) 105 else 72
    val focusAlphaDurationMs = if (animateFocus) 120 else 82

    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = focusScaleDurationMs, easing = tokens.easing),
        label = "arvio_focus_scale",
    )

    // Animate alpha for smooth border fade in/out
    val highlightAlpha by animateFloatAsState(
        targetValue = if (visualFocused) 1f else 0f,
        animationSpec = tween(durationMillis = focusAlphaDurationMs, easing = tokens.easing),
        label = "arvio_focus_alpha",
    )

    val originX = if (visualFocused) focusedTransformOriginX.coerceIn(0f, 1f) else 0.5f
    val focusTransformOrigin = TransformOrigin(originX, 0.5f)

    val clickable = if (onClick != null && onLongClick != null) {
        Modifier.combinedClickable(
            enabled = enabled,
            role = Role.Button,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else if (onClick != null) {
        Modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
    } else {
        Modifier
    }

    val focusModifier = if (enableSystemFocus) {
        Modifier.onFocusChanged { state ->
            val focusedNow = state.isFocused
            if (focusedNow != isFocused) {
                isFocused = focusedNow
                onFocusChanged(focusedNow)
            }
        }
    } else {
        Modifier
    }

    val systemFocusable = if (enableSystemFocus) {
        Modifier.focusable(enabled = enabled)
    } else {
        Modifier
    }

    // Always apply graphicsLayer and drawWithContent — toggling modifier
    // structure between frames forces Compose to diff different chains,
    // causing micro-jank on the focus/unfocus edge. The GPU skips identity
    // transforms (scale=1) for free, so the always-on approach has zero
    // cost when unfocused.
    val layerModifier = Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
        transformOrigin = focusTransformOrigin
    }

    val borderModifier = Modifier.drawWithContent {
        drawContent()
        if (highlightAlpha > 0f) {
            val outline = shape.createOutline(size, layoutDirection, this)
            val borderWidth = outlineWidth.toPx()
            val ringColor = outlineColor.copy(alpha = highlightAlpha)
            val glowColor = outlineColor.copy(alpha = highlightAlpha * 0.4f)

            when (outline) {
                is Outline.Rounded -> {
                    val path = Path().apply { addRoundRect(outline.roundRect) }
                    // Draw outer glow (softer, larger stroke)
                    drawPath(path, glowColor, style = Stroke(width = borderWidth + 2.dp.toPx()))
                    // Draw inner bright border
                    drawPath(path, ringColor, style = Stroke(width = borderWidth))
                }
                is Outline.Rectangle -> {
                    // Draw outer glow (softer, larger stroke)
                    drawRect(color = glowColor, style = Stroke(width = borderWidth + 2.dp.toPx()))
                    // Draw inner bright border
                    drawRect(color = ringColor, style = Stroke(width = borderWidth))
                }
                is Outline.Generic -> {
                    // Draw outer glow (softer, larger stroke)
                    drawPath(path = outline.path, color = glowColor, style = Stroke(width = borderWidth + 2.dp.toPx()))
                    // Draw inner bright border
                    drawPath(path = outline.path, color = ringColor, style = Stroke(width = borderWidth))
                }
            }
        }
    }

    this
        .then(focusModifier)
        .then(layerModifier)
        .then(borderModifier)
        .then(clickable)
        .then(systemFocusable)
}

@Composable
fun ArvioFocusableSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    backgroundColor: Color = ArvioSkin.colors.surface,
    focusedScale: Float = ArvioSkin.focus.scaleFocused,
    pressedScale: Float = ArvioSkin.focus.scalePressed,
    outlineWidth: Dp = ArvioSkin.focus.outlineWidth,
    glowWidth: Dp = ArvioSkin.focus.glowWidth,
    glowAlpha: Float = ArvioSkin.focus.glowAlpha,
    outlineColor: Color = ArvioSkin.colors.focusOutline,
    focusedTransformOriginX: Float = 0.5f,
    useGradientBorder: Boolean = false,  // Arctic Fuse 2: SOLID border, not gradient
    gradientStartColor: Color = ArvioSkin.colors.focusGradientStart,
    gradientEndColor: Color = ArvioSkin.colors.focusGradientEnd,
    animateFocus: Boolean = true,
    enabled: Boolean = true,
    enableSystemFocus: Boolean = true,
    isFocusedOverride: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onFocusChanged: (Boolean) -> Unit = {},
    content: @Composable BoxScope.(isFocused: Boolean) -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val visualFocused = isFocusedOverride || isFocused

    Box(
        modifier = modifier
            .arvioFocusable(
                enabled = enabled,
                enableSystemFocus = enableSystemFocus,
                isFocusedOverride = isFocusedOverride,
                shape = shape,
                focusedScale = focusedScale,
                pressedScale = pressedScale,
                outlineWidth = outlineWidth,
                glowWidth = glowWidth,
                glowAlpha = glowAlpha,
                outlineColor = outlineColor,
                focusedTransformOriginX = focusedTransformOriginX,
                useGradientBorder = useGradientBorder,
                gradientStartColor = gradientStartColor,
                gradientEndColor = gradientEndColor,
                animateFocus = animateFocus,
                onClick = onClick,
                onLongClick = onLongClick,
                onFocusChanged = {
                    isFocused = it
                    onFocusChanged(it)
                },
            )
            .clip(shape)
            .background(backgroundColor),
    ) {
        content(visualFocused)
    }
}

@Composable
fun rememberArvioCardShape(cornerRadius: Dp = ArvioSkin.radius.md): Shape {
    return remember(cornerRadius) {
        androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius)
    }
}

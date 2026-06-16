package com.example.dink_smb_player.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Three-layer focus ring from design README §5.
//   inner: 4dp solid accent at 90% alpha
//   outer: 4dp accent at 25% alpha
//   glow:  60dp drop shadow at 30% alpha, 20dp y-offset
// Default shape lets the caller pass the same RoundedCornerShape the surface uses.
fun Modifier.dinkFocus(
    focused: Boolean,
    shape: Shape,
    accent: Color,
): Modifier = if (!focused) this else this
    .drawBehind {
        // Soft glow drawn first so the borders sit on top.
        val glowAlpha = 0.30f
        val glowRadius = 60.dp.toPx()
        val glowOffsetY = 20.dp.toPx()
        // Cheap approximation of the box-shadow: a translated, expanded color blur via repeated strokes.
        val steps = 8
        for (i in 1..steps) {
            val expand = glowRadius * (i / steps.toFloat())
            drawRect(
                color = accent.copy(alpha = glowAlpha / steps),
                topLeft = Offset(-expand, -expand + glowOffsetY),
                size = Size(size.width + expand * 2, size.height + expand * 2),
                style = Stroke(width = expand),
            )
        }
    }
    .border(width = 8.dp, color = accent.copy(alpha = 0.25f), shape = shape)
    .border(width = 4.dp, color = accent.copy(alpha = 0.90f), shape = shape)

// Composable variant that wires up a MutableInteractionSource for D-pad focus detection.
@Composable
fun Modifier.dinkFocusable(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape,
): Modifier = composed {
    val focused by interactionSource.collectIsFocusedAsState()
    val accent = LocalDinkPalette.current.accent
    dinkFocus(focused = focused, shape = shape, accent = accent)
}

internal val FocusRingInnerWidth: Dp = 4.dp
internal val FocusRingOuterWidth: Dp = 8.dp

internal fun Density.focusRingPx(width: Dp): Float = width.toPx()

package com.example.dink_smb_player.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.dink_smb_player.ui.theme.LocalDinkPalette

/**
 * Slim indeterminate progress line — a single accent segment sweeping across a
 * faint track. Used while a screen computes its list off the main thread (the
 * library group/sort passes) so big libraries show motion instead of a blank.
 */
@Composable
fun ThinLoadingBar(modifier: Modifier = Modifier) {
    val palette = LocalDinkPalette.current
    val transition = rememberInfiniteTransition(label = "loading")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(950, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(palette.bg2),
    ) {
        val seg = maxWidth * 0.3f
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .offset(x = (maxWidth + seg) * progress - seg)
                .width(seg)
                .height(3.dp)
                .background(palette.accent, RoundedCornerShape(2.dp)),
        )
    }
}

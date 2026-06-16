package com.example.dink_smb_player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType

/**
 * Primary CTA — accent gradient fill, 56dp tall by default. Optional leading icon.
 * The 64dp variant ([height] = 64dp) is the Shuffle-All pattern used on library detail screens.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GradientButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    height: Dp = 56.dp,
    cornerRadius: Dp = 24.dp,
    contentColor: Color? = null,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }
    val ink = contentColor ?: palette.ink0

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(cornerRadius)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.accent,
            focusedContainerColor = palette.accent,
            contentColor = ink,
            focusedContentColor = ink,
        ),
        interactionSource = interaction,
        modifier = modifier.height(height),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .background(palette.accentGradient)
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(text = label, style = type.buttonLabel.copy(color = ink))
            }
        }
    }
}

/** Secondary button — transparent w/ outline, ghost style. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    height: Dp = 56.dp,
    cornerRadius: Dp = 24.dp,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(cornerRadius)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = palette.bg2,
            contentColor = palette.ink0,
            focusedContentColor = palette.ink0,
        ),
        interactionSource = interaction,
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .border(width = 1.dp, color = palette.lineStrong, shape = RoundedCornerShape(cornerRadius)),
    ) {
        Box(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = palette.ink1,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(text = label, style = type.buttonLabel.copy(color = palette.ink0))
            }
        }
    }
}

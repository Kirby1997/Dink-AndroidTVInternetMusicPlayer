package com.example.dink_smb_player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType

data class MiniPlayerState(
    val title: String = "Nothing playing",
    val artist: String = "—",
    val isPlaying: Boolean = false,
    val progress: Float = 0f,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MiniPlayer(
    state: MiniPlayerState,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current

    Row(
        modifier = modifier
            .testTag("miniplayer")
            .fillMaxWidth()
            .height(96.dp)
            .background(palette.bg1)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.bg2),
        )
        Column(modifier = Modifier.width(260.dp)) {
            Text(
                text = state.title,
                style = type.body.copy(color = palette.ink0),
                maxLines = 1,
            )
            Text(
                text = state.artist,
                style = type.bodySmall.copy(color = palette.ink2),
                maxLines = 1,
            )
        }
        TransportButton(icon = Icons.Outlined.SkipPrevious, onClick = onPrev)
        TransportButton(
            icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            onClick = onPlayPause,
            big = true,
        )
        TransportButton(icon = Icons.Outlined.SkipNext, onClick = onNext)
        Spacer(Modifier.width(8.dp))
        Scrubber(progress = state.progress, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Outlined.VolumeUp,
            contentDescription = "Volume",
            tint = palette.ink2,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun Scrubber(progress: Float, modifier: Modifier = Modifier) {
    val palette = LocalDinkPalette.current
    Box(
        modifier = modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(palette.bg2),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .background(palette.accent),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TransportButton(
    icon: ImageVector,
    onClick: () -> Unit,
    big: Boolean = false,
) {
    val palette = LocalDinkPalette.current
    val size: Dp = if (big) 56.dp else 44.dp
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (big) palette.bg3 else Color.Transparent,
            focusedContainerColor = palette.bg3,
            contentColor = palette.ink0,
            focusedContentColor = palette.ink0,
        ),
        interactionSource = interaction,
        // Not D-pad focusable: the MiniPlayer is a persistent now-playing readout
        // while navigating, controlled by the remote's hardware media keys (routed
        // via MediaSession), not by landing focus on it. Keeping it out of the
        // focus graph also removes a trap where DOWN from content fell into it.
        modifier = Modifier.size(size).focusProperties { canFocus = false },
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.ink0,
                modifier = Modifier.size(if (big) 28.dp else 22.dp),
            )
        }
    }
}

package com.example.dink_smb_player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType

data class SegOption<T>(val value: T, val label: String)

/**
 * Pill-shaped segmented control. Selected option highlighted with bg3 fill + accent text.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun <T> Seg(
    selected: T,
    options: List<SegOption<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDinkPalette.current
    Row(
        modifier = modifier
            .height(40.dp)
            .background(palette.bg1, RoundedCornerShape(20.dp))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { opt ->
            SegButton(
                label = opt.label,
                active = opt.value == selected,
                onClick = { onSelect(opt.value) },
                modifier = Modifier.fillMaxHeight(),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SegButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) palette.bg3 else Color.Transparent,
            focusedContainerColor = palette.bg2,
            contentColor = if (active) palette.ink0 else palette.ink2,
            focusedContentColor = palette.ink0,
        ),
        interactionSource = interaction,
        modifier = modifier,
    ) {
        Box(
            // fillMaxHEIGHT only — fillMaxSize made the first button greedily take the
            // Row's whole width, leaving the other options at 0dp (invisible). Each pill
            // now sizes to its own label.
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = type.buttonLabel.copy(
                    color = if (active) palette.ink0 else palette.ink2,
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

package com.example.dink_smb_player.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType

/**
 * Horizontal shelf with eyebrow + title + optional "View all" pill, then a LazyRow of cards.
 * Caller passes a LazyListScope content lambda so items can be `items(list) { Card(it) }` etc.
 */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ShelfRow(
    title: String,
    eyebrow: String,
    modifier: Modifier = Modifier,
    onViewAll: (() -> Unit)? = null,
    /**
     * Where focus should land when it enters this shelf from outside (e.g. spatial Up
     * from the MiniPlayer or Down from another shelf). Typically the first card's
     * [FocusRequester]; without this, Compose finds the nearest-X focusable and the
     * LazyRow auto-scrolls horizontally to bring it into view.
     */
    onEnterRequester: FocusRequester? = null,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(eyebrow.uppercase(), style = type.monoSmall.copy(color = palette.ink3))
                Text(title, style = type.sectionTitle.copy(color = palette.ink0))
            }
            if (onViewAll != null) {
                ViewAllPill(onClick = onViewAll)
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .let { base ->
                    if (onEnterRequester != null) {
                        base.focusProperties { enter = { onEnterRequester } }
                    } else base
                },
            content = content,
        )
        Spacer(Modifier.height(28.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ViewAllPill(onClick: () -> Unit) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.bg1,
            focusedContainerColor = palette.bg3,
            contentColor = palette.ink1,
            focusedContentColor = palette.ink0,
        ),
        interactionSource = interaction,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("View all >", style = type.buttonLabel.copy(color = palette.ink1))
        }
    }
}

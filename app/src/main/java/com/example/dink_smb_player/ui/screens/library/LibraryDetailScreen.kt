@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.dink_smb_player.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.LocalContentFocus
import com.example.dink_smb_player.LocalRailFocusRequester
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.player.PlayerState
import com.example.dink_smb_player.ui.components.CoverArt
import com.example.dink_smb_player.ui.components.GhostButton
import com.example.dink_smb_player.ui.components.GradientButton
import com.example.dink_smb_player.ui.components.SongContextMenu
import com.example.dink_smb_player.ui.components.synthAlbumFor
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType

/**
 * Detail (track list) for one album / artist / folder. Reached by tapping a row on the
 * Albums / Artists / Folders group screens; the tapped facet is in [LibraryDetailNav].
 * Header has the cover, counts, and Play-all / Shuffle; rows play from their index and
 * long-press opens the add-to-playlist/queue context menu. Closes Phase 9's
 * "library-detail screens included".
 */
@Composable
fun LibraryDetailScreen(
    player: PlayerState,
    onNavigate: (ScreenId) -> Unit,
    onToast: (String) -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val railRequester = LocalRailFocusRequester.current
    val contentFocus = LocalContentFocus.current

    val group = LibraryDetailNav.group
    if (group == null) {
        // Holder cleared (process death / direct nav) — bounce home instead of crashing.
        Box(modifier = Modifier.fillMaxSize().background(palette.bg0)) {}
        return
    }

    val songs = group.songs
    val art = remember(group.key) { synthAlbumFor(songs.firstOrNull() ?: return@remember null) }
    val totalSec = remember(songs) { songs.sumOf { it.durationSec } }
    var menuSong by remember { mutableStateOf<Song?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 24.dp)) {
        // ---- Header: cover + title + counts + actions ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (art != null) {
                CoverArt(
                    song = songs.firstOrNull(),
                    palette = art.palette,
                    shape = art.shape,
                    modifier = Modifier.size(132.dp),
                    cornerRadius = 16.dp,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(LibraryDetailNav.facet.uppercase(), style = type.monoSmall.copy(color = palette.ink3))
                Text(group.title, style = type.screenTitle.copy(color = palette.ink0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "${songs.size} tracks · ${formatLongDuration(totalSec)}",
                    style = type.body.copy(color = palette.ink2),
                    maxLines = 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GradientButton(
                        label = "Play all",
                        leadingIcon = Icons.Filled.PlayArrow,
                        onClick = {
                            if (songs.isNotEmpty()) {
                                if (player.shuffle) player.toggleShuffle()
                                player.playFrom(songs, 0)
                                onNavigate(ScreenId.NowPlaying)
                            }
                        },
                        height = 48.dp,
                        modifier = Modifier.focusRequester(contentFocus).focusProperties { left = railRequester },
                    )
                    GhostButton(
                        label = "Shuffle",
                        leadingIcon = Icons.Filled.Shuffle,
                        onClick = {
                            if (songs.isNotEmpty()) {
                                if (!player.shuffle) player.toggleShuffle()
                                player.playFrom(songs, songs.indices.random())
                                onNavigate(ScreenId.NowPlaying)
                            }
                        },
                        modifier = Modifier.focusProperties { left = railRequester },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                TrackRow(
                    index = index + 1,
                    song = song,
                    isPlaying = player.currentSong?.id == song.id,
                    onClick = {
                        player.playFrom(songs, index)
                        onNavigate(ScreenId.NowPlaying)
                    },
                    onLongClick = { menuSong = song },
                    modifier = Modifier.focusProperties { left = railRequester },
                )
            }
        }
    }

    menuSong?.let { song ->
        SongContextMenu(
            song = song,
            player = player,
            onToast = onToast,
            onDismiss = { menuSong = null },
        )
    }
}

@Composable
private fun TrackRow(
    index: Int,
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val container = when {
        focused -> palette.bg2
        isPlaying -> palette.bg1
        else -> Color.Transparent
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            focusedContainerColor = palette.bg2,
            contentColor = palette.ink0,
            focusedContentColor = palette.ink0,
        ),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().height(60.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "%02d".format(index),
                style = type.mono.copy(color = if (isPlaying) palette.accent else palette.ink3),
                modifier = Modifier.width(36.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = song.title,
                    style = type.songTitleCompact.copy(color = if (isPlaying) palette.accent else palette.ink0),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artist,
                    style = type.bodySmall.copy(color = palette.ink2),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatDuration(song.durationSec),
                style = type.mono.copy(color = palette.ink1),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(64.dp),
            )
        }
    }
}

private fun formatDuration(sec: Int): String {
    if (sec <= 0) return "—"
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}

private fun formatLongDuration(sec: Int): String {
    if (sec <= 0) return "length pending"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

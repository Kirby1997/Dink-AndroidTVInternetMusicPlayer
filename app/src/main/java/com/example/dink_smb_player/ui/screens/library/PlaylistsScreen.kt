@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.example.dink_smb_player.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.LocalRailFocusRequester
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.library.PlaylistRepository
import com.example.dink_smb_player.data.model.Playlist
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.player.PlayerState
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType
import kotlinx.coroutines.launch

/**
 * User playlists. Click plays the whole playlist (a bounded queue); long-press opens
 * Play / Delete. Tracks are added from the song context menu (long-press a song),
 * not here — this screen is the destination, [com.example.dink_smb_player.ui.components.SongContextMenu]
 * is the entry point. Song ids resolve against the live library, so an offline source
 * just yields a shorter playlist rather than an error.
 */
@Composable
fun PlaylistsScreen(
    player: PlayerState,
    onNavigate: (ScreenId) -> Unit,
    onToast: (String) -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val railRequester = LocalRailFocusRequester.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val playlists by PlaylistRepository.playlists.collectAsState()
    val libraryFlow = remember(context) { LibraryRepository.songs(context) }
    val library by libraryFlow.collectAsState()

    var menuFor by remember { mutableStateOf<Playlist?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 24.dp)) {
        Text("Playlists", style = type.screenTitle.copy(color = palette.ink0), maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (playlists.isEmpty()) "No playlists yet" else "${playlists.size} playlists",
            style = type.body.copy(color = palette.ink2),
            maxLines = 1,
        )

        Spacer(Modifier.height(20.dp))

        if (playlists.isEmpty()) {
            Text(
                "Long-press a song (hold OK) in Songs or Search to add it to a playlist.",
                style = type.body.copy(color = palette.ink3),
                maxLines = 2,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(playlists, key = { it.id }) { pl ->
                    val resolved = PlaylistRepository.songsOf(pl, library)
                    PlaylistRow(
                        name = pl.name,
                        subtitle = "${resolved.size} tracks",
                        railRequester = railRequester,
                        onClick = {
                            if (resolved.isNotEmpty()) {
                                player.playFrom(resolved, 0)
                                onNavigate(ScreenId.NowPlaying)
                            } else {
                                onToast("Playlist is empty (or its source is offline)")
                            }
                        },
                        onLongClick = { menuFor = pl },
                    )
                }
            }
        }
    }

    menuFor?.let { pl ->
        val resolved = PlaylistRepository.songsOf(pl, library)
        PlaylistActionsDialog(
            playlist = pl,
            onPlay = {
                menuFor = null
                if (resolved.isNotEmpty()) {
                    player.playFrom(resolved, 0)
                    onNavigate(ScreenId.NowPlaying)
                } else onToast("Playlist is empty")
            },
            onDelete = {
                menuFor = null
                scope.launch { PlaylistRepository.delete(context, pl.id) }
                onToast("Deleted “${pl.name}”")
            },
            onDismiss = { menuFor = null },
        )
    }
}

@Composable
private fun PlaylistRow(
    name: String,
    subtitle: String,
    railRequester: FocusRequester,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.bg1,
            focusedContainerColor = palette.bg2,
            contentColor = palette.ink0,
            focusedContentColor = palette.ink0,
        ),
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .focusProperties { left = railRequester },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(palette.accent.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = Icons.Outlined.QueueMusic, contentDescription = null, tint = palette.accent, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = type.songTitleCompact.copy(color = palette.ink0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = type.monoSmall.copy(color = palette.ink3), maxLines = 1)
            }
            Text("Hold OK", style = type.monoSmall.copy(color = palette.ink3), maxLines = 1)
        }
    }
}

@Composable
private fun PlaylistActionsDialog(
    playlist: Playlist,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val firstFocus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(40)
        runCatching { firstFocus.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .width(380.dp)
                .background(palette.bg1, RoundedCornerShape(16.dp))
                .border(1.dp, palette.lineStrong, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(playlist.name, style = type.songTitleCompact.copy(color = palette.ink0), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${playlist.songIds.size} tracks", style = type.monoSmall.copy(color = palette.ink3), maxLines = 1)
            Spacer(Modifier.height(4.dp))
            ActionRow("Play", Icons.Outlined.PlayArrow, firstFocus, palette.accent, onPlay)
            ActionRow("Delete playlist", Icons.Outlined.Delete, null, palette.bad, onDelete)
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    focusRequester: FocusRequester?,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.bg2,
            focusedContainerColor = tint.copy(alpha = 0.22f),
            contentColor = palette.ink0,
            focusedContentColor = palette.ink0,
        ),
        modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(label, style = type.body.copy(color = palette.ink0), maxLines = 1)
        }
    }
}

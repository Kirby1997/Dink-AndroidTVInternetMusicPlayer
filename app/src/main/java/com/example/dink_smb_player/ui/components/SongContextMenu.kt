@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.example.dink_smb_player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.data.library.PlaylistRepository
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.player.PlayerState
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType
import kotlinx.coroutines.launch

private enum class MenuMode { Root, PickPlaylist, NewPlaylist }

/**
 * Long-press context menu for a track. Centered dialog of D-pad-focusable rows —
 * there's no hover/right-click on a remote, so hold-OK is the only per-item action
 * (Plex/YouTube TV convention). Two levels: actions → playlist picker (+ inline
 * "New playlist…" naming via leanback IME). Dismiss with Back.
 */
@Composable
fun SongContextMenu(
    song: Song,
    player: PlayerState,
    onToast: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by PlaylistRepository.playlists.collectAsState()

    var mode by remember { mutableStateOf(MenuMode.Root) }
    val firstRowFocus = remember { FocusRequester() }

    // Pull focus to the top row each time the level changes, so the dialog is
    // immediately D-pad navigable and never leaks focus back to the rail.
    LaunchedEffect(mode) {
        if (mode != MenuMode.NewPlaylist) {
            kotlinx.coroutines.delay(40)
            runCatching { firstRowFocus.requestFocus() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .background(palette.bg1, RoundedCornerShape(16.dp))
                .border(1.dp, palette.lineStrong, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header: the track this menu acts on.
            Text(song.title, style = type.songTitleCompact.copy(color = palette.ink0), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = type.monoSmall.copy(color = palette.ink3), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))

            when (mode) {
                MenuMode.Root -> {
                    MenuRow("Add to playlist", Icons.Outlined.PlaylistAdd, firstRowFocus) { mode = MenuMode.PickPlaylist }
                    MenuRow("Add to queue", Icons.Outlined.QueueMusic) {
                        player.addToQueue(song)
                        onToast("Added to queue")
                        onDismiss()
                    }
                }

                MenuMode.PickPlaylist -> {
                    MenuRow("New playlist…", Icons.Outlined.Add, firstRowFocus) { mode = MenuMode.NewPlaylist }
                    if (playlists.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(playlists, key = { it.id }) { pl ->
                                val already = song.id in pl.songIds
                                MenuRow(
                                    label = pl.name,
                                    icon = Icons.Outlined.PlaylistAdd,
                                    trailing = if (already) "✓" else "${pl.songIds.size}",
                                    enabled = !already,
                                ) {
                                    scope.launch { PlaylistRepository.addSong(context, pl.id, song.id) }
                                    onToast("Added to ${pl.name}")
                                    onDismiss()
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    MenuRow("Back", Icons.Outlined.ArrowBack) { mode = MenuMode.Root }
                }

                MenuMode.NewPlaylist -> {
                    NewPlaylistField(
                        onCreate = { name ->
                            scope.launch { PlaylistRepository.create(context, name, seedSongId = song.id) }
                            onToast("Created “${name.trim().ifEmpty { "Untitled playlist" }}”")
                            onDismiss()
                        },
                        onCancel = { mode = MenuMode.PickPlaylist },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    icon: ImageVector,
    focusRequester: FocusRequester? = null,
    trailing: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.bg2,
            focusedContainerColor = palette.accent.copy(alpha = 0.22f),
            contentColor = if (enabled) palette.ink0 else palette.ink3,
            focusedContentColor = palette.ink0,
        ),
        interactionSource = interaction,
        modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(20.dp))
            Text(label, style = type.body.copy(color = palette.ink0), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (trailing != null) {
                Text(trailing, style = type.monoSmall.copy(color = palette.ink3), maxLines = 1)
            }
        }
    }
}

/** Inline name entry. Auto-focuses (opens leanback IME); Done creates, Back cancels. */
@Composable
private fun NewPlaylistField(
    onCreate: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    var name by remember { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        runCatching { fieldFocus.requestFocus() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("NAME · DONE TO CREATE · BACK TO CANCEL", style = type.monoSmall.copy(color = palette.accent))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(palette.bg2, RoundedCornerShape(10.dp))
                .border(1.dp, palette.accent, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (name.isEmpty()) {
                Text("Playlist name…", style = type.body.copy(color = palette.ink3))
            }
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = type.body.copy(color = palette.ink0),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCreate(name) }),
                modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
            )
        }
        MenuRow("Cancel", Icons.Outlined.ArrowBack) { onCancel() }
    }
}

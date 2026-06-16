@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.example.dink_smb_player.ui.screens.sources

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.LocalContentFocus
import com.example.dink_smb_player.LocalRailFocusRequester
import com.example.dink_smb_player.data.MediaLibrary
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.player.PlayerState
import com.example.dink_smb_player.ui.components.GradientButton
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType
import kotlinx.coroutines.launch

private val audioPermission: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

/**
 * In-app MediaStore browser. Stock Android TV often ships with no SAF/file-picker
 * app, so we read the system audio index ourselves. Requires READ_MEDIA_AUDIO on
 * API 33+, legacy READ_EXTERNAL_STORAGE on ≤32.
 *
 * Phase 6.5 turns this into a per-volume browser (internal / USB / SD).
 */
@Composable
fun LocalStorageScreen(
    player: PlayerState,
    onNavigate: (ScreenId) -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val railRequester = LocalRailFocusRequester.current
    val contentFocus = LocalContentFocus.current
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val songs = MediaLibrary.localSongs
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(hasPermission) {
        if (hasPermission && songs.isEmpty()) {
            loading = true
            MediaLibrary.refresh(context)
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // TopBar already shows "Sources / Local Storage" crumb — don't duplicate it.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(
                    text = "Local Storage",
                    style = type.sectionTitle.copy(color = palette.ink0),
                    maxLines = 1,
                )
                Text(
                    text = when {
                        !hasPermission -> "Grant access to read audio files indexed by Android."
                        loading -> "Scanning…"
                        songs.isEmpty() -> "No audio files indexed. Copy MP3/FLAC to /sdcard/Music or /sdcard/Download and rescan."
                        else -> "${songs.size} track${if (songs.size == 1) "" else "s"} indexed on device."
                    },
                    style = type.bodySmall.copy(color = palette.ink2),
                    maxLines = 2,
                )
            }
            if (!hasPermission) {
                GradientButton(
                    label = "Grant access",
                    onClick = { permLauncher.launch(audioPermission) },
                    height = 48.dp,
                    modifier = Modifier
                        .focusRequester(contentFocus)
                        .focusProperties { left = railRequester },
                )
            } else {
                GradientButton(
                    label = "Rescan",
                    leadingIcon = Icons.Filled.Refresh,
                    onClick = {
                        scope.launch {
                            loading = true
                            MediaLibrary.refresh(context)
                            loading = false
                        }
                    },
                    height = 48.dp,
                    modifier = Modifier
                        .focusRequester(contentFocus)
                        .focusProperties { left = railRequester },
                )
            }
        }

        if (hasPermission && songs.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(songs) { idx, song ->
                    TrackRow(
                        song = song,
                        onClick = {
                            player.playFrom(songs, idx)
                            onNavigate(ScreenId.NowPlaying)
                        },
                        modifier = Modifier.focusProperties { left = railRequester },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.bg1,
            focusedContainerColor = palette.bg2,
            contentColor = palette.ink0,
            focusedContentColor = palette.ink0,
        ),
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(palette.accent.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.tv.material3.Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = song.title,
                    style = type.songTitleCompact.copy(color = palette.ink0),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(song.artist)
                        song.albumTitle?.let { append(" · "); append(it) }
                        append(" · ")
                        append(formatDuration(song.durationSec))
                        append(" · ")
                        append(song.bitrate)
                    },
                    style = type.monoSmall.copy(color = palette.ink3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatDuration(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}

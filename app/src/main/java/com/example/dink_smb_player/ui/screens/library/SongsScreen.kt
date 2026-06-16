@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import com.example.dink_smb_player.data.PreviewMockData
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.model.Album
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.player.PlayerState
import com.example.dink_smb_player.ui.components.CoverArt
import com.example.dink_smb_player.ui.components.GradientButton
import com.example.dink_smb_player.ui.components.synthAlbumFor
import com.example.dink_smb_player.ui.components.Seg
import com.example.dink_smb_player.ui.components.SegOption
import com.example.dink_smb_player.ui.components.SongContextMenu
import com.example.dink_smb_player.ui.components.ThinLoadingBar
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType

enum class SongSort { MostPlayed, Title, Artist, Longest }
enum class SongFilter { All, Flac, Lossy }

@Composable
fun SongsScreen(
    player: PlayerState,
    onNavigate: (ScreenId) -> Unit,
    onToast: (String) -> Unit,
) {
    val railRequester = LocalRailFocusRequester.current
    val contentFocus = LocalContentFocus.current
    val albumsById = remember { PreviewMockData.albums.associateBy { it.id } }
    // Read the unified library index — every imported track (local + SMB + cloud)
    // surfaces here regardless of which source screen scanned it. NO mock seed: this is
    // a real, user-facing list, so fake demo tracks must not leak into it.
    val context = LocalContext.current
    val librarySongsFlow = remember(context) { LibraryRepository.songs(context) }
    val allSongs by librarySongsFlow.collectAsState()

    var sort by remember { mutableStateOf(SongSort.Title) }
    var filter by remember { mutableStateOf(SongFilter.All) }
    // Long-press (hold OK) on a row opens the track context menu (add to playlist / queue).
    var menuSong by remember { mutableStateOf<Song?>(null) }

    // Filter + sort of the whole library runs off the main thread; doing it inline
    // in composition froze navigation on big libraries. Empty until the first
    // background pass completes, so the screen opens instantly then populates.
    // null while the background filter+sort runs → screen shows a thin loading bar
    // instead of a blank list. Non-null (possibly empty) means the pass finished.
    val filtered: List<Song>? by produceState<List<Song>?>(initialValue = null, sort, filter, allSongs) {
        value = withContext(Dispatchers.Default) {
            val pool = when (filter) {
                SongFilter.All -> allSongs
                SongFilter.Flac -> allSongs.filter { it.bitrate.contains("FLAC", ignoreCase = true) }
                SongFilter.Lossy -> allSongs.filter { !it.bitrate.contains("FLAC", ignoreCase = true) }
            }
            when (sort) {
                SongSort.MostPlayed -> pool.sortedByDescending { it.playCount }
                SongSort.Title -> pool.sortedBy { it.title.lowercase() }
                SongSort.Artist -> pool.sortedBy { it.artist.lowercase() }
                SongSort.Longest -> pool.sortedByDescending { it.durationSec }
            }
        }
    }

    val rows = filtered.orEmpty()
    val totalDurationSec = remember(rows) { rows.sumOf { it.durationSec } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp),
    ) {
        Header(
            count = filtered?.size ?: allSongs.size,
            totalSec = totalDurationSec,
            sort = sort,
            filter = filter,
            railRequester = railRequester,
            contentFocus = contentFocus,
            onSortChange = { sort = it },
            onFilterChange = { filter = it },
            onShuffleAll = {
                val pool = rows.ifEmpty { allSongs }
                if (pool.isNotEmpty()) {
                    if (!player.shuffle) player.toggleShuffle()
                    player.playFrom(pool, pool.indices.random())
                    onNavigate(ScreenId.NowPlaying)
                }
            },
        )

        Spacer(Modifier.height(20.dp))

        // Column header strip — mono labels for the row columns below.
        ColumnHeaderRow()

        Spacer(Modifier.height(8.dp))

        if (filtered == null) {
            ThinLoadingBar(modifier = Modifier.padding(horizontal = 64.dp))
        } else {
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            // focusGroup keeps D-pad focus contained in the list during a fast fling.
            // Without it, a focused row recycling before the next is laid out drops
            // focus to the root → the rail/drawer grabs it mid-scroll.
            modifier = Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(horizontal = 64.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(rows, key = { _, song -> song.id }) { index, song ->
                val album = albumsById[song.albumId]
                SongRow(
                    index = index + 1,
                    song = song,
                    album = album,
                    isPlaying = player.currentSong?.id == song.id,
                    onClick = {
                        player.playFrom(rows, index)
                        onNavigate(ScreenId.NowPlaying)
                    },
                    onLongClick = { menuSong = song },
                    // Every row routes Left to the rail; otherwise spatial nav from a
                    // lower row grabs whatever rail item is vertically nearest (Settings).
                    modifier = Modifier.leftEdgeTo(railRequester),
                )
            }
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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun Modifier.leftEdgeTo(requester: FocusRequester): Modifier =
    focusProperties { left = requester }

@Composable
private fun Header(
    count: Int,
    totalSec: Int,
    sort: SongSort,
    filter: SongFilter,
    railRequester: FocusRequester,
    contentFocus: FocusRequester,
    onSortChange: (SongSort) -> Unit,
    onFilterChange: (SongFilter) -> Unit,
    onShuffleAll: () -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 64.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Songs",
            style = type.screenTitle.copy(color = palette.ink0),
            maxLines = 1,
        )
        Text(
            text = "$count tracks · ${formatLongDuration(totalSec)} of music",
            style = type.body.copy(color = palette.ink2),
            maxLines = 1,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GradientButton(
                label = "Shuffle all",
                leadingIcon = Icons.Filled.Shuffle,
                onClick = onShuffleAll,
                height = 48.dp,
                modifier = Modifier.focusRequester(contentFocus).leftEdgeTo(railRequester),
            )
            Seg(
                selected = sort,
                onSelect = onSortChange,
                options = listOf(
                    SegOption(SongSort.MostPlayed, "Plays"),
                    SegOption(SongSort.Title, "Title"),
                    SegOption(SongSort.Artist, "Artist"),
                    SegOption(SongSort.Longest, "Length"),
                ),
            )
            Seg(
                selected = filter,
                onSelect = onFilterChange,
                options = listOf(
                    SegOption(SongFilter.All, "All"),
                    SegOption(SongFilter.Flac, "FLAC"),
                    SegOption(SongFilter.Lossy, "Lossy"),
                ),
            )
        }
    }
}

@Composable
private fun ColumnHeaderRow() {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val labelStyle = type.monoSmall.copy(color = palette.ink3)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 64.dp + 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("#", style = labelStyle, maxLines = 1, softWrap = false, modifier = Modifier.width(36.dp))
        Spacer(Modifier.width(56.dp))
        Text("TITLE", style = labelStyle, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
        Text("SOURCE", style = labelStyle, maxLines = 1, softWrap = false, modifier = Modifier.width(220.dp))
        Text("PLAYS", style = labelStyle, maxLines = 1, softWrap = false, modifier = Modifier.width(80.dp))
        Text("LENGTH", style = labelStyle, maxLines = 1, softWrap = false, modifier = Modifier.width(72.dp))
    }
}

@Composable
private fun SongRow(
    index: Int,
    song: Song,
    album: Album?,
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
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            focusedContainerColor = palette.bg2,
            contentColor = palette.ink0,
            focusedContentColor = palette.ink0,
        ),
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    text = "%02d".format(index),
                    style = type.mono.copy(
                        color = if (isPlaying) palette.accent else palette.ink3,
                    ),
                )
            }
            // Real cover when the file has one, else procedural (album-or-synth). Every
            // row gets art now — imported tracks have no mock Album, so this is the only
            // thing standing between the list and a wall of blank squares.
            val artFallback = album ?: synthAlbumFor(song)
            CoverArt(
                song = song,
                palette = artFallback.palette,
                shape = artFallback.shape,
                modifier = Modifier.size(56.dp),
                cornerRadius = 10.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = song.title,
                    style = type.songTitleCompact.copy(
                        color = if (isPlaying) palette.accent else palette.ink0,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(song.artist)
                        if (album != null) {
                            append(" · ")
                            append(album.title)
                            album.year?.let { y ->
                                append(" · ")
                                append(y)
                            }
                        }
                    },
                    style = type.bodySmall.copy(color = palette.ink2),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                modifier = Modifier.width(220.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = song.bitrate,
                    style = type.mono.copy(color = palette.ink1),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.sourcePath,
                    style = type.monoSmall.copy(color = palette.ink3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = song.playCount.toString(),
                style = type.mono.copy(color = palette.ink1),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(80.dp),
            )
            Text(
                text = formatDuration(song.durationSec),
                style = type.mono.copy(color = palette.ink1),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(72.dp),
            )
        }
    }
}

private fun formatDuration(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}

private fun formatLongDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

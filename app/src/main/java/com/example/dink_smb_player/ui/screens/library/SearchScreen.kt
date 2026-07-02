@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.example.dink_smb_player.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.LocalContentFocus
import com.example.dink_smb_player.LocalRailFocusRequester
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.player.PlayerState
import com.example.dink_smb_player.ui.components.CoverArt
import com.example.dink_smb_player.ui.components.Seg
import com.example.dink_smb_player.ui.components.SegOption
import com.example.dink_smb_player.ui.components.SongContextMenu
import com.example.dink_smb_player.ui.components.synthAlbumFor
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded result set so a one-letter query over a 25k library can't render 25k rows. */
private const val SONG_CAP = 80
private const val ALBUM_CAP = 30
private const val ARTIST_CAP = 30

/** What the query matches against, chosen by the user. Also drives result layout. */
enum class SearchFacet { Songs, Albums, Artists }

/** One matched artist, expanded into their albums (each with its tracks) so an
 *  artist search reads as an organised discography, not a flat row. */
private data class ArtistResult(
    val name: String,
    val trackCount: Int,
    val albums: List<LibraryGroup>,
)

private data class SearchResults(
    val songs: List<Song>,
    val albums: List<LibraryGroup>,
    val artists: List<ArtistResult>,
) {
    val isEmpty: Boolean get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()
}

/**
 * Library-wide search across Songs / Albums / Artists. Embedded-tag metadata
 * (title, artist, albumTitle on [Song]) is the match key — same truth the library
 * facets group by — so a tag-corrected SMB/cloud track is findable by its real name,
 * not its filename.
 *
 * TV input: the field is a tap-to-edit chip (OK opens leanback IME, Back/Done
 * commits) like [com.example.dink_smb_player.ui.screens.sources.AddShareWizard]'s.
 * Results filter live off [query], so they're already correct when the IME closes.
 */
@Composable
fun SearchScreen(
    player: PlayerState,
    onNavigate: (ScreenId) -> Unit,
    onToast: (String) -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val railRequester = LocalRailFocusRequester.current
    val contentFocus = LocalContentFocus.current
    val context = LocalContext.current

    val songsFlow = remember(context) { LibraryRepository.songs(context) }
    val library by songsFlow.collectAsState()

    var query by remember { mutableStateOf("") }
    var facet by remember { mutableStateOf(SearchFacet.Songs) }
    // Long-press (hold OK) on a song result opens the track context menu.
    var menuSong by remember { mutableStateOf<Song?>(null) }

    // Match + bucket off the main thread — a broad query over a big library does a
    // full scan + groupBy that would jank the field while typing if run inline.
    // null = first pass not done yet (only ever shows for a frame).
    val results: SearchResults? by produceState<SearchResults?>(initialValue = null, query, library, facet) {
        value = withContext(Dispatchers.Default) { search(query, library, facet) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 24.dp)) {
        Text("Search", style = type.screenTitle.copy(color = palette.ink0), maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${library.size} tracks in your library",
            style = type.body.copy(color = palette.ink2),
            maxLines = 1,
        )

        Spacer(Modifier.height(16.dp))

        // Explicit down-target: the facet Seg sits at the left edge, so spatial DOWN from
        // the full-width field beams straight past it onto the results. Route it directly.
        val facetFocus = remember { FocusRequester() }

        SearchField(
            value = query,
            onChange = { query = it },
            contentFocus = contentFocus,
            railRequester = railRequester,
            downTarget = facetFocus,
        )

        Spacer(Modifier.height(12.dp))

        // Facet picker — what the query matches against (and how results are laid out).
        Box(modifier = Modifier.focusGroup().focusRequester(facetFocus)) {
            Seg(
                selected = facet,
                options = listOf(
                    SegOption(SearchFacet.Songs, "Songs"),
                    SegOption(SearchFacet.Albums, "Albums"),
                    SegOption(SearchFacet.Artists, "Artists"),
                ),
                onSelect = { facet = it },
                modifier = Modifier.focusProperties { left = railRequester },
            )
        }

        Spacer(Modifier.height(16.dp))

        val r = results
        when {
            query.isBlank() -> Hint("Type to search by ${facet.name.lowercase().removeSuffix("s")}.")
            r == null -> Hint("Searching…")
            r.isEmpty -> Hint("No ${facet.name.lowercase()} match “$query”.")
            else -> ResultsList(facet, r, player, onNavigate, railRequester, onLongClickSong = { menuSong = it })
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
private fun Hint(text: String) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    Text(text, style = type.body.copy(color = palette.ink3), maxLines = 2)
}

@Composable
private fun ResultsList(
    facet: SearchFacet,
    results: SearchResults,
    player: PlayerState,
    onNavigate: (ScreenId) -> Unit,
    railRequester: FocusRequester,
    onLongClickSong: (Song) -> Unit,
) {
    // Open an album/artist's track list (detail screen). The facet shapes the crumb.
    fun openDetail(group: LibraryGroup, facetLabel: String, parent: ScreenId) {
        LibraryDetailNav.open(group, facetLabel, parent)
        onNavigate(ScreenId.LibraryDetail)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (facet) {
            SearchFacet.Songs -> {
                items(results.songs, key = { "s-${it.id}" }) { song ->
                    val idx = results.songs.indexOf(song)
                    SongResultRow(
                        song = song,
                        onClick = {
                            player.playFrom(results.songs, idx)
                            onNavigate(ScreenId.NowPlaying)
                        },
                        onLongClick = { onLongClickSong(song) },
                        railRequester = railRequester,
                    )
                }
            }

            SearchFacet.Albums -> {
                items(results.albums, key = { "al-${it.key}" }) { group ->
                    AlbumResultRow(
                        group = group,
                        onClick = { openDetail(group, "Album", ScreenId.Albums) },
                        railRequester = railRequester,
                    )
                }
            }

            SearchFacet.Artists -> {
                // Each artist → header, then their albums (each a tappable sub-header
                // opening the album's detail) with the album's songs listed beneath.
                results.artists.forEach { artist ->
                    item(key = "arh-${artist.name}") {
                        SectionHeader("${artist.name.uppercase()} · ${artist.trackCount} TRACKS · ${artist.albums.size} ALBUMS")
                    }
                    artist.albums.forEach { album ->
                        item(key = "ab-${artist.name}-${album.key}") {
                            AlbumResultRow(
                                group = album,
                                onClick = { openDetail(album, "Album", ScreenId.Artists) },
                                railRequester = railRequester,
                            )
                        }
                        items(album.songs, key = { "as-${artist.name}-${album.key}-${it.id}" }) { song ->
                            val idx = album.songs.indexOf(song)
                            SongResultRow(
                                song = song,
                                onClick = {
                                    player.playFrom(album.songs, idx)
                                    onNavigate(ScreenId.NowPlaying)
                                },
                                onLongClick = { onLongClickSong(song) },
                                railRequester = railRequester,
                                indent = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongResultRow(
    song: Song,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    railRequester: FocusRequester,
    indent: Boolean = false,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }
    val art = remember(song.id) { synthAlbumFor(song) }
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
            .height(56.dp)
            .focusProperties { left = railRequester },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = if (indent) 32.dp else 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CoverArt(song = song, palette = art.palette, shape = art.shape, modifier = Modifier.size(40.dp), cornerRadius = 8.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(song.title, style = type.songTitleCompact.copy(color = palette.ink0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitleFor(song), style = type.monoSmall.copy(color = palette.ink3), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AlbumResultRow(
    group: LibraryGroup,
    onClick: () -> Unit,
    railRequester: FocusRequester,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }
    val rep = group.songs.firstOrNull()
    val art = remember(group.key) { rep?.let { synthAlbumFor(it) } }
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
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .focusProperties { left = railRequester },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (art != null) {
                CoverArt(song = rep, palette = art.palette, shape = art.shape, modifier = Modifier.size(44.dp), cornerRadius = 8.dp)
            } else {
                Box(
                    modifier = Modifier.size(44.dp).background(palette.accent.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Album, null, tint = palette.accent, modifier = Modifier.size(20.dp)) }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(group.title, style = type.songTitleCompact.copy(color = palette.ink0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(group.subtitle, style = type.monoSmall.copy(color = palette.ink3), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    Text(
        label,
        style = type.monoSmall.copy(color = palette.ink3),
        maxLines = 1,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

/**
 * Tap-to-edit search field. Focusing a `BasicTextField` on TV opens the full-screen
 * leanback IME immediately, so we render a focusable chip and only mount the live
 * `BasicTextField` after the user activates with OK. Back/Done commits and returns
 * focus to the chip. Mirrors AddShareWizard's TapEditField.
 */
@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    contentFocus: FocusRequester,
    railRequester: FocusRequester,
    downTarget: FocusRequester,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current

    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    var returnFocus by remember { mutableStateOf(false) }
    // Leanback IME Done/Back emits a trailing key-up that lands on the just-refocused
    // chip; without a guard it re-fires the clickable and reopens the IME instantly.
    var reopenGuardUntil by remember { mutableStateOf(0L) }

    fun exitEdit() {
        editing = false
        returnFocus = true
        reopenGuardUntil = System.currentTimeMillis() + 400
    }

    if (editing) {
        BackHandler(enabled = true) { exitEdit() }
    }

    LaunchedEffect(returnFocus) {
        if (returnFocus) {
            kotlinx.coroutines.delay(40)
            runCatching { contentFocus.requestFocus() }
            returnFocus = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = if (editing) "SEARCH · BACK WHEN DONE" else "SEARCH · OK TO EDIT",
            style = type.monoSmall.copy(color = if (editing) palette.accent else palette.ink3),
        )
        if (editing) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(40)
                runCatching { fieldFocus.requestFocus() }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(palette.bg1, RoundedCornerShape(8.dp))
                    .border(1.dp, palette.accent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text("Song, album, or artist…", style = type.body.copy(color = palette.ink3))
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = type.body.copy(color = palette.ink0),
                    cursorBrush = SolidColor(palette.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { exitEdit() }),
                    // Trap D-pad while editing: a direction the field doesn't consume
                    // escapes to the rail and opens the drawer mid-type. Exit is Back/Search.
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        .focusProperties {
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                            up = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                        },
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .focusRequester(contentFocus)
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.bg1)
                    .border(
                        1.dp,
                        if (focused) palette.accent else palette.lineStrong,
                        RoundedCornerShape(8.dp),
                    )
                    .focusProperties { left = railRequester; down = downTarget }
                    .onFocusChanged { focused = it.isFocused }
                    .clickable {
                        if (System.currentTimeMillis() >= reopenGuardUntil) editing = true
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = if (focused) palette.accent else palette.ink3,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = value.ifEmpty { "Song, album, or artist…" },
                    style = type.body.copy(color = if (value.isEmpty()) palette.ink3 else palette.ink0),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun subtitleFor(song: Song): String = buildString {
    append(song.artist)
    song.albumTitle?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
}

/**
 * Tokenised, case-insensitive match driven by the chosen [facet]: Songs matches track
 * titles, Albums matches album titles, Artists matches artist names — so "search by
 * artist" is exactly that. A value matches if every whitespace-separated query token is
 * a substring of it ("slip wait" → Slipknot · Wait and Bleed). Only the active facet is
 * computed; the others stay empty. Albums/artists group ALL their tracks (not just the
 * matched ones) so play/detail is the whole album.
 */
private fun search(query: String, library: List<Song>, facet: SearchFacet): SearchResults {
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
    fun matches(s: String): Boolean = tokens.all { s.contains(it) }

    fun albumGroupsFor(tracks: List<Song>): List<LibraryGroup> = tracks
        .groupBy { it.albumTitle?.takeIf { t -> t.isNotBlank() } ?: "Unknown album" }
        .map { (albumKey, list) ->
            val sorted = list.sortedBy { it.title.lowercase() }
            val artist = list.map { it.artist }.distinct().singleOrNull() ?: "Various artists"
            LibraryGroup(albumKey, albumKey, "$artist · ${list.size} tracks", sorted)
        }
        .sortedBy { it.title.lowercase() }

    return when (facet) {
        SearchFacet.Songs -> SearchResults(
            songs = library.filter { matches(it.title.lowercase()) }
                .sortedBy { it.title.lowercase() }.take(SONG_CAP),
            albums = emptyList(),
            artists = emptyList(),
        )

        SearchFacet.Albums -> {
            val albums = library
                .filter { !it.albumTitle.isNullOrBlank() }
                .groupBy { it.albumTitle!! }
                .filterKeys { matches(it.lowercase()) }
                .let { groups -> albumGroupsFor(groups.values.flatten()) }
                .take(ALBUM_CAP)
            SearchResults(emptyList(), albums, emptyList())
        }

        SearchFacet.Artists -> {
            val artists = library
                .groupBy { it.artist }
                .filterKeys { matches(it.lowercase()) }
                .map { (artistKey, list) ->
                    ArtistResult(artistKey, list.size, albumGroupsFor(list))
                }
                .sortedBy { it.name.lowercase() }
                .take(ARTIST_CAP)
            SearchResults(emptyList(), emptyList(), artists)
        }
    }
}

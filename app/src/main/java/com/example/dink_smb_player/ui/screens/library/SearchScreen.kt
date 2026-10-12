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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Typing pause before a query change re-scans the library (UI-16). */
private const val SEARCH_DEBOUNCE_MS = 150L

/** Bounded result set so a one-letter query over a 25k library can't render 25k rows. */
private const val SONG_CAP = 80
private const val ALBUM_CAP = 30
private const val ARTIST_CAP = 30

/** Per-section caps on the All facet: a preview of each kind; the facet tabs show the rest. */
private const val ALL_ARTIST_CAP = 5
private const val ALL_ALBUM_CAP = 8
private const val ALL_SONG_CAP = 25

/** What the query matches against, chosen by the user. Also drives result layout.
 *  [All] = every kind at once, in Artists / Albums / Songs sections. */
enum class SearchFacet { All, Artists, Albums, Songs }

/**
 * Search state that outlives the screen. `DinkNav` swaps screens by composition, so plain
 * `remember` lost the query + facet the moment a result opened LibraryDetail — Back then
 * landed on an empty Search (UI-6). Process-lifetime, like [ListScrollMemo]: also holds the
 * results scroll and which row was opened, so Back refocuses it.
 */
object SearchMemo {
    val query = mutableStateOf("")
    val facet = mutableStateOf(SearchFacet.All)
    var index: Int = 0
    var offset: Int = 0
    /** Lazy-list key of the result row that opened a detail. */
    var openedKey: String? = null
    /** One-shot, set by Back from a detail whose parent is Search (see DinkApp). */
    var armBackFocus: Boolean = false

    fun rememberOpened(key: String) {
        openedKey = key
    }

    /** Arms the refocus; called when Back returns from a detail opened here. */
    fun arm() {
        armBackFocus = true
    }

    /** Consume the one-shot refocus: returns the row key to focus, or null. Only fires
     *  when armed AND the row still exists in the current results. */
    fun consumeBackFocus(resultKeys: Collection<String>): String? {
        if (!armBackFocus) return null
        armBackFocus = false
        return openedKey?.takeIf { it in resultKeys }
    }
}

/**
 * One search pass. Artists and albums are the SAME [LibraryGroup]s the Artists / Albums
 * screens show (key, title, subtitle, tracks), so opening one lands on an identical detail.
 * [facet] is the facet these were computed for — the list lays out by it, not the live
 * picker, so a facet switch never renders the previous facet's results in the new layout.
 * The totals count matches before the cap, for the section headers.
 */
internal data class SearchResults(
    val facet: SearchFacet,
    val artists: List<LibraryGroup>,
    val albums: List<LibraryGroup>,
    val songs: List<Song>,
    val artistTotal: Int = artists.size,
    val albumTotal: Int = albums.size,
    val songTotal: Int = songs.size,
    /** The query these results answer; lags the field while a search is in flight. */
    val query: String = "",
) {
    val isEmpty: Boolean get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()
}

/**
 * Library-wide search across Artists / Albums / Songs. Embedded-tag metadata
 * (title, artist, albumTitle on [Song]) is the match key — same truth the library
 * facets group by — so a tag-corrected SMB track is findable by its real name,
 * not its filename. Artist and album results open their detail page (as the
 * Artists / Albums screens do); song results play.
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

    var query by SearchMemo.query
    var facet by SearchMemo.facet
    // Long-press (hold OK) on a song result opens the track context menu.
    var menuSong by remember { mutableStateOf<Song?>(null) }

    // Match + bucket off the main thread — a broad query over a big library does a
    // full scan + groupBy that would jank the field while typing if run inline.
    // null = first pass not done yet (only ever shows for a frame).
    // UI-16: a keystroke restarts this block, so the delay debounces typing (the superseded
    // scan is cancelled via ensureActive), and match keys are built once per library
    // instance (SearchIndex.of), not per keystroke. The first pass (screen entry,
    // back from a detail) and facet changes run at once.
    // Build the match keys while the user is still opening the keyboard: the first build is
    // seconds on the TV, and the blank-query pass below never touches the index.
    LaunchedEffect(library) { withContext(Dispatchers.Default) { SearchIndex.of(library).warm() } }
    val lastQuery = remember { arrayOfNulls<String>(1) }
    val results: SearchResults? by produceState<SearchResults?>(initialValue = null, query, library, facet) {
        val prev = lastQuery[0]
        if (prev != null && prev != query && query.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
        value = withContext(Dispatchers.Default) { search(query, SearchIndex.of(library), facet) }
        lastQuery[0] = query
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
        // Only the first pill routes Left to the rail; the rest move between pills.
        Box(modifier = Modifier.focusGroup().focusRequester(facetFocus)) {
            Seg(
                selected = facet,
                options = listOf(
                    SegOption(SearchFacet.All, "All"),
                    SegOption(SearchFacet.Artists, "Artists"),
                    SegOption(SearchFacet.Albums, "Albums"),
                    SegOption(SearchFacet.Songs, "Songs"),
                ),
                onSelect = { facet = it },
                firstItemModifier = Modifier.focusProperties { left = railRequester },
            )
        }

        Spacer(Modifier.height(16.dp))

        val r = results
        // Back from a detail opened here: refocus the row that opened it (the list restores
        // its scroll, so it's on screen). Nothing to refocus → the field, never the void —
        // Back doesn't bump DinkApp's commit refocus, so without this focus drifts to the rail.
        val returnFocus = remember { FocusRequester() }
        var returnKey by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(r) {
            if (r == null || !SearchMemo.armBackFocus) return@LaunchedEffect
            returnKey = SearchMemo.consumeBackFocus(resultKeys(r))
            val target = if (returnKey != null) returnFocus else contentFocus
            repeat(20) {
                delay(30)
                if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
        when {
            query.isBlank() -> Hint(
                if (facet == SearchFacet.All) "Type to search artists, albums, and songs."
                else "Type to search by ${facet.name.lowercase().removeSuffix("s")}.",
            )
            // An empty result for an older query (the blank-query pass on entry, while the
            // first index build runs for seconds) isn't "nothing matches" — it's not done yet.
            r == null || (r.isEmpty && r.query != query) -> Hint("Searching…")
            r.isEmpty -> Hint(
                if (r.facet == SearchFacet.All) "Nothing matches “$query”."
                else "No ${r.facet.name.lowercase()} match “$query”.",
            )
            // Keyed by facet + query so switching facet or typing a new query starts the
            // list at the top (a Back return keeps both, so its restored scroll survives).
            else -> key(r.facet, query) {
                ResultsList(
                    r, player, onNavigate, railRequester,
                    onLongClickSong = { menuSong = it },
                    returnKey = returnKey,
                    returnFocus = returnFocus,
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
private fun Hint(text: String) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    Text(text, style = type.body.copy(color = palette.ink3), maxLines = 2)
}

@Composable
private fun ResultsList(
    results: SearchResults,
    player: PlayerState,
    onNavigate: (ScreenId) -> Unit,
    railRequester: FocusRequester,
    onLongClickSong: (Song) -> Unit,
    returnKey: String?,
    returnFocus: FocusRequester,
) {
    // Open an artist/album's detail screen — the same page its Artists/Albums tile opens
    // (an artist → their album wall, an album → its tracks). Parent = Search so the rail
    // highlights Search and Back returns here with the query intact (UI-6).
    fun openDetail(group: LibraryGroup, facetLabel: String, rowKey: String) {
        SearchMemo.rememberOpened(rowKey)
        LibraryDetailNav.open(group, facetLabel, ScreenId.Search)
        onNavigate(ScreenId.LibraryDetail)
    }
    fun Modifier.returnTarget(rowKey: String): Modifier =
        if (rowKey == returnKey) this.focusRequester(returnFocus) else this

    // Section headers only on All, where three kinds of row share one list.
    val headers = results.facet == SearchFacet.All

    // Scroll survives the detail round-trip only on a Back return (armed); a fresh visit
    // or new query starts at the top. Saved on the way out either way.
    val listState = rememberLazyListState(
        if (SearchMemo.armBackFocus) SearchMemo.index else 0,
        if (SearchMemo.armBackFocus) SearchMemo.offset else 0,
    )
    DisposableEffect(Unit) {
        onDispose {
            SearchMemo.index = listState.firstVisibleItemIndex
            SearchMemo.offset = listState.firstVisibleItemScrollOffset
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (results.artists.isNotEmpty()) {
            if (headers) item(key = "h-artists") { SectionHeader(sectionLabel("ARTISTS", results.artists.size, results.artistTotal)) }
            items(results.artists, key = { artistRowKey(it) }) { group ->
                GroupResultRow(
                    group = group,
                    round = true,
                    onClick = { openDetail(group, "Artist", artistRowKey(group)) },
                    railRequester = railRequester,
                    modifier = Modifier.returnTarget(artistRowKey(group)),
                )
            }
        }

        if (results.albums.isNotEmpty()) {
            if (headers) item(key = "h-albums") { SectionHeader(sectionLabel("ALBUMS", results.albums.size, results.albumTotal)) }
            items(results.albums, key = { albumRowKey(it) }) { group ->
                GroupResultRow(
                    group = group,
                    round = false,
                    onClick = { openDetail(group, "Album", albumRowKey(group)) },
                    railRequester = railRequester,
                    modifier = Modifier.returnTarget(albumRowKey(group)),
                )
            }
        }

        if (results.songs.isNotEmpty()) {
            if (headers) item(key = "h-songs") { SectionHeader(sectionLabel("SONGS", results.songs.size, results.songTotal)) }
            itemsIndexed(results.songs, key = { _, song -> "s-${song.id}" }) { idx, song ->
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
    }
}

/** "ARTISTS · 12", or "ARTISTS · 5 OF 12" when the All preview cut the section short. */
private fun sectionLabel(label: String, shown: Int, total: Int): String =
    if (shown < total) "$label · $shown OF $total" else "$label · $total"

@Composable
private fun SongResultRow(
    song: Song,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    railRequester: FocusRequester,
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
            modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 16.dp),
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

/** An artist ([round] art, like a profile) or album result: cover, name, and the same
 *  subtitle its Artists/Albums tile shows. */
@Composable
private fun GroupResultRow(
    group: LibraryGroup,
    round: Boolean,
    onClick: () -> Unit,
    railRequester: FocusRequester,
    modifier: Modifier = Modifier,
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
        modifier = modifier
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
                CoverArt(
                    song = rep,
                    palette = art.palette,
                    shape = art.shape,
                    modifier = Modifier.size(44.dp),
                    // Half the size → a circle for an artist.
                    cornerRadius = if (round) 22.dp else 8.dp,
                )
            } else {
                Box(
                    modifier = Modifier.size(44.dp).background(
                        palette.accent.copy(alpha = 0.18f),
                        if (round) CircleShape else RoundedCornerShape(8.dp),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (round) Icons.Outlined.Person else Icons.Outlined.Album,
                        null,
                        tint = palette.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
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

private fun artistRowKey(group: LibraryGroup): String = "ar-${group.key}"
private fun albumRowKey(group: LibraryGroup): String = "al-${group.key}"

/** Keys of the rows that can open a detail — what a Back return may refocus. */
private fun resultKeys(r: SearchResults): Set<String> =
    r.artists.mapTo(HashSet<String>()) { artistRowKey(it) } + r.albums.map { albumRowKey(it) }

private fun subtitleFor(song: Song): String = buildString {
    append(song.artist)
    song.albumTitle?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
}

// ---- Matching: pure, so it's JVM-testable (SearchIndexTest). ----

private val SEARCH_WS = Regex("\\s+")
private val SEARCH_MARKS = Regex("\\p{Mn}+")
private val SEARCH_NON_ALNUM = Regex("[^\\p{L}\\p{Nd}]+")

/** Case- and accent-insensitive form of [s] — lowercase, then NFD with the combining marks
 *  dropped, as [com.example.dink_smb_player.data.index.LibraryGrouping.normKey] folds them
 *  ("Motörhead" ≡ "motorhead"). Spacing and punctuation survive so the query can tokenise. */
internal fun searchFold(s: String): String =
    SEARCH_MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")

/** [searchFold]ed text with punctuation and spacing gone, so "acdc" / "ac-dc" find
 *  "AC/DC" and "dont" finds "Don't" — the other half of the grouping fold. */
private fun compactOf(folded: String): String = SEARCH_NON_ALNUM.replace(folded, "")

/** One match string holding each distinct spelling, folded and compacted, a line apiece —
 *  so a query matches any of them. Tokens hold no whitespace, so never span two lines. */
internal fun searchKey(spellings: Iterable<String>): String =
    spellings.asSequence().map(::searchFold).flatMap { sequenceOf(it, compactOf(it)) }
        .filter { it.isNotEmpty() }.distinct().joinToString("\n")

/**
 * How well a [searchFold]ed display name answers the folded [query]: 0 exact (ignoring
 * punctuation), 1 the name starts with it, 2 a later word does ("The Beatles" for
 * "beatles"), 3 a looser match. Sorts "Queen" above "Queens of the Stone Age".
 */
internal fun matchRank(folded: String, query: String): Int = when {
    folded == query || compactOf(folded) == compactOf(query) -> 0
    folded.startsWith(query) -> 1
    " $folded".contains(" $query") -> 2
    else -> 3
}

/**
 * Match keys for one library list, built once per library content (UI-16) — the search used
 * to lowercase every title / album / artist on each keystroke. Album and artist buckets are
 * built lazily, the first time that facet is searched. [of] memoizes the last library by
 * content: [LibraryRepository.songs] emits a new list on every row change, but the index is
 * reused unless a field that affects search/grouping changed. Album/artist groups come from
 * the shared [GroupMemo], so Search and the Albums/Artists screens compute grouping once.
 */
internal class SearchIndex private constructor(library: List<Song>) {
    /** Latest content-equal list; rebound on a memo hit (see [of]) so the next lookup is an
     *  identity hit. Derived lazies below were built from an equal-content earlier list. */
    @Volatile var library: List<Song> = library
        private set

    /** [searchKey] of each song's title, parallel to [library]. The first line is the
     *  folded title itself (what [matchRank] ranks by). */
    val titleKeys: Array<String> by lazy { Array(library.size) { searchKey(listOf(library[it].title)) } }

    /** The Albums screen's own groups ([albumGroups]: album artist + normalized title, UI-17)
     *  — so two "Greatest Hits" are two results, case/punctuation variants are one, and the
     *  detail opened is the one the Albums tile opens. Matched on every title spelling in
     *  the album; untitled ("Unknown album") groups aren't searchable. */
    val albums: List<Bucket> by lazy {
        GroupMemo.get("Album", library, ::albumGroups).mapNotNull { g ->
            val titles = g.songs.mapNotNull { it.albumTitle?.takeIf(String::isNotBlank) }
            if (titles.isEmpty()) null else Bucket(g, searchKey(titles))
        }
    }

    /** The Artists screen's own groups ([artistGroups]: collaborations filed under their
     *  primary artist, spelling variants merged). Matched by the clean labels. */
    val artists: List<Bucket> by lazy {
        GroupMemo.get("Artist", library, ::artistGroups).map { g -> Bucket(g, searchKey(g.songs.map { it.artistLabel ?: it.artist } + g.title)) }
    }

    /** Forces the lazy keys (a no-op once built). */
    fun warm() { titleKeys; albums; artists }

    /** [key] is the [searchKey] match text for [group]. */
    class Bucket(val group: LibraryGroup, val key: String)

    companion object {
        @Volatile private var last: SearchIndex? = null

        /** Reuses the last index when [library] has the same grouping/search content
         *  ([sameLibraryContent]) — a play-count or unrelated-row emission is a hit. */
        @Synchronized
        fun of(library: List<Song>): SearchIndex {
            last?.let {
                if (sameLibraryContent(it.library, library)) {
                    it.library = library // rebind so the next call is an identity hit
                    return it
                }
            }
            return SearchIndex(library).also { last = it }
        }
    }
}

/** How many rows a scan handles between cancellation checks. */
private const val CANCEL_CHECK_EVERY = 1024

/**
 * Tokenised, case- and accent-insensitive match driven by the chosen [facet]: Artists
 * matches artist names, Albums album titles, Songs track titles — so "search by artist"
 * is exactly that — and All runs all three, each capped to a preview. A value matches if
 * every whitespace-separated query token is a substring of it ("bleed wait" → Wait and
 * Bleed). Each kind is ordered by [matchRank], then name. Artists/albums carry ALL their
 * tracks (not just the matched ones) so the detail is the whole artist/album.
 * Cancellable: a superseded query stops scanning (UI-16).
 */
internal suspend fun search(query: String, index: SearchIndex, facet: SearchFacet): SearchResults {
    val tokens = query.trim().split(SEARCH_WS).filter { it.isNotEmpty() }.map(::searchFold)
    if (tokens.isEmpty()) return SearchResults(facet, emptyList(), emptyList(), emptyList(), query = query)
    val compactTokens = tokens.map(::compactOf)
    // A punctuation-only token ("-" in "ac - dc") has no compact form; it can't be required
    // literally or the folding is undone — skip it unless it's all the query has.
    val hasWordToken = compactTokens.any { it.isNotEmpty() }
    fun matches(key: String): Boolean = tokens.indices.all { i ->
        if (compactTokens[i].isEmpty()) hasWordToken || key.contains(tokens[i])
        else key.contains(tokens[i]) || key.contains(compactTokens[i])
    }
    val folded = tokens.joinToString(" ")
    val ctx = currentCoroutineContext()
    val all = facet == SearchFacet.All

    fun rankedGroups(buckets: List<SearchIndex.Bucket>): List<LibraryGroup> {
        val out = ArrayList<Pair<Int, LibraryGroup>>()
        buckets.forEachIndexed { i, b ->
            if (i % CANCEL_CHECK_EVERY == 0) ctx.ensureActive()
            if (matches(b.key)) out += matchRank(searchFold(b.group.title), folded) to b.group
        }
        return out.sortedWith(compareBy<Pair<Int, LibraryGroup>> { it.first }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.second.title })
            .map { it.second }
    }

    fun rankedSongs(): List<Song> {
        val library = index.library
        val keys = index.titleKeys
        val hits = ArrayList<Pair<Int, Song>>()
        for (i in keys.indices) {
            if (i % CANCEL_CHECK_EVERY == 0) ctx.ensureActive()
            if (matches(keys[i])) hits += matchRank(keys[i].substringBefore('\n'), folded) to library[i]
        }
        return hits.sortedWith(compareBy<Pair<Int, Song>> { it.first }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.second.title })
            .map { it.second }
    }

    val artists = if (all || facet == SearchFacet.Artists) rankedGroups(index.artists) else emptyList()
    val albums = if (all || facet == SearchFacet.Albums) rankedGroups(index.albums) else emptyList()
    val songs = if (all || facet == SearchFacet.Songs) rankedSongs() else emptyList()
    return SearchResults(
        facet = facet,
        artists = artists.take(if (all) ALL_ARTIST_CAP else ARTIST_CAP),
        albums = albums.take(if (all) ALL_ALBUM_CAP else ALBUM_CAP),
        songs = songs.take(if (all) ALL_SONG_CAP else SONG_CAP),
        artistTotal = artists.size,
        albumTotal = albums.size,
        songTotal = songs.size,
        query = query,
    )
}

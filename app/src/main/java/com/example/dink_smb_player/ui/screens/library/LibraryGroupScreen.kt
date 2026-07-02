@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.example.dink_smb_player.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.LocalContentFocus
import com.example.dink_smb_player.LocalRailFocusRequester
import com.example.dink_smb_player.data.index.LibraryGrouping
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.player.PlayerState
import com.example.dink_smb_player.ui.components.CoverArt
import com.example.dink_smb_player.ui.components.GradientButton
import com.example.dink_smb_player.ui.components.synthAlbumFor
import com.example.dink_smb_player.ui.components.ThinLoadingBar
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType

/** A library facet (one album / artist / folder) and its tracks. */
data class LibraryGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val songs: List<Song>,
)

/** Hand-off for the transient [ScreenId.LibraryDetail] screen. A STACK of frames, so
 *  drilling artist → album keeps the artist frame underneath: Back pops the album and
 *  lands back on the artist's album grid before finally leaving to the list screen.
 *  `group`/`facet`/`parent` read the top frame (kept as accessors so callers/crumbs are
 *  unchanged). `DinkNav` is screen-as-state and carries no arguments, hence this singleton. */
object LibraryDetailNav {
    data class Frame(val group: LibraryGroup, val facet: String, val parent: ScreenId)

    val frames = mutableStateListOf<Frame>()

    val group: LibraryGroup? get() = frames.lastOrNull()?.group
    /** Singular facet word for the crumb ("Album" / "Artist" / "Folder"). */
    val facet: String get() = frames.lastOrNull()?.facet ?: "Album"
    /** Parent rail screen, so the drawer highlights it + Back returns there. */
    val parent: ScreenId get() = frames.lastOrNull()?.parent ?: ScreenId.Albums

    /** Enter a facet fresh from a list screen — resets any nested stack. */
    fun open(group: LibraryGroup, facet: String, parent: ScreenId) {
        frames.clear()
        frames.add(Frame(group, facet, parent))
    }

    /** Drill into a sub-facet (album under an artist), keeping the parent frame for Back. */
    fun push(group: LibraryGroup, facet: String, parent: ScreenId) {
        frames.add(Frame(group, facet, parent))
    }

    /** Pop one nested frame. Returns false when only the root frame remains (caller then
     *  navigates back out to the list screen). */
    fun popFrame(): Boolean {
        if (frames.size > 1) {
            frames.removeAt(frames.lastIndex)
            return true
        }
        return false
    }
}

/** Remembers each list facet's scroll position + which tile was opened, so returning from a
 *  detail lands you back where you were (scrolled to, and focused on, the item you opened)
 *  instead of at the top / on the rail. Process-lifetime; keyed by facet ("Album"/…). */
object ListScrollMemo {
    class Pos(
        var index: Int = 0,
        var offset: Int = 0,
        var focusedKey: String? = null,
        /** One-shot: true only when a Back into this list should refocus [focusedKey]. Keeps
         *  rail-hover previews (which also compose the screen) from stealing focus. */
        var armBackFocus: Boolean = false,
    )
    private val map = HashMap<String, Pos>()
    fun pos(facet: String): Pos = map.getOrPut(facet) { Pos() }
    fun saveScroll(facet: String, index: Int, offset: Int) {
        pos(facet).also { it.index = index; it.offset = offset }
    }
    fun setFocused(facet: String, key: String) { pos(facet).focusedKey = key }
    fun clearFocused(facet: String) { map[facet]?.focusedKey = null }
    /** Called by Back when returning to a list — arms the one-shot tile refocus. */
    fun arm(facet: String) { pos(facet).armBackFocus = true }
    /** Facet word for a top-level list screen id, or null if it isn't one. */
    fun facetOf(screen: ScreenId): String? = when (screen) {
        ScreenId.Albums -> "Album"
        ScreenId.Artists -> "Artist"
        ScreenId.Folders -> "Folder"
        else -> null
    }
}

/**
 * Shared list screen for the grouped library facets (Albums / Artists / Folders).
 * Reads the unified index via [LibraryRepository], buckets it with [grouper], and
 * plays a whole group on click — a naturally bounded queue. "Shuffle all" plays the
 * full library shuffled (windowed engine queue keeps it ANR-safe).
 */
@Composable
fun LibraryGroupScreen(
    title: String,
    rowIcon: ImageVector,
    player: PlayerState,
    onNavigate: (ScreenId) -> Unit,
    grouper: (List<Song>) -> List<LibraryGroup>,
    facet: String,
    parent: ScreenId,
    /** Render groups as a cover-art grid (Albums / Artists) rather than icon rows
     *  (Folders) — so the screen reads like a wall of albums, not a folder list. */
    artGrid: Boolean = false,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val railRequester = LocalRailFocusRequester.current
    val contentFocus = LocalContentFocus.current
    val context = LocalContext.current

    val songsFlow = remember(context) { LibraryRepository.songs(context) }
    val songs by songsFlow.collectAsState(
        initial = remember(context) { LibraryRepository.songsNow(context) },
    )
    // False until the on-disk index finishes restoring at boot — drives a loading state
    // so an empty list during restore isn't mistaken for an empty library.
    val restored by LibraryRepository.restoredState.collectAsState()
    // Bucket the (up to 25k-song) library off the main thread — grouper does a
    // groupBy + sortedBy that froze navigation when run inline in composition.
    // null = still computing → show a thin loading bar instead of a blank list.
    // Memoised by facet + song-list identity: [LibraryRepository.songs] is a cached
    // StateFlow, so `songs` is the SAME instance across navigations until the library
    // changes — re-entering Albums/Artists/Folders hits the memo and returns instantly
    // instead of re-running the groupBy + sort every visit.
    val groups: List<LibraryGroup>? by produceState<List<LibraryGroup>?>(initialValue = null, songs, grouper) {
        value = withContext(Dispatchers.Default) { GroupMemo.get(facet, songs, grouper) }
    }
    // Detect libraries with many filename-derived titles (imported before tag reading,
    // or untaggable files) so we can point the user at the Re-read tags maintenance
    // action instead of leaving "()320kbps"-style rows unexplained. Sampled + off-main.
    val untagged: Boolean by produceState(initialValue = false, songs) {
        value = withContext(Dispatchers.Default) { hasUntaggedTracks(songs) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = type.screenTitle.copy(color = palette.ink0), maxLines = 1)
                Text(
                    text = when {
                        !restored && songs.isEmpty() -> "Loading library…"
                        groups == null -> "${songs.size} tracks"
                        groups?.isEmpty() == true -> "Nothing imported yet — add a source and import folders."
                        else -> "${groups?.size ?: 0} ${title.lowercase()} · ${songs.size} tracks"
                    },
                    style = type.body.copy(color = palette.ink2),
                    maxLines = 1,
                )
            }
            if (songs.isNotEmpty()) {
                GradientButton(
                    label = "Shuffle all",
                    leadingIcon = Icons.Filled.Shuffle,
                    onClick = {
                        if (!player.shuffle) player.toggleShuffle()
                        player.playFrom(songs, songs.indices.random())
                        onNavigate(ScreenId.NowPlaying)
                    },
                    height = 48.dp,
                    modifier = Modifier.focusRequester(contentFocus).focusProperties { left = railRequester },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Tap any group → its detail track list (play/shuffle/queue live there). Remember which
        // tile we opened so Back can scroll here and refocus it (see grid/list below).
        val openDetail: (LibraryGroup) -> Unit = { group ->
            ListScrollMemo.setFocused(facet, group.key)
            LibraryDetailNav.open(group, facet, parent)
            onNavigate(ScreenId.LibraryDetail)
        }

        // Untagged-library nudge → Settings → Library (Re-read tags). Only once there
        // are groups to mislabel; hidden on an empty library (the CTA covers that).
        if (untagged && groups?.isNotEmpty() == true) {
            UntaggedHint(onClick = { onNavigate(ScreenId.Settings) })
            Spacer(Modifier.height(12.dp))
        }

        val groupList = groups
        if (groupList == null || (!restored && songs.isEmpty())) {
            ThinLoadingBar()
        } else if (groupList.isEmpty()) {
            // Empty library → a way out, not a dead end. Takes contentFocus (the Shuffle
            // all button isn't shown when there are no songs) so a drawer commit lands here.
            GradientButton(
                label = "Add a source",
                onClick = { onNavigate(ScreenId.SmbShares) },
                height = 48.dp,
                modifier = Modifier.focusRequester(contentFocus).focusProperties { left = railRequester },
            )
        } else if (artGrid) {
            // Fixed column count derived from width (was GridCells.Adaptive) so we KNOW
            // which tiles are in the leftmost column. Only those route Left → rail; every
            // other tile lets Left fall through to the tile on its left (spatial nav).
            // Routing every tile's Left to the rail meant Left anywhere opened the drawer
            // instead of moving across the grid — same bug class as the Settings tab row.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Smaller tiles → more per row. 168dp only fit 5 across and read oversized on
                // a 10-foot TV; 104dp packs ~8 so the wall-of-albums scans faster and more
                // rows fit vertically (square art is as tall as a tile is wide, so narrower
                // tiles are also shorter — the main lever on how many rows are visible).
                val minTile = 104.dp
                val gap = 16.dp
                val columns = maxOf(1, ((maxWidth + gap) / (minTile + gap)).toInt())
                // Restore scroll to where we were when we last left this facet, and save it
                // again on the way out (Back into here should land at the same position).
                val savedPos = remember(facet) { ListScrollMemo.pos(facet) }
                val gridState = rememberLazyGridState(savedPos.index, savedPos.offset)
                DisposableEffect(facet) {
                    onDispose {
                        ListScrollMemo.saveScroll(facet, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                    }
                }
                // When we came BACK from a detail, focus the tile we opened (it's at the restored
                // scroll position, so focusing it doesn't jump the list). Fresh rail entries clear
                // focusedKey (in DinkApp), so this only fires on a return.
                val returnKey = savedPos.focusedKey
                val tileFocus = remember { FocusRequester() }
                LaunchedEffect(groupList) {
                    if (savedPos.armBackFocus && returnKey != null && groupList.any { it.key == returnKey }) {
                        savedPos.armBackFocus = false
                        repeat(20) {
                            delay(30)
                            if (runCatching { tileFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                        }
                    }
                }
                // D-pad Down used to dump focus on the leftmost tile whenever the next row
                // wasn't composed yet (it scrolled, then focus search grabbed column 0). Two
                // things keep the column now: focusRestorer() sends focus back to the tile it
                // left rather than the grid's first child, and a bottom contentPadding keeps the
                // row below already composed so Down has a same-column target to land on.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().focusRestorer(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = minTile + gap),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    gridItemsIndexed(groupList, key = { _, g -> g.key }) { index, group ->
                        GroupTile(
                            group = group,
                            onClick = { openDetail(group) },
                            modifier = Modifier
                                .then(if (group.key == returnKey) Modifier.focusRequester(tileFocus) else Modifier)
                                .then(if (index % columns == 0) Modifier.focusProperties { left = railRequester } else Modifier),
                        )
                    }
                }
            }
        } else {
            val savedPos = remember(facet) { ListScrollMemo.pos(facet) }
            val listState = rememberLazyListState(savedPos.index, savedPos.offset)
            DisposableEffect(facet) {
                onDispose {
                    ListScrollMemo.saveScroll(facet, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                }
            }
            val returnKey = savedPos.focusedKey
            val rowFocus = remember { FocusRequester() }
            LaunchedEffect(groupList) {
                if (savedPos.armBackFocus && returnKey != null && groupList.any { it.key == returnKey }) {
                    savedPos.armBackFocus = false
                    repeat(20) {
                        delay(30)
                        if (runCatching { rowFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(groupList, key = { _, g -> g.key }) { idx, group ->
                    GroupRow(
                        title = group.title,
                        subtitle = group.subtitle,
                        icon = rowIcon,
                        onClick = { openDetail(group) },
                        modifier = (if (group.key == returnKey) Modifier.focusRequester(rowFocus) else Modifier)
                            .focusProperties { left = railRequester },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupTile(
    group: LibraryGroup,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }
    val rep = group.songs.firstOrNull()
    val art = remember(group.key) { rep?.let { synthAlbumFor(it) } }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.bg1,
            focusedContainerColor = palette.bg2,
            contentColor = palette.ink0,
            focusedContentColor = palette.ink0,
        ),
        interactionSource = interaction,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (art != null) {
                CoverArt(
                    song = rep,
                    palette = art.palette,
                    shape = art.shape,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    cornerRadius = 12.dp,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // 2 lines so narrow tiles don't clip long album/artist names to "…"; smaller
                // cardTitle keeps the two lines from dominating the shorter tile.
                Text(group.title, style = type.cardTitle.copy(color = palette.ink0, fontSize = 17.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(group.subtitle, style = type.monoSmall.copy(color = palette.ink3), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GroupRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
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
        modifier = modifier.fillMaxWidth().height(64.dp),
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
                Icon(imageVector = icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = type.songTitleCompact.copy(color = palette.ink0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = type.monoSmall.copy(color = palette.ink3), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Process-level memo of computed facet groupings, keyed by facet + the identity of the
 * song list. Because [LibraryRepository.songs] hands out a cached StateFlow, its value is
 * the same `List<Song>` instance across navigations until the library actually mutates —
 * so re-opening Albums/Artists/Folders returns the already-bucketed groups instead of
 * re-running the whole-library groupBy + sort. A real library change swaps the instance →
 * identity miss → exactly one recompute. One entry per facet (3 total), so it's tiny.
 */
private object GroupMemo {
    private val cache = HashMap<String, Pair<Int, List<LibraryGroup>>>()

    @Synchronized
    fun get(
        facet: String,
        songs: List<Song>,
        compute: (List<Song>) -> List<LibraryGroup>,
    ): List<LibraryGroup> {
        val token = System.identityHashCode(songs)
        cache[facet]?.let { (cachedToken, cached) -> if (cachedToken == token) return cached }
        val result = compute(songs)
        cache[facet] = token to result
        return result
    }
}

@Composable
private fun UntaggedHint(onClick: () -> Unit) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val railRequester = LocalRailFocusRequester.current
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.accent.copy(alpha = 0.12f),
            focusedContainerColor = palette.bg2,
            contentColor = palette.ink0,
            focusedContentColor = palette.ink0,
        ),
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().height(52.dp).focusProperties { left = railRequester },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.AutoFixHigh, null, tint = palette.accent, modifier = Modifier.size(20.dp))
            Text(
                text = "Some tracks show filenames instead of titles. Open Settings → Library to re-read tags.",
                style = type.body.copy(color = palette.ink1),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Cheap, sampled heuristic: does a meaningful share of the library still show
 *  filename-derived titles (or zero durations)? Bounds work to the first 400 tracks so
 *  it stays off the critical path even on a 25k library. */
fun hasUntaggedTracks(songs: List<Song>): Boolean {
    if (songs.isEmpty()) return false
    var bad = 0
    var n = 0
    for (s in songs.asSequence().take(400)) {
        n++
        val stem = s.sourcePath.substringAfterLast('/').substringBeforeLast('.')
        if (s.title.equals(stem, ignoreCase = true) || s.title.contains("kbps", ignoreCase = true) || s.durationSec <= 0) bad++
    }
    return n > 0 && bad * 100 / n >= 25
}

// ---- Grouping helpers: one per facet, shared bucket builder. ----

// Grouping keys are PRECOMPUTED at import/retag by data.index.LibraryGrouping and stored on each
// row (Song.artistKey/albumKey/artistLabel), so the grouping below is a plain groupBy on a stored
// key rather than an NFD + regex normalization of 25k rows at display time. LibraryGrouping.normKey
// is used only as a fallback for the rare row that predates precompute (null keys). See that file
// for the collapsing rules (case/The/feat/punctuation/accents) and collaboration attribution.

/** The most frequent raw spelling among a group's tracks — the canonical display label. */
private fun <T> Iterable<T>.mostCommon(): T =
    groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key

private fun buildGroups(
    songs: List<Song>,
    keyOf: (Song) -> String,
    titleOf: (String, List<Song>) -> String,
    subtitleOf: (String, List<Song>) -> String,
): List<LibraryGroup> =
    songs.groupBy(keyOf)
        .map { (key, list) -> LibraryGroup(key, titleOf(key, list), subtitleOf(key, list), list) }
        .sortedBy { it.title.lowercase() }

// Grouping now reads the PRECOMPUTED keys stored on each Song (filled at import/retag by
// LibraryGrouping — collaborations already attributed to their primary artist there). The
// `?: LibraryGrouping.normKey(...)` fallbacks only fire for a row that predates precompute; a
// migration on the next restore fills those, after which every key is present.

/** An artist bucket's display label: the most common precomputed clean spelling (never a
 *  collaboration/featured string), falling back to the raw artist only for pre-precompute rows. */
private fun artistLabelOf(list: List<Song>): String =
    list.mapNotNull { it.artistLabel }.ifEmpty { list.map { it.artist } }.mostCommon()

fun albumGroups(songs: List<Song>): List<LibraryGroup> =
    buildGroups(
        songs,
        keyOf = { it.albumKey ?: LibraryGrouping.normKey(it.albumTitle ?: "Unknown album") },
        titleOf = { _, list ->
            list.mapNotNull { it.albumTitle }.takeIf { it.isNotEmpty() }?.mostCommon() ?: "Unknown album"
        },
        subtitleOf = { _, list ->
            // "Various artists" only when the PRIMARY artists genuinely differ — featured
            // guests and spelling variants collapse to one primary key so they don't read
            // as a compilation.
            val keys = list.map { it.artistKey ?: LibraryGrouping.normKey(it.artist) }.distinct()
            val artist = if (keys.size == 1) artistLabelOf(list) else "Various artists"
            "$artist · ${list.size} tracks"
        },
    )

fun artistGroups(songs: List<Song>): List<LibraryGroup> =
    buildGroups(
        songs,
        keyOf = { it.artistKey ?: LibraryGrouping.normKey(it.artist) },
        titleOf = { _, list -> artistLabelOf(list) },
        subtitleOf = { _, list ->
            val albums = list.map { it.albumKey ?: LibraryGrouping.normKey(it.albumTitle ?: "") }.distinct().size
            "${list.size} tracks · $albums albums"
        },
    )

fun folderGroups(songs: List<Song>): List<LibraryGroup> = buildGroups(
    songs,
    keyOf = { it.sourcePath.substringBeforeLast('/', "").ifEmpty { "/" } },
    titleOf = { key, _ -> key.substringAfterLast('/').ifEmpty { key } },
    subtitleOf = { key, list -> "$key · ${list.size} tracks" },
)

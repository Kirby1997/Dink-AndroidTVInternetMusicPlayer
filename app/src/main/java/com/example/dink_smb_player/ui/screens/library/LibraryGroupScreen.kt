@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

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
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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

/** Hand-off for the transient [ScreenId.LibraryDetail] screen: which facet the user
 *  tapped on Albums/Artists/Folders. Mirrors the `activeBrowse*` singletons the SMB /
 *  cloud browsers use — `DinkNav` is screen-as-state and carries no arguments. */
object LibraryDetailNav {
    var group: LibraryGroup? by mutableStateOf(null)
    /** Singular facet word for the crumb ("Album" / "Artist" / "Folder"). */
    var facet: String by mutableStateOf("Album")
    /** Parent rail screen, so the drawer highlights it + Back returns there. */
    var parent: ScreenId by mutableStateOf(ScreenId.Albums)
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

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = type.screenTitle.copy(color = palette.ink0), maxLines = 1)
                Text(
                    text = when {
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

        Spacer(Modifier.height(20.dp))

        // Tap any group → its detail track list (play/shuffle/queue live there).
        val openDetail: (LibraryGroup) -> Unit = { group ->
            LibraryDetailNav.group = group
            LibraryDetailNav.facet = facet
            LibraryDetailNav.parent = parent
            onNavigate(ScreenId.LibraryDetail)
        }

        // Untagged-library nudge → Settings → Library (Re-read tags). Only once there
        // are groups to mislabel; hidden on an empty library (the CTA covers that).
        if (untagged && groups?.isNotEmpty() == true) {
            UntaggedHint(onClick = { onNavigate(ScreenId.Settings) })
            Spacer(Modifier.height(12.dp))
        }

        val groupList = groups
        if (groupList == null) {
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
                val minTile = 168.dp
                val gap = 16.dp
                val columns = maxOf(1, ((maxWidth + gap) / (minTile + gap)).toInt())
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    itemsIndexed(groupList, key = { _, g -> g.key }) { index, group ->
                        GroupTile(
                            group = group,
                            onClick = { openDetail(group) },
                            modifier = if (index % columns == 0) {
                                Modifier.focusProperties { left = railRequester }
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(groupList, key = { _, g -> g.key }) { idx, group ->
                    GroupRow(
                        title = group.title,
                        subtitle = group.subtitle,
                        icon = rowIcon,
                        onClick = { openDetail(group) },
                        modifier = Modifier.focusProperties { left = railRequester },
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
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                Text(group.title, style = type.cardTitle.copy(color = palette.ink0), maxLines = 1, overflow = TextOverflow.Ellipsis)
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

private fun buildGroups(
    songs: List<Song>,
    keyOf: (Song) -> String,
    titleOf: (String) -> String,
    subtitleOf: (String, List<Song>) -> String,
): List<LibraryGroup> =
    songs.groupBy(keyOf)
        .map { (key, list) -> LibraryGroup(key, titleOf(key), subtitleOf(key, list), list) }
        .sortedBy { it.title.lowercase() }

fun albumGroups(songs: List<Song>): List<LibraryGroup> = buildGroups(
    songs,
    keyOf = { it.albumTitle ?: "Unknown album" },
    titleOf = { it },
    subtitleOf = { _, list ->
        val artist = list.map { it.artist }.distinct().singleOrNull() ?: "Various artists"
        "$artist · ${list.size} tracks"
    },
)

fun artistGroups(songs: List<Song>): List<LibraryGroup> = buildGroups(
    songs,
    keyOf = { it.artist },
    titleOf = { it },
    subtitleOf = { _, list ->
        val albums = list.mapNotNull { it.albumTitle }.distinct().size
        "${list.size} tracks · $albums albums"
    },
)

fun folderGroups(songs: List<Song>): List<LibraryGroup> = buildGroups(
    songs,
    keyOf = { it.sourcePath.substringBeforeLast('/', "").ifEmpty { "/" } },
    titleOf = { it.substringAfterLast('/').ifEmpty { it } },
    subtitleOf = { key, list -> "$key · ${list.size} tracks" },
)

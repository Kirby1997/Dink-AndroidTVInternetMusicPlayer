@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.example.dink_smb_player.ui.screens.library

import android.content.Context
import android.os.storage.StorageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.LocalContentFocus
import com.example.dink_smb_player.LocalRailFocusRequester
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.data.prefs.SharePrefs
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.player.PlayerState
import com.example.dink_smb_player.ui.components.GradientButton
import com.example.dink_smb_player.ui.components.MenuRow
import com.example.dink_smb_player.ui.components.SongContextMenu
import com.example.dink_smb_player.ui.components.ThinLoadingBar
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** ListScrollMemo / LibraryDetailNav facet word for this screen (Back from a detail arms it). */
private const val FACET = "Folder"
private val INDENT = 28.dp

/**
 * Library → Folders as a directory tree: one top-level row per source (SMB share / local
 * volume), then its directories, with tracks as leaves. Empty single-child directory chains
 * are compacted into one row ("Music / Artists"). Tree model + key decisions live in
 * [FolderTree] / [TreeKeys] (pure, unit-tested); this file is only rendering + focus.
 *
 * D-pad: OK toggles a folder / plays a track (its folder as the queue); Right expands, or
 * steps into the first child of an open folder; Left collapses, or jumps to the parent row;
 * Left on a collapsed top-level row goes to the rail. Hold OK / Menu: folder → Play / Shuffle /
 * Open as list, track → the song context menu.
 */
@Composable
fun FolderTreeScreen(
    player: PlayerState,
    onNavigate: (ScreenId) -> Unit,
    onToast: (String) -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val railRequester = LocalRailFocusRequester.current
    val contentFocus = LocalContentFocus.current
    val context = LocalContext.current

    // Cached hot StateFlow — the same list instance across navigations (FolderTreeMemo's
    // identity fast path), never a cold per-visit flow.
    val songs by remember(context) { LibraryRepository.songs(context) }.collectAsState()
    val restored by LibraryRepository.restoredState.collectAsState()

    // Source display names: SMB shares by mount path, local volumes by directory. null until
    // read, so the tree isn't built twice (once with fallback labels, again with real ones).
    val labels: Map<String, String>? by produceState<Map<String, String>?>(null, context) {
        val volumes = withContext(Dispatchers.IO) { volumeLabels(context) }
        SharePrefs(context.applicationContext).shares.collect { shares ->
            value = volumes + shares.associate { it.mountPath.trimEnd('/').lowercase() to it.name }
        }
    }

    val roots: List<FolderNode>? by produceState<List<FolderNode>?>(null, songs, labels) {
        val l = labels ?: return@produceState
        value = withContext(Dispatchers.Default) { FolderTreeMemo.get(songs, l) }
    }

    var expanded by remember { mutableStateOf(FolderTreeMemo.expanded) }
    fun setExpanded(next: Set<String>) {
        expanded = next
        FolderTreeMemo.expanded = next
    }
    // Forget folders that vanished from the library (keeps the memo from growing stale).
    LaunchedEffect(roots) {
        roots?.let { r -> FolderTree.pruneExpanded(r, expanded).let { if (it !== expanded) setExpanded(it) } }
    }

    // Visible rows: O(visible) walk, cheap enough to redo on each toggle.
    val rows: List<TreeRow>? = remember(roots, expanded) { roots?.let { FolderTree.flatten(it, expanded) } }

    val savedPos = remember { ListScrollMemo.pos(FACET) }
    val listState = rememberLazyListState(savedPos.index, savedPos.offset)
    DisposableEffect(Unit) {
        onDispose { ListScrollMemo.saveScroll(FACET, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }

    // One target FocusRequester, attached to whichever row [pendingFocus] names. Used for
    // Left → parent, Right → first child, and the Back-from-detail restore.
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    val targetFocus = remember { FocusRequester() }
    LaunchedEffect(rows) {
        val list = rows ?: return@LaunchedEffect
        // One-shot (armed by app Back from a detail opened here): refocus the row we left from.
        if (savedPos.armBackFocus) {
            savedPos.armBackFocus = false
            val key = savedPos.focusedKey
            if (key != null && list.any { it.key == key }) pendingFocus = key
        }
    }
    LaunchedEffect(pendingFocus, rows) {
        val key = pendingFocus ?: return@LaunchedEffect
        val list = rows ?: return@LaunchedEffect
        val idx = list.indexOfFirst { it.key == key }
        if (idx < 0) { pendingFocus = null; return@LaunchedEffect }
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == idx }) listState.scrollToItem(idx)
        repeat(20) {
            if (runCatching { targetFocus.requestFocus() }.isSuccess) { pendingFocus = null; return@LaunchedEffect }
            delay(30)
        }
        pendingFocus = null
    }

    var menuSong by remember { mutableStateOf<Song?>(null) }
    var menuFolder by remember { mutableStateOf<FolderNode?>(null) }

    /** [shuffle] null keeps the player's current shuffle mode (a track pick, as on Songs);
     *  true/false force it (Shuffle / Play folder, as on the detail screen). */
    fun play(tracks: List<Song>, start: Int, shuffle: Boolean?) {
        if (tracks.isEmpty()) return
        if (shuffle != null && player.shuffle != shuffle) player.toggleShuffle()
        player.playFrom(tracks, start)
        onNavigate(ScreenId.NowPlaying)
    }

    fun onKey(nav: TreeNav): Boolean = when (nav) {
        TreeNav.PassThrough -> false
        TreeNav.Consume -> true
        is TreeNav.Expand -> { setExpanded(expanded + nav.folderId); true }
        is TreeNav.Collapse -> { setExpanded(expanded - nav.folderId); true }
        is TreeNav.FocusRow -> { pendingFocus = nav.rowKey; true }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Folders", style = type.screenTitle.copy(color = palette.ink0), maxLines = 1)
                Text(
                    text = when {
                        !restored && songs.isEmpty() -> "Loading library…"
                        roots == null -> "${songs.size} tracks"
                        roots?.isEmpty() == true -> "Nothing imported yet — add a source and import folders."
                        else -> roots!!.size.let { n -> "$n ${if (n == 1) "source" else "sources"} · ${songs.size} tracks" }
                    },
                    style = type.body.copy(color = palette.ink2),
                    maxLines = 1,
                )
            }
            if (songs.isNotEmpty()) {
                GradientButton(
                    label = "Shuffle all",
                    leadingIcon = Icons.Filled.Shuffle,
                    onClick = { play(songs, songs.indices.random(), shuffle = true) },
                    height = 48.dp,
                    modifier = Modifier.focusRequester(contentFocus).focusProperties { left = railRequester },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        val list = rows
        if (list == null || (!restored && songs.isEmpty())) {
            ThinLoadingBar()
        } else if (list.isEmpty()) {
            GradientButton(
                label = "Add a source",
                onClick = { onNavigate(ScreenId.SmbShares) },
                height = 48.dp,
                modifier = Modifier.focusRequester(contentFocus).focusProperties { left = railRequester },
            )
        } else {
            LazyColumn(
                state = listState,
                // focusGroup keeps focus inside the list while a fast scroll recycles rows
                // (otherwise the rail grabs it mid-fling — same as SongsScreen).
                modifier = Modifier.fillMaxSize().focusGroup(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(list, key = { _, r -> r.key }, contentType = { _, r -> r::class }) { index, row ->
                    val rowModifier = Modifier
                        .then(if (row.key == pendingFocus) Modifier.focusRequester(targetFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused || it.hasFocus) ListScrollMemo.setFocused(FACET, row.key) }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (ev.key) {
                                Key.DirectionLeft -> onKey(TreeKeys.left(list, index))
                                Key.DirectionRight -> onKey(TreeKeys.right(list, index))
                                Key.Menu -> {
                                    when (row) {
                                        is TreeRow.Folder -> menuFolder = row.node
                                        is TreeRow.Track -> menuSong = row.song
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        // Only a top-level row hands Left to the rail (and only when the key
                        // isn't consumed above, i.e. it's collapsed). Everything else is
                        // handled in-tree; Cancel stops focus search wrapping into the drawer.
                        .focusProperties {
                            left = if (row.parentKey == null) railRequester else FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        }
                    when (row) {
                        is TreeRow.Folder -> FolderRow(
                            row = row,
                            onClick = {
                                setExpanded(if (row.expanded) expanded - row.node.id else expanded + row.node.id)
                            },
                            onLongClick = { menuFolder = row.node },
                            modifier = rowModifier,
                        )
                        is TreeRow.Track -> TrackTreeRow(
                            row = row,
                            isPlaying = player.currentSong?.id == row.song.id,
                            onClick = { play(row.folder.tracks, row.indexInFolder, shuffle = null) },
                            onLongClick = { menuSong = row.song },
                            modifier = rowModifier,
                        )
                    }
                }
            }
        }
    }

    menuSong?.let { song ->
        SongContextMenu(song = song, player = player, onToast = onToast, onDismiss = { menuSong = null })
    }
    menuFolder?.let { node ->
        FolderActionMenu(
            node = node,
            onPlay = { menuFolder = null; play(node.allTracks(), 0, shuffle = false) },
            onShuffle = {
                menuFolder = null
                val all = node.allTracks()
                if (all.isNotEmpty()) play(all, all.indices.random(), shuffle = true)
            },
            onOpenList = {
                menuFolder = null
                val all = node.allTracks()
                ListScrollMemo.setFocused(FACET, TreeRow.folderKey(node.id))
                LibraryDetailNav.open(
                    LibraryGroup(
                        key = node.id,
                        title = node.name.substringAfterLast(" / "),
                        subtitle = "${node.id} · ${all.size} tracks",
                        songs = all,
                    ),
                    FACET,
                    ScreenId.Folders,
                )
                onNavigate(ScreenId.LibraryDetail)
            },
            onDismiss = { menuFolder = null },
        )
    }
}

@Composable
private fun FolderRow(
    row: TreeRow.Folder,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val node = row.node
    TreeRowSurface(onClick = onClick, onLongClick = onLongClick, depth = row.depth, modifier = modifier) {
        Icon(
            imageVector = if (row.expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
            contentDescription = if (row.expanded) "Collapse" else "Expand",
            tint = palette.ink2,
            modifier = Modifier.size(22.dp),
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(palette.accent.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val icon: ImageVector = when {
                node.isRoot && node.id.startsWith("//") -> Icons.Outlined.Storage
                node.isRoot -> Icons.Outlined.SdStorage
                row.expanded -> Icons.Outlined.FolderOpen
                else -> Icons.Outlined.Folder
            }
            Icon(imageVector = icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(20.dp))
        }
        Text(
            text = node.name,
            style = type.songTitleCompact.copy(color = palette.ink0),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${node.trackCount} ${if (node.trackCount == 1) "track" else "tracks"}",
            style = type.monoSmall.copy(color = palette.ink3),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun TrackTreeRow(
    row: TreeRow.Track,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val song = row.song
    TreeRowSurface(onClick = onClick, onLongClick = onLongClick, depth = row.depth, highlighted = isPlaying, modifier = modifier) {
        // Chevron-width gap so a track's icon lines up under its sibling folders' icons.
        Spacer(Modifier.width(22.dp))
        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.MusicNote,
                contentDescription = null,
                tint = if (isPlaying) palette.accent else palette.ink3,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = song.title,
                style = type.songTitleCompact.copy(color = if (isPlaying) palette.accent else palette.ink0),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = type.monoSmall.copy(color = palette.ink3),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = if (song.durationSec > 0) "%d:%02d".format(song.durationSec / 60, song.durationSec % 60) else "—",
            style = type.mono.copy(color = palette.ink1),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Full-width focus surface shared by folder + track rows; depth indents the content, not
 *  the surface, so every row spans the same width and Up/Down never drift sideways. */
@Composable
private fun TreeRowSurface(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    depth: Int,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = LocalDinkPalette.current
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (highlighted) palette.bg1 else Color.Transparent,
            focusedContainerColor = palette.bg2,
            contentColor = palette.ink0,
            focusedContentColor = palette.ink0,
        ),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().height(60.dp),
    ) {
        Row(
            // fillMaxHeight, not fillMaxSize: a fillMaxSize child of a Row eats its width.
            modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(start = 12.dp + INDENT * depth, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/** Hold-OK / Menu on a folder: play it all, shuffle it, or open it as a flat track list. */
@Composable
private fun FolderActionMenu(
    node: FolderNode,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onOpenList: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(40)
        runCatching { firstRow.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .background(palette.bg1, RoundedCornerShape(16.dp))
                .border(1.dp, palette.lineStrong, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(node.name, style = type.songTitleCompact.copy(color = palette.ink0), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${node.trackCount} tracks", style = type.monoSmall.copy(color = palette.ink3), maxLines = 1)
            Spacer(Modifier.height(4.dp))
            MenuRow("Play folder", Icons.Filled.PlayArrow, firstRow, onClick = onPlay)
            MenuRow("Shuffle folder", Icons.Filled.Shuffle, onClick = onShuffle)
            MenuRow("Open as list", Icons.Outlined.QueueMusic, onClick = onOpenList)
        }
    }
}

/** Local volume display names keyed by lowercased directory ("/storage/emulated/0" → …). */
private fun volumeLabels(context: Context): Map<String, String> = runCatching {
    val sm = context.getSystemService(StorageManager::class.java) ?: return@runCatching emptyMap()
    sm.storageVolumes.mapNotNull { v ->
        val dir = v.directory?.path ?: return@mapNotNull null
        dir.trimEnd('/').lowercase() to v.getDescription(context)
    }.toMap()
}.getOrDefault(emptyMap())

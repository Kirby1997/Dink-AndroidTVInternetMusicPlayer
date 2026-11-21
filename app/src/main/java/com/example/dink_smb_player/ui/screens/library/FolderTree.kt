package com.example.dink_smb_player.ui.screens.library

import com.example.dink_smb_player.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * Pure (non-Compose) model behind the Folders directory tree: build a source → directory →
 * track tree from the library, compact empty single-child directory chains, count tracks
 * recursively, flatten the visible rows for a LazyColumn, and decide what Left/Right do.
 * Kept free of Android/Compose types so the JVM unit tests cover all of it.
 */

/** One directory (or a source root) in the tree. Immutable once built. */
class FolderNode(
    /** Stable identity of the deepest directory this row stands for (a compacted
     *  "Music / Artists" row is keyed by its "Artists" dir) — see [FolderTree.nodeId].
     *  Expanded-state and focus memos are keyed by it. */
    val id: String,
    /** Row label. Source roots carry the source's display name; a compacted chain joins its
     *  segment names with " / ". */
    val name: String,
    /** The deepest directory's own name (the last segment of a compacted chain; the source
     *  label for a root) — the title when this folder is opened as a list. */
    val leafName: String,
    val isRoot: Boolean,
    /** Sub-directories, case-insensitively sorted by name. */
    val children: List<FolderNode>,
    /** Tracks directly in this directory, sorted by file name (keeps "01 …", "02 …" order). */
    val tracks: List<Song>,
) {
    /** Tracks in this directory and every directory below it. */
    val trackCount: Int = tracks.size + children.sumOf { it.trackCount }

    /** Every track at or below this directory, in display order (sub-folders first, then
     *  this folder's own tracks) — the queue for "Play folder" / "Open as list". */
    fun allTracks(): List<Song> {
        val out = ArrayList<Song>(trackCount)
        fun walk(n: FolderNode) {
            n.children.forEach(::walk)
            out.addAll(n.tracks)
        }
        walk(this)
        return out
    }
}

/** A visible row of the flattened tree. [key] is unique across the whole list (LazyColumn key). */
sealed class TreeRow {
    abstract val key: String
    abstract val depth: Int
    /** Row key of the containing folder, or null for a top-level source row. */
    abstract val parentKey: String?

    data class Folder(
        val node: FolderNode,
        override val depth: Int,
        val expanded: Boolean,
        override val parentKey: String?,
    ) : TreeRow() {
        override val key: String get() = folderKey(node.id)
    }

    data class Track(
        val song: Song,
        /** The folder holding this track: OK plays [FolderNode.tracks] from [indexInFolder]. */
        val folder: FolderNode,
        val indexInFolder: Int,
        override val depth: Int,
        override val parentKey: String?,
    ) : TreeRow() {
        override val key: String get() = trackKey(song.id)
    }

    companion object {
        fun folderKey(id: String) = "d:$id"
        fun trackKey(songId: String) = "t:$songId"
    }
}

/** Where a track's path splits into its source root and the directories inside it.
 *  [rootKey] is the source identity (lowercased for SMB, whose paths are case-insensitive);
 *  [caseInsensitive] says directory segments should be matched ignoring case too. */
internal data class SourceSplit(
    val rootKey: String,
    val rootLabel: String,
    val segments: List<String>,
    val caseInsensitive: Boolean = false,
)

object FolderTree {

    private const val INTERNAL_LABEL = "Internal storage"
    private const val COMPACT_SEPARATOR = " / "
    /** Separates the source root from the directory path inside a node id. A control char can't
     *  appear in a share/volume/dir name, so ids from different sources never collide (a
     *  "/" root's "Music" dir vs an SMB host called "music", say). */
    private const val ROOT_SEP = '\u0001'

    /** Node id for the directory [dirKeys] (already case-folded where the source needs it)
     *  under source [rootKey]; empty [dirKeys] = the source root itself. */
    fun nodeId(rootKey: String, dirKeys: List<String>): String =
        rootKey + ROOT_SEP + dirKeys.joinToString("/")

    /** The source root key a node id belongs to. */
    fun rootKeyOf(id: String): String = id.substringBefore(ROOT_SEP)

    /**
     * Split a [Song.sourcePath] into source root + directory segments (+ file name last).
     *
     * - SMB: `//host/share/dir/file` → root `//host/share` (keyed lowercase: SMB is
     *   case-insensitive), labelled by the share's display name from [labels] (keyed by mount
     *   path), else "share on host" in the path's own spelling.
     * - Local: `/storage/emulated/N/…` (and `/sdcard/…`) → internal storage;
     *   `/storage/<VOLUME>/…` → that removable volume (USB / SD), labelled from [labels]
     *   (keyed by volume directory) else "USB / SD (VOLUME)".
     * - Anything else absolute → a "Device" root; a relative path → its first segment.
     *
     * [labels] keys must be lowercase.
     */
    internal fun splitSource(rawPath: String, labels: Map<String, String>): SourceSplit {
        val path = rawPath.replace('\\', '/')
        val segs = path.split('/').filter { it.isNotEmpty() }
        fun label(root: String, fallback: String) = labels[root.lowercase()] ?: fallback
        return when {
            path.startsWith("//") && segs.size >= 3 -> {
                val root = "//${segs[0]}/${segs[1]}".lowercase()
                SourceSplit(root, label(root, "${segs[1]} on ${segs[0]}"), segs.drop(2), caseInsensitive = true)
            }
            path.startsWith("//") && segs.isNotEmpty() -> {
                val root = "//${segs[0]}".lowercase()
                SourceSplit(root, label(root, segs[0]), segs.drop(1), caseInsensitive = true)
            }
            path.startsWith("/storage/emulated/") && segs.size >= 4 -> {
                val root = "/storage/emulated/${segs[2]}"
                SourceSplit(root, label(root, INTERNAL_LABEL), segs.drop(3))
            }
            path.startsWith("/sdcard/") -> SourceSplit("/sdcard", label("/sdcard", INTERNAL_LABEL), segs.drop(1))
            path.startsWith("/storage/") && segs.size >= 3 && segs[1] != "emulated" && segs[1] != "self" -> {
                val root = "/storage/${segs[1]}"
                SourceSplit(root, label(root, "USB / SD (${segs[1]})"), segs.drop(2))
            }
            path.startsWith("/") -> SourceSplit("/", label("/", "Device"), segs)
            segs.size >= 2 -> SourceSplit(segs[0], label(segs[0], segs[0]), segs.drop(1))
            else -> SourceSplit("", label("", "Local storage"), segs)
        }
    }

    /** [name] is the first spelling seen; children are keyed by the (case-folded) segment. */
    private class Builder(val id: String, val name: String) {
        val children = LinkedHashMap<String, Builder>()
        val tracks = ArrayList<Song>()
    }

    /**
     * Build the tree: one root per source, then directories, tracks as leaves. Directory
     * chains with no tracks and a single sub-directory are compacted into one row (roots are
     * never merged into their child, so every source keeps its own labelled row). Roots sort
     * by label, directories by name, tracks by file name — all case-insensitive. On SMB,
     * roots and directories differing only in case are one node, shown in the first spelling.
     */
    fun build(songs: List<Song>, labels: Map<String, String> = emptyMap()): List<FolderNode> {
        val lowered = if (labels.keys.all { it == it.lowercase() }) labels
            else labels.mapKeys { it.key.lowercase() }
        val roots = LinkedHashMap<String, Builder>()
        for (song in songs) {
            val split = splitSource(song.sourcePath, lowered)
            var node = roots.getOrPut(split.rootKey) { Builder(nodeId(split.rootKey, emptyList()), split.rootLabel) }
            // Last segment is the file name; everything before it is a directory.
            val dirs = if (split.segments.isEmpty()) emptyList() else split.segments.dropLast(1)
            val keys = ArrayList<String>(dirs.size)
            for (dir in dirs) {
                val key = if (split.caseInsensitive) dir.lowercase() else dir
                keys.add(key)
                node = node.children.getOrPut(key) { Builder(nodeId(split.rootKey, keys), dir) }
            }
            node.tracks.add(song)
        }
        return roots.values
            .map { freeze(it, isRoot = true) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private val trackOrder: Comparator<Song> =
        compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { fileNameOf(it) }.thenBy { it.id }

    internal fun fileNameOf(song: Song): String =
        song.sourcePath.replace('\\', '/').substringAfterLast('/')

    private fun freeze(b: Builder, isRoot: Boolean): FolderNode {
        var cur = b
        var name = b.name
        if (!isRoot) {
            // Compact: an empty directory with exactly one sub-directory folds into it.
            while (cur.tracks.isEmpty() && cur.children.size == 1) {
                cur = cur.children.values.first()
                name += COMPACT_SEPARATOR + cur.name
            }
        }
        return FolderNode(
            id = cur.id,
            name = name,
            leafName = cur.name,
            isRoot = isRoot,
            children = cur.children.values
                .map { freeze(it, isRoot = false) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            tracks = cur.tracks.sortedWith(trackOrder),
        )
    }

    /** Flatten the tree into the rows currently visible given which folder ids are [expanded]. */
    fun flatten(roots: List<FolderNode>, expanded: Set<String>): List<TreeRow> {
        val out = ArrayList<TreeRow>()
        fun visit(node: FolderNode, depth: Int, parentKey: String?) {
            val open = node.id in expanded
            val row = TreeRow.Folder(node, depth, open, parentKey)
            out.add(row)
            if (!open) return
            node.children.forEach { visit(it, depth + 1, row.key) }
            node.tracks.forEachIndexed { i, song ->
                out.add(TreeRow.Track(song, node, i, depth + 1, row.key))
            }
        }
        roots.forEach { visit(it, 0, null) }
        return out
    }

    /**
     * Drop expanded ids whose source is still in the tree but whose folder is gone. Ids under a
     * source that's absent right now are KEPT — a partial library (restore, re-import, a monitor
     * pass mid-walk) mustn't wipe the user's open folders. An empty tree prunes nothing.
     * Returns the same instance when nothing changes.
     */
    fun pruneExpanded(roots: List<FolderNode>, expanded: Set<String>): Set<String> {
        if (expanded.isEmpty() || roots.isEmpty()) return expanded
        val ids = HashSet<String>()
        fun collect(n: FolderNode) { ids.add(n.id); n.children.forEach(::collect) }
        roots.forEach(::collect)
        val rootKeys = roots.mapTo(HashSet()) { rootKeyOf(it.id) }
        fun keep(id: String) = id in ids || rootKeyOf(id) !in rootKeys
        return if (expanded.all(::keep)) expanded else expanded.filterTo(HashSet(), ::keep)
    }
}

/**
 * Process-lifetime memo for the Folders screen: the built tree (rebuilt only when the
 * library's grouping-relevant content or the source labels change — same content check as
 * [GroupMemo]) and the expanded folder ids, so leaving and re-entering Folders keeps the tree
 * open where it was.
 */
internal object FolderTreeMemo {
    private class Entry(val songs: List<Song>, val labels: Map<String, String>, val roots: List<FolderNode>)
    private var entry: Entry? = null

    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    /** Expanded folder ids. Every change is an atomic read-modify-write on the CURRENT value,
     *  so key-repeat toggles landing before a recomposition are never lost. */
    val expanded: StateFlow<Set<String>> = _expanded.asStateFlow()

    fun expand(id: String) = _expanded.update { it + id }
    fun collapse(id: String) = _expanded.update { it - id }
    fun toggle(id: String) = _expanded.update { if (id in it) it - id else it + id }
    fun prune(roots: List<FolderNode>) = _expanded.update { FolderTree.pruneExpanded(roots, it) }

    @Synchronized
    fun get(songs: List<Song>, labels: Map<String, String>): List<FolderNode> {
        entry?.let {
            if (it.labels == labels && sameLibraryContent(it.songs, songs)) {
                if (it.songs !== songs) entry = Entry(songs, labels, it.roots)
                return it.roots
            }
        }
        val roots = FolderTree.build(songs, labels)
        entry = Entry(songs, labels, roots)
        return roots
    }

    @Synchronized
    fun clear() { entry = null; _expanded.value = emptySet() }
}

/** What a D-pad key does on a tree row. */
sealed class TreeNav {
    /** Let the key fall through to normal focus search (rail for Left on a top-level row). */
    data object PassThrough : TreeNav()
    /** Swallow the key; nothing happens. */
    data object Consume : TreeNav()
    data class Expand(val folderId: String) : TreeNav()
    data class Collapse(val folderId: String) : TreeNav()
    data class FocusRow(val rowKey: String) : TreeNav()
}

/**
 * Left/Right decisions. They take the CURRENT expanded set (read at key time), not the
 * row's composed `expanded` flag, so a burst of key repeats before the list recomposes still
 * acts on the real state.
 */
object TreeKeys {
    /** Left: collapse an open folder; else jump to the parent row; a collapsed top-level row
     *  passes Left through so focus goes to the nav rail. */
    fun left(row: TreeRow, expanded: Set<String>): TreeNav {
        if (row is TreeRow.Folder && row.node.id in expanded) return TreeNav.Collapse(row.node.id)
        return row.parentKey?.let { TreeNav.FocusRow(it) } ?: TreeNav.PassThrough
    }

    /** Right: expand a closed folder; on an open one move into its first child (sub-folders
     *  are listed before tracks); no-op on tracks. */
    fun right(row: TreeRow, expanded: Set<String>): TreeNav {
        if (row !is TreeRow.Folder) return TreeNav.Consume
        val node = row.node
        if (node.id !in expanded) return TreeNav.Expand(node.id)
        node.children.firstOrNull()?.let { return TreeNav.FocusRow(TreeRow.folderKey(it.id)) }
        node.tracks.firstOrNull()?.let { return TreeNav.FocusRow(TreeRow.trackKey(it.id)) }
        return TreeNav.Consume
    }
}

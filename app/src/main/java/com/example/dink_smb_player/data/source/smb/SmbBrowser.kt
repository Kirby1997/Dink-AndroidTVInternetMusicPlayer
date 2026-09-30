package com.example.dink_smb_player.data.source.smb

import com.example.dink_smb_player.data.model.SmbShare
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.data.prefs.SmbCreds
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation

/**
 * Lazy, one-directory-at-a-time SMB listing for the share file browser. Unlike
 * [SmbSync] (which walks the whole tree up front and was the root of the 28k-track
 * ANR), this lists only the folder the user is currently looking at, so playing a
 * folder hands the player a bounded queue.
 *
 * smbj blocks — call [list] off the main thread (Dispatchers.IO).
 */
object SmbBrowser {

    /** One row in the browser. [smbPath] is the backslash path relative to the
     *  share root; [song] is non-null only for audio files. */
    data class Entry(
        val name: String,
        val isDir: Boolean,
        val smbPath: String,
        val song: Song?,
    )

    fun list(share: SmbShare, creds: SmbCreds?, smbPath: String): Result<List<Entry>> = runCatching {
        val disk = SmbClient.share(share.id, share.host, share.port, share.shareName, creds)
        val out = ArrayList<Entry>()
        val entries: List<FileIdBothDirectoryInformation> = disk.list(smbPath)
        for (entry in entries) {
            val name = entry.fileName
            if (name == "." || name == "..") continue
            if (isIgnoredSmbEntry(name, entry.fileAttributes)) continue
            val isDir = (entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            val child = if (smbPath.isEmpty()) name else "$smbPath\\$name"
            when {
                isDir -> out += Entry(name, isDir = true, smbPath = child, song = null)
                SmbSync.isAudio(name) -> out += Entry(
                    name = name,
                    isDir = false,
                    smbPath = child,
                    song = SmbSync.songFor(share, child),
                )
            }
        }
        // Folders first, then files, each alphabetical — the conventional browser order.
        out.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        out
    }
}

/** Folder names that are NAS / OS housekeeping, never music: Synology thumbnails and extended
 *  attributes, recycle bins, snapshot trees (Synology, QNAP, Windows). Matched ignoring case. */
private val IGNORED_DIR_NAMES = setOf(
    "@eadir", "#recycle", "#snapshot", "\$recycle.bin", "system volume information",
    "@recycle", "@recently-snapshot", ".@__thumb", ".appledouble",
)

/** How the walk treats one listing entry — see [smbEntryKind]. */
internal enum class SmbEntryKind {
    /** Walk it: descend into a folder, index an audio file. */
    Keep,
    /** Housekeeping, never music: skipped, and anything indexed under it may be pruned. */
    Junk,
    /**
     * A folder the walk does not descend into — a reparse point (junction / symlink, which can
     * loop back up the tree; OneDrive Files-On-Demand folders are reparse points too) or a
     * generic hidden/system folder. It is NOT known to be junk: tracks indexed under it before
     * these were skipped (SRC-7) are real music. The walk keeps those rows as they are instead
     * of pruning them, and doesn't count the folder as a failed listing, so the rest of the
     * walk still prunes normally.
     */
    Unwalked,
}

/**
 * Classify a listing entry for the walk. [attributes] is the raw FileAttributes bitmask.
 *  - [SmbEntryKind.Junk]: [IGNORED_DIR_NAMES] folders (some NAS servers list them without the
 *    hidden bit), macOS AppleDouble `._*` resource-fork files ("._song.mp3" isn't audio, it just
 *    ends in .mp3), and hidden/system FILES (desktop.ini, thumbs).
 *  - [SmbEntryKind.Unwalked]: reparse-point folders and other hidden/system folders.
 *    Reparse-point FILES are kept: Windows Server dedup and cloud placeholders store real
 *    music that way.
 */
internal fun smbEntryKind(name: String, attributes: Long): SmbEntryKind {
    fun has(a: FileAttributes) = (attributes and a.value) != 0L
    val isDir = has(FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
    val hiddenOrSystem = has(FileAttributes.FILE_ATTRIBUTE_HIDDEN) || has(FileAttributes.FILE_ATTRIBUTE_SYSTEM)
    return when {
        isDir && name.lowercase() in IGNORED_DIR_NAMES -> SmbEntryKind.Junk
        !isDir && name.startsWith("._") -> SmbEntryKind.Junk
        !isDir && hiddenOrSystem -> SmbEntryKind.Junk
        isDir && (hiddenOrSystem || has(FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT)) -> SmbEntryKind.Unwalked
        else -> SmbEntryKind.Keep
    }
}

/** True for an entry the share browser doesn't show: anything the walk doesn't index as-is
 *  ([smbEntryKind] other than Keep). */
internal fun isIgnoredSmbEntry(name: String, attributes: Long): Boolean =
    smbEntryKind(name, attributes) != SmbEntryKind.Keep

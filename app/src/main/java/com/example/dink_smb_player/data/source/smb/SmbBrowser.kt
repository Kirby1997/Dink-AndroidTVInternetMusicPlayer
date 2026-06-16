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

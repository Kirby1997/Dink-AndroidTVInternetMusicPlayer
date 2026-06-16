package com.example.dink_smb_player.data.source.cloud

import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.library.trackIdFor
import com.example.dink_smb_player.data.model.CloudProvider
import com.example.dink_smb_player.data.model.Song

/**
 * Lazy one-folder-at-a-time browser for a cloud provider, mirroring
 * [com.example.dink_smb_player.data.source.smb.SmbBrowser]. Lists folders + audio in
 * a single Drive folder so the browse screen never walks the whole account.
 *
 * Songs are playable straight from the browser (streamed) before any import — the
 * [Song.mediaUri] is the same `gdrive://` URI the importer would assign, and its id
 * matches the index PK so playing then importing doesn't duplicate.
 *
 * Blocks on the network — call from Dispatchers.IO.
 */
object CloudBrowser {

    /** One row: a subfolder (navigate by [id], breadcrumb [namePath]) or a track. */
    data class Entry(
        val id: String,
        val name: String,
        val isDir: Boolean,
        val namePath: String,
        val song: Song?,
    )

    /** List [folderId]'s children. [namePath] is the breadcrumb of this folder
     *  (relative to root, "" = My Drive) used to build child breadcrumbs + track paths. */
    fun list(provider: CloudProvider, accessToken: String, folderId: String, namePath: String): Result<List<Entry>> =
        GoogleDriveClient.listChildren(accessToken, folderId).map { items ->
            items.map { item ->
                val childPath = if (namePath.isEmpty()) item.name else "$namePath/${item.name}"
                if (item.isFolder) {
                    Entry(id = item.id, name = item.name, isDir = true, namePath = childPath, song = null)
                } else {
                    Entry(
                        id = item.id,
                        name = item.name,
                        isDir = false,
                        namePath = namePath,
                        song = songFor(provider, item, namePath),
                    )
                }
            }
        }

    private fun songFor(provider: CloudProvider, item: GoogleDriveClient.DriveItem, namePath: String): Song {
        val title = item.name.substringBeforeLast('.', item.name)
        val ext = item.name.substringAfterLast('.', "").uppercase()
        val path = if (namePath.isEmpty()) "${provider.name}/${item.name}"
        else "${provider.name}/$namePath/${item.name}"
        return Song(
            id = trackIdFor(SourceType.Cloud, provider.id, item.id),
            title = title,
            artist = "Unknown",
            albumId = null,
            albumTitle = namePath.substringAfterLast('/', namePath).ifEmpty { provider.name },
            durationSec = 0,
            playCount = 0,
            sourcePath = path,
            bitrate = ext.ifEmpty { "—" },
            mediaUri = CloudImporter.mediaUriFor(provider.id, item.id),
        )
    }
}

package com.example.dink_smb_player.data.source.cloud

import android.content.Context
import com.example.dink_smb_player.data.index.SourceEntity
import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import com.example.dink_smb_player.data.library.trackIdFor
import com.example.dink_smb_player.data.model.CloudFolderRef
import com.example.dink_smb_player.data.model.CloudProvider
import com.example.dink_smb_player.data.source.TagReader
import java.net.URLEncoder

/**
 * Turns the audio under a provider's chosen folders into [TrackEntity] rows for the
 * library index. INDEX-ONLY: id, name, size + a `gdrive://` streaming URI — bytes
 * are never downloaded (TV storage is tight; cloud is streamed on demand via
 * [CloudDataSource]).
 *
 * Folder-scoped, mirroring SMB: the walk descends only the [CloudFolderRef]s the
 * user imported, and each track's [TrackEntity.path] is a name breadcrumb
 * ("<Provider>/<folder>/<sub>/<file>") so monitor reconciliation can scope by prefix
 * exactly like [com.example.dink_smb_player.data.source.smb.SmbImporter].
 *
 * Drive listing blocks — call from Dispatchers.IO.
 */
object CloudImporter {

    private const val MAX_DEPTH = 12

    /** Streaming URI consumed by [CloudDataSource]. fileId in the path (case-sensitive),
     *  providerId in the query so the data source can resolve the token. */
    fun mediaUriFor(providerId: String, fileId: String): String =
        "gdrive://file/${enc(fileId)}?pid=${enc(providerId)}"

    /** [TrackEntity.path] prefix every track under [ref] starts with — the monitor
     *  refresh uses it to decide which existing rows fall inside a monitored folder. */
    fun monitoredPrefix(provider: CloudProvider, ref: CloudFolderRef): String =
        if (ref.path.isEmpty()) "${provider.name}/" else "${provider.name}/${ref.path}/"

    fun sourceEntityFor(provider: CloudProvider, trackCount: Int, sizeBytes: Long): SourceEntity = SourceEntity(
        id = provider.id,
        type = SourceType.Cloud,
        displayName = provider.name,
        createdAtMs = System.currentTimeMillis(),
        lastSyncMs = System.currentTimeMillis(),
        trackCount = trackCount,
        sizeBytes = sizeBytes,
    )

    /** Outcome of a walk. [complete] is false when any folder listing failed (or a subtree
     *  past [MAX_DEPTH]) — [tracks] is then a SUBSET, so callers MUST NOT prune against it.
     *  Mirrors [com.example.dink_smb_player.data.source.smb.SmbImporter.EnumResult]. */
    data class EnumResult(val tracks: List<TrackEntity>, val complete: Boolean)

    /** Recursively enumerate the audio under each [CloudFolderRef] and map to index
     *  rows. Dedupes overlapping roots by track id. */
    fun enumerate(
        context: Context,
        provider: CloudProvider,
        accessToken: String,
        folders: List<CloudFolderRef>,
        existing: Map<String, TrackEntity> = emptyMap(),
    ): Result<EnumResult> =
        runCatching {
            val out = LinkedHashMap<String, TrackEntity>()
            val complete = java.util.concurrent.atomic.AtomicBoolean(true)
            for (ref in folders) walk(context, provider, accessToken, ref.id, ref.path, 0, out, existing, complete)
            EnumResult(out.values.toList(), complete.get())
        }

    private fun walk(
        context: Context,
        provider: CloudProvider,
        token: String,
        folderId: String,
        namePath: String,
        depth: Int,
        out: MutableMap<String, TrackEntity>,
        existing: Map<String, TrackEntity>,
        complete: java.util.concurrent.atomic.AtomicBoolean,
    ) {
        if (depth > MAX_DEPTH) { complete.set(false); return }
        // Listing failure → skip this folder but mark incomplete so the caller upserts
        // WITHOUT pruning; pruning a subset would delete real tracks we couldn't see.
        val items = GoogleDriveClient.listChildren(token, folderId).getOrElse { complete.set(false); return }
        for (item in items) {
            val childPath = if (namePath.isEmpty()) item.name else "$namePath/${item.name}"
            if (item.isFolder) {
                walk(context, provider, token, item.id, childPath, depth + 1, out, existing, complete)
            } else {
                val id = trackIdFor(SourceType.Cloud, provider.id, item.id)
                // Already indexed → reuse the row (keep tags), skip the tag read.
                val t = existing[id] ?: trackFor(context, provider, item, namePath)
                out[t.id] = t
            }
        }
    }

    private fun trackFor(context: Context, provider: CloudProvider, item: GoogleDriveClient.DriveItem, namePath: String): TrackEntity {
        val ext = item.name.substringAfterLast('.', "").uppercase()
        val path = if (namePath.isEmpty()) "${provider.name}/${item.name}"
        else "${provider.name}/$namePath/${item.name}"
        val uri = mediaUriFor(provider.id, item.id)
        // Read embedded tags (header bytes only, streamed). Falls back to filename/
        // folder when untagged or unreadable.
        val tags = TagReader.read(context, uri)
        return TrackEntity(
            id = trackIdFor(SourceType.Cloud, provider.id, item.id),
            title = tags?.title ?: item.name.substringBeforeLast('.', item.name),
            artist = tags?.artist,
            albumTitle = tags?.album ?: namePath.substringAfterLast('/', namePath).ifEmpty { provider.name },
            year = tags?.year,
            trackNumber = tags?.trackNumber,
            durationMs = tags?.durationMs ?: 0L,
            bitrate = ext.ifEmpty { null },
            mimeType = item.mimeType,
            sourceType = SourceType.Cloud,
            sourceId = provider.id,
            path = path,
            uri = uri,
            sizeBytes = item.sizeBytes,
            addedAtMs = System.currentTimeMillis(),
        )
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

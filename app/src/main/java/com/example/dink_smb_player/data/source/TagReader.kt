package com.example.dink_smb_player.data.source

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.dink_smb_player.data.source.smb.DinkDataSourceFactory
import java.util.concurrent.TimeUnit

/**
 * Reads embedded tags (ID3 / Vorbis comment / MP4) from a track at IMPORT time,
 * including remote SMB / cloud tracks — without downloading the file.
 *
 * It runs Media3's [MetadataRetriever] over our own [DinkDataSourceFactory], so the
 * extractor pulls only the bytes it needs (container header / metadata atom) over
 * smbj or HTTP Range. `Metadata.Entry.populateMediaMetadata` decodes every tag
 * format into a single [MediaMetadata], so there's no per-format parsing here.
 *
 * Blocks (network) — call from Dispatchers.IO. Returns null when the file has no
 * usable tags, the read times out, or anything goes wrong: callers then keep their
 * filename/folder-derived values.
 */
object TagReader {

    data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val year: Int? = null,
        val trackNumber: Int? = null,
        val durationMs: Long? = null,
    )

    // Tag headers sit at the start of the file, so a healthy read returns in well under
    // a second. A long timeout only ever burns on untagged/unreadable files — and at 25k
    // tracks that worst case dominates total rescan time. Keep it tight.
    private const val TIMEOUT_SECONDS = 6L

    fun read(context: Context, uri: String): Tags? {
        val appContext = context.applicationContext
        val sourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(DinkDataSourceFactory(appContext))
        // Hold the future so we can CANCEL it on timeout/error. Without this, a slow
        // file's extraction keeps running on a background thread after .get() gives up,
        // holding multi-MB read buffers — across a 25k rescan those leaks pile up and
        // OOM the process (the crash was in smbj's reader allocating into a dead read).
        val future = MetadataRetriever.retrieveMetadata(sourceFactory, MediaItem.fromUri(uri))
        val mm: MediaMetadata? = try {
            val trackGroups = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val builder = MediaMetadata.Builder()
            var sawAny = false
            for (g in 0 until trackGroups.length) {
                val group = trackGroups.get(g)
                for (f in 0 until group.length) {
                    val md = group.getFormat(f).metadata ?: continue
                    for (i in 0 until md.length()) {
                        md.get(i).populateMediaMetadata(builder)
                        sawAny = true
                    }
                }
            }
            if (sawAny) builder.build() else null
        } catch (t: Throwable) {
            null
        } finally {
            // Idempotent: harmless after a successful get, frees the extractor on timeout.
            future.cancel(true)
        }
        // Duration is a separate platform-retriever probe — Media3 1.4's MetadataRetriever
        // doesn't expose it. Header-only, byte-budgeted, no download (see DurationReader).
        val durationMs = DurationReader.read(appContext, uri)
        val tags = Tags(
            title = mm?.title?.toString()?.trim()?.ifBlank { null },
            artist = (mm?.artist ?: mm?.albumArtist)?.toString()?.trim()?.ifBlank { null },
            album = mm?.albumTitle?.toString()?.trim()?.ifBlank { null },
            year = mm?.recordingYear ?: mm?.releaseYear,
            trackNumber = mm?.trackNumber,
            durationMs = durationMs?.takeIf { it > 0 },
        )
        // All-null (no tags AND no duration) → treat as nothing so the caller keeps its
        // path-derived values.
        return if (tags == Tags()) null else tags
    }
}

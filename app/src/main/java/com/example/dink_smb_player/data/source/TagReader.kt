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
 * Media3's Mp3Extractor only surfaces ID3v2 tags at the FILE START. A large share of older
 * / ExactAudioCopy-ripped MP3s carry their tags only at the END (ID3v1 / APEv2) — for those
 * the retrieve returns nothing, and we fall back to [TagFallbackReader].
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

    // ID3v2 headers sit at the start of the file, so a healthy read returns well under a second.
    // A longer timeout only ever burns on untagged/unreadable files. Kept at 10s (not 3s): a slow
    // NAS legitimately takes several seconds to serve an MP3 whose first audio frame is pushed
    // megabytes in by a large embedded ID3v2 cover, and 3s was cutting those valid reads short.
    private const val TIMEOUT_SECONDS = 10L

    /**
     * [tagsNeeded] = false skips the embedded-tag retrieve. [durationNeeded] = false skips
     * the duration probe. Both default true; the rescan turns each off independently per row
     * so a file is opened over SMB ONLY for the field it's actually missing:
     *
     *  - a row with a real title (not filename-derived) but no duration → tagsNeeded=false:
     *    skips the 1-3s tag retrieve, which always finds nothing useful there anyway.
     *  - a filename-derived row that already HAS a duration → durationNeeded=false: skips a
     *    whole second SMB open+header read (the duration probe is a separate retriever).
     *
     * Skipping the unneeded read halves the per-row network round-trips on rows that only
     * miss one field, and is a big part of the rescan speed-up.
     */
    fun read(
        context: Context,
        uri: String,
        tagsNeeded: Boolean = true,
        durationNeeded: Boolean = true,
    ): Tags? {
        val appContext = context.applicationContext
        val mm: MediaMetadata? = if (!tagsNeeded) null else {
            val sourceFactory = DefaultMediaSourceFactory(appContext)
                .setDataSourceFactory(DinkDataSourceFactory(appContext))
            // Hold the future so we can CANCEL it on timeout/error. Without this, a slow
            // file's extraction keeps running on a background thread after .get() gives up,
            // holding multi-MB read buffers — across a 25k rescan those leaks pile up and
            // OOM the process (the crash was in smbj's reader allocating into a dead read).
            val future = MetadataRetriever.retrieveMetadata(sourceFactory, MediaItem.fromUri(uri))
            try {
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
        }
        // Duration is a separate platform-retriever probe — Media3 1.4's MetadataRetriever
        // doesn't expose it. Header-only, byte-budgeted, time-capped, no download (see
        // DurationReader). Skip it entirely when the caller already has a valid duration:
        // that's a second SMB open+read saved on every row that only missed its title.
        val durationMs = if (durationNeeded) DurationReader.read(appContext, uri) else null
        var tags = Tags(
            title = mm?.title?.toString()?.trim()?.ifBlank { null },
            artist = (mm?.artist ?: mm?.albumArtist)?.toString()?.trim()?.ifBlank { null },
            album = mm?.albumTitle?.toString()?.trim()?.ifBlank { null },
            year = mm?.recordingYear ?: mm?.releaseYear,
            trackNumber = mm?.trackNumber,
            durationMs = durationMs?.takeIf { it > 0 },
        )
        // Media3 found no ID3v2 at the file start → this is likely an ID3v1/APEv2-only MP3
        // (common in old / EAC-ripped libraries). Read the trailing tag ourselves. Gated to
        // MP3 so non-ID3 containers (M4A/FLAC) don't pay a wasted SMB open on every row.
        if (tagsNeeded && tags.title == null && uri.substringBefore('?').endsWith(".mp3", ignoreCase = true)) {
            TagFallbackReader.read(appContext, uri)?.let { fb ->
                tags = tags.copy(
                    title = tags.title ?: fb.title,
                    artist = tags.artist ?: fb.artist,
                    album = tags.album ?: fb.album,
                    year = tags.year ?: fb.year,
                    trackNumber = tags.trackNumber ?: fb.trackNumber,
                )
            }
        }
        // All-null (no tags AND no duration) → treat as nothing so the caller keeps its
        // path-derived values.
        return if (tags == Tags()) null else tags
    }
}

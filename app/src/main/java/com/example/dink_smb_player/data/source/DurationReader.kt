package com.example.dink_smb_player.data.source

import android.content.Context
import android.media.MediaMetadataRetriever

/**
 * Reads a track's playback DURATION at IMPORT time — including remote SMB / cloud
 * tracks — WITHOUT downloading the file.
 *
 * Media3's [androidx.media3.exoplayer.MetadataRetriever] (used by [TagReader] for
 * tags) does not surface duration in media3 1.4, so this fills the gap: it drives the
 * platform [MediaMetadataRetriever] over a [MediaDataSource] that is itself backed by
 * our own [DinkDataSourceFactory]. The extractor pulls only the bytes it needs
 * (container header for MP3/FLAC, metadata atom for M4A) over smbj or HTTP Range.
 *
 * A byte BUDGET caps total transfer so a probe can never fall through to downloading a
 * whole file: once the budget is spent [readAt] returns EOF, the retriever stops, and
 * duration just falls back to playback-time enrichment (PlayerState) for that track.
 *
 * Blocks (network) — call from Dispatchers.IO. Returns null on any failure.
 */
object DurationReader {

    /** Header + metadata atom for an audio file is comfortably inside this; a VBR MP3
     *  with a Xing header or an M4A moov both land well under it. Past this we bail to
     *  avoid pulling the whole file over the network on 25k tracks. */
    private const val PROBE_BUDGET_BYTES = 8L * 1024 * 1024

    /** Wall-clock cap per probe. A healthy duration read is well under a second; this only
     *  ever fires on a seek-heavy file on a slow NAS, where a single probe could otherwise
     *  run 30-50s and stall a rescan worker. Past it the duration falls back to playback-time
     *  enrichment — far better than parking the whole rescan on one pathological file. */
    private const val PROBE_TIMEOUT_MS = 5_000L

    fun read(context: Context, uri: String): Long? {
        val retriever = MediaMetadataRetriever()
        val deadline = android.os.SystemClock.elapsedRealtime() + PROBE_TIMEOUT_MS
        val src = Media3MediaDataSource(context.applicationContext, uri, PROBE_BUDGET_BYTES, deadline)
        return try {
            retriever.setDataSource(src)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { src.close() }
        }
    }
}

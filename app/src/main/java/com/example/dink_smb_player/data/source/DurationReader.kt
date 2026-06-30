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

    /** Transfer cap per probe. A VBR MP3 with no usable Xing header forces the platform
     *  extractor to scan EVERY frame to the end of file for an accurate duration, so the
     *  probe legitimately reads most of the file — sized to let a typical full album track
     *  complete (with the 512KB read-ahead it's a handful of round-trips, not a download).
     *  Past this the probe truncates and the row falls back to playback-time enrichment
     *  rather than dragging a pathologically large file over the network. */
    private const val PROBE_BUDGET_BYTES = 24L * 1024 * 1024

    /** Wall-clock cap per probe. Sized to let a real probe FINISH on a slow NAS: an MP3 with a
     *  large embedded ID3v2 cover at file start pushes the first audio frame (and its Xing
     *  header) megabytes in, so the sequential read to reach it can take many seconds. Set too
     *  low, the cap fires mid-parse and the platform retriever reports a bogus SHORT duration
     *  from the partial bytes (the 5s value here truncated ~85% of a 25k SMB library to fake
     *  5-16s durations). Only genuinely pathological seek-heavy files now hit it, and those are
     *  rejected (see truncated check) rather than stored wrong. */
    private const val PROBE_TIMEOUT_MS = 20_000L

    fun read(context: Context, uri: String): Long? {
        // MP3s (≈the whole library): parse the Xing/Info VBR header directly — ~4KB, exact,
        // no whole-file scan. The platform retriever below is the fallback for everything
        // else (M4A/FLAC) and for any MP3 the parser can't confidently read.
        if (uri.substringBefore('?').endsWith(".mp3", ignoreCase = true)) {
            Mp3DurationParser.read(context, uri)?.let { return it }
        }
        val retriever = MediaMetadataRetriever()
        val deadline = android.os.SystemClock.elapsedRealtime() + PROBE_TIMEOUT_MS
        val src = Media3MediaDataSource(context.applicationContext, uri, PROBE_BUDGET_BYTES, deadline)
        return try {
            retriever.setDataSource(src)
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
            // CRITICAL: a probe cut short by our budget/deadline/a read error makes the platform
            // retriever derive duration from the PARTIAL stream — a too-short bogus value, not
            // null. Discard it and fall back to playback-time enrichment instead of storing a
            // wrong duration that would also be sticky (retag skips rows that already have one).
            if (src.truncated) null else dur
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { src.close() }
        }
    }
}

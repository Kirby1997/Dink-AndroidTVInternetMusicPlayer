package com.example.dink_smb_player.data.source

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.example.dink_smb_player.data.source.smb.DinkDataSourceFactory

/**
 * [MediaDataSource] adapter over a Media3 [androidx.media3.datasource.DataSource]
 * (smb:// / gdrive:// / file:// / http(s)://, dispatched by [DinkDataSourceFactory]).
 * Lets the platform [android.media.MediaMetadataRetriever] read a remote track's
 * header / metadata atom over smbj or HTTP Range — no full download.
 *
 * One open handle is kept ALIVE across SEQUENTIAL [readAt] calls and only re-opened on an
 * actual seek (a [position] that isn't where the current handle is). This matters a lot:
 * the platform retriever parses MP4/M4A atom boxes with dozens of small back-to-back reads,
 * and opening a fresh SMB file (an SMB CREATE round-trip) per read made one duration probe
 * cost dozens of round-trips — minutes-per-thousand on a slow NAS, the bottleneck that made
 * the 25k duration rescan crawl. Sequential runs now collapse to a single open. A byte
 * [budgetBytes] still caps total transfer so a probe can never fall through to pulling a
 * whole file (past budget → EOF; the retriever stops).
 *
 * Used by [DurationReader] (duration) and [com.example.dink_smb_player.data.art.ArtExtractor]
 * (embedded cover art).
 */
internal class Media3MediaDataSource(
    context: Context,
    private val uri: String,
    private val budgetBytes: Long,
    // Wall-clock cap. The byte budget bounds transfer but NOT time: a seek-heavy MP4 whose
    // moov atom is interleaved at end-of-file makes the retriever issue many back-to-back
    // seeks, each an SMB re-open, and a slow NAS turns that into 30-50s for ONE file —
    // which parks a rescan worker and tanks throughput. Past this deadline readAt returns
    // EOF so the retriever gives up; that file's duration just falls back to playback-time
    // enrichment. 0 = no cap (playback, where we must read the whole stream).
    private val deadlineMs: Long = 0L,
) : MediaDataSource() {

    private val factory = DinkDataSourceFactory(context.applicationContext)
    private var cachedSize: Long = -2 // -2 = not probed yet; -1 = unknown; >=0 = known
    private var consumed: Long = 0

    private fun pastDeadline(): Boolean =
        deadlineMs > 0L && android.os.SystemClock.elapsedRealtime() >= deadlineMs

    // The live handle reused across sequential reads, and the next byte offset it will
    // return. `cursor < 0` means no handle is open. A readAt whose position != cursor is a
    // seek: close + re-open positioned there.
    private var openDs: DataSource? = null
    private var cursor: Long = -1L

    override fun getSize(): Long {
        if (cachedSize != -2L) return cachedSize
        val ds = factory.createDataSource()
        cachedSize = try {
            val len = ds.open(DataSpec(Uri.parse(uri)))
            if (len == C.LENGTH_UNSET.toLong()) -1 else len
        } catch (t: Throwable) {
            -1
        } finally {
            runCatching { ds.close() }
        }
        return cachedSize
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (consumed >= budgetBytes) return -1 // budget spent: stop the probe (EOF)
        if (pastDeadline()) return -1 // time budget spent: stop the probe (EOF)

        // Re-open only on a seek; reuse the open handle when the read continues sequentially.
        if (openDs == null || position != cursor) {
            closeOpen()
            val ds = factory.createDataSource()
            try {
                // Length UNSET → readable to EOF; we stop on our own budget, so this never
                // drags the whole file. One open serves the whole sequential run.
                ds.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
            } catch (t: Throwable) {
                runCatching { ds.close() }
                return -1
            }
            openDs = ds
            cursor = position
        }

        val ds = openDs!!
        val want = minOf(size.toLong(), budgetBytes - consumed).toInt()
        return try {
            var got = 0
            while (got < want) {
                val n = ds.read(buffer, offset + got, want - got)
                if (n == C.RESULT_END_OF_INPUT) break
                got += n
            }
            consumed += got
            cursor += got
            if (got == 0) -1 else got
        } catch (t: Throwable) {
            closeOpen()
            -1
        }
    }

    private fun closeOpen() {
        runCatching { openDs?.close() }
        openDs = null
        cursor = -1L
    }

    override fun close() {
        closeOpen()
    }
}

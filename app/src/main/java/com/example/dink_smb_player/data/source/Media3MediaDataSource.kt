@file:OptIn(UnstableApi::class)

package com.example.dink_smb_player.data.source

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.example.dink_smb_player.data.source.smb.DinkDataSourceFactory
import com.example.dink_smb_player.data.source.smb.ReadAheadBuffer

/**
 * [MediaDataSource] adapter over a Media3 [androidx.media3.datasource.DataSource]
 * (smb:// / gdrive:// / file:// / http(s)://, dispatched by [DinkDataSourceFactory]).
 * Lets the platform [android.media.MediaMetadataRetriever] read a remote track's
 * header / metadata atom over smbj or HTTP Range — no full download.
 *
 * smb:// is genuinely random-access, so it gets a fast path: the file is opened ONCE and every
 * [readAt] — including the platform retriever's hundreds of scattered frame-scan reads — is
 * served by a positioned read on that single handle ([SmbProbe.Handle]) through a
 * 512KB read-ahead buffer. Inside an [SmbProbe.session] that handle is the one the other
 * probes of the same file share, and its size (from the listing) answers [getSize]. The old code re-opened the SMB file (a full SMB CREATE
 * round-trip) on every non-sequential read, which cost ~636 opens for one duration probe and
 * timed it out. Other schemes (http Range / gdrive) can't reposition a live stream, so they
 * keep the re-open-on-seek fallback. A byte [budgetBytes] caps total transfer so a probe can
 * never fall through to pulling a whole file (past budget → EOF; the retriever stops).
 *
 * Used by [DurationReader] (the M4A/FLAC fallback path — MP3 duration goes through the much
 * cheaper [Mp3DurationParser]) and [com.example.dink_smb_player.data.art.ArtExtractor].
 */
internal class Media3MediaDataSource(
    private val context: Context,
    private val uri: String,
    private val budgetBytes: Long,
    // Wall-clock cap. The byte budget bounds transfer but NOT time: a seek-heavy MP4 whose
    // moov atom is interleaved at end-of-file makes the retriever issue many back-to-back
    // seeks, each an SMB re-open, and a slow NAS turns that into 30-50s for ONE file —
    // which parks a rescan worker and tanks throughput. Past this deadline readAt returns
    // EOF so the retriever gives up; that file's duration just falls back to playback-time
    // enrichment. 0 = no cap (playback, where we must read the whole stream).
    private val deadlineMs: Long = 0L,
    // Opens the smb:// file. The probe session is looked up HERE, on the constructing thread —
    // the platform retriever calls readAt back on binder threads, where it isn't visible.
    // Tests pass a fake.
    private val openSmb: () -> ProbeFile = SmbProbe.current(uri).let { session -> { SmbProbe.open(uri, session) } },
) : MediaDataSource() {

    // Lazy: the smb path never touches it.
    private val factory by lazy { DinkDataSourceFactory(context.applicationContext) }
    private var cachedSize: Long = -2 // -2 = not probed yet; -1 = unknown; >=0 = known
    private var consumed: Long = 0

    // smb:// is genuinely random-access (one open handle, positioned reads). The platform
    // duration scanner issues hundreds of scattered reads; serving each by re-opening the file
    // cost a full SMB CREATE per seek (~636 per probe → 20s timeout). For smb we open ONCE and
    // random-access via one open handle. Other schemes (http Range / gdrive) can't
    // reposition a live stream, so they keep the re-open-on-seek fallback below.
    private val isSmb = uri.startsWith("smb", ignoreCase = true)
    private var smbFile: ProbeFile? = null
    private var smbTried = false

    // Read-ahead for the SMB path. The platform scans a VBR MP3 frame-by-frame in tiny (~2KB)
    // reads — ~1700 for one 3.5MB file — and these are SEQUENTIAL (seeks=0). One big SMB read
    // per ~512KB serves hundreds of those tiny reads from memory, turning ~1700 network
    // round-trips into ~7. `consumed` counts only NETWORK bytes (fetches), so the budget still
    // bounds transfer and cache hits are free. The 512KB array is allocated on first read.
    private val readAhead = ReadAheadBuffer(512 * 1024)
    private val smbFetch = ReadAheadBuffer.Fetch { pos, b, o, l ->
        smbFile!!.readAt(pos, b, o, l).also { if (it > 0) consumed += it }
    }

    // True once the stream was cut short by our budget / deadline / a read error — i.e. the
    // retriever was handed a -1 (EOF) at a position BEFORE the real end of file. Crucial: the
    // platform MediaMetadataRetriever does NOT discard a duration when it hits a premature EOF;
    // it happily reports a duration derived from the PARTIAL bytes it managed to read, which is
    // far too short. Callers (DurationReader) must treat a truncated probe as "no value" rather
    // than trust that bogus short duration. Stays false when the underlying file is read fully
    // to its real EOF, where the parsed duration is valid.
    var truncated: Boolean = false
        private set

    // The first I/O failure (or deadline) that cut the stream short — null when the stream
    // ended genuinely or only on the byte budget. Lets callers tell a transient failure (NAS
    // down, timeout → retry later) from a definitive "file has nothing" (see ReadFailures).
    var failure: Throwable? = null
        private set

    private fun noteFailure(t: Throwable) { if (failure == null) failure = t }

    private fun pastDeadline(): Boolean =
        deadlineMs > 0L && android.os.SystemClock.elapsedRealtime() >= deadlineMs

    // The live handle reused across sequential reads, and the next byte offset it will
    // return. `cursor < 0` means no handle is open. A readAt whose position != cursor is a
    // seek: close + re-open positioned there.
    private var openDs: DataSource? = null
    private var cursor: Long = -1L

    override fun getSize(): Long {
        if (cachedSize != -2L) return cachedSize
        if (isSmb) { // the handle we read through knows its size — no second open
            cachedSize = ensureSmb()?.length?.takeIf { it >= 0 } ?: -1
            return cachedSize
        }
        val ds = factory.createDataSource()
        cachedSize = try {
            val len = ds.open(DataSpec(Uri.parse(uri)))
            if (len == C.LENGTH_UNSET.toLong()) -1 else len
        } catch (t: Throwable) {
            noteFailure(t)
            -1
        } finally {
            runCatching { ds.close() }
        }
        return cachedSize
    }

    /** Open the SMB handle once; all subsequent seeks random-access it. Null if this isn't an
     *  smb URI or the open failed (then we fall back to the generic re-open path). */
    private fun ensureSmb(): ProbeFile? {
        if (smbTried) return smbFile
        smbTried = true
        if (!isSmb) return null
        // A bare smbj-backed handle (not the factory's MultiSchemeDataSource wrapper) — we need
        // random-access positioned reads. Creds come from the global SmbConnectionRegistry.
        try {
            smbFile = openSmb()
        } catch (t: Throwable) {
            truncated = true
            noteFailure(t)
        }
        return smbFile
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (consumed >= budgetBytes) { truncated = true; return -1 } // budget spent: stop (false EOF)
        if (pastDeadline()) { // time budget spent: stop (false EOF)
            truncated = true
            noteFailure(java.util.concurrent.TimeoutException("probe deadline exceeded"))
            return -1
        }

        val want = minOf(size.toLong(), budgetBytes - consumed).toInt()

        // SMB fast path: one open handle + read-ahead buffer — no CREATE per seek, and the
        // platform's ~2KB sequential scan reads are served from memory between refills.
        if (isSmb) {
            ensureSmb() ?: return -1
            // Served from the buffer when it covers `position`; otherwise one refill there,
            // capped at the remaining budget.
            val n = try {
                readAhead.read(position, buffer, offset, want, budgetBytes - consumed, smbFetch)
            } catch (t: Throwable) {
                truncated = true // read error mid-parse — stream is incomplete
                noteFailure(t)
                return -1
            }
            return if (n <= 0) -1 else n // -1 is a GENUINE EOF from smbj → valid, NOT truncated
        }

        // Generic fallback (http / gdrive / file): re-open on seek; reuse handle when sequential.
        if (openDs == null || position != cursor) {
            closeOpen()
            val ds = factory.createDataSource()
            try {
                // Length UNSET → readable to EOF; we stop on our own budget, so this never
                // drags the whole file. One open serves the whole sequential run.
                ds.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
            } catch (t: Throwable) {
                runCatching { ds.close() }
                truncated = true // open/seek failed mid-parse — stream is incomplete
                noteFailure(t)
                return -1
            }
            openDs = ds
            cursor = position
        }

        val ds = openDs!!
        return try {
            var got = 0
            while (got < want) {
                val n = ds.read(buffer, offset + got, want - got)
                if (n == C.RESULT_END_OF_INPUT) break
                got += n
            }
            consumed += got
            cursor += got
            // got == 0 here is a GENUINE end-of-file (underlying ds returned END_OF_INPUT): the
            // file was read fully, so the parsed duration is valid — do NOT mark truncated.
            if (got == 0) -1 else got
        } catch (t: Throwable) {
            closeOpen()
            truncated = true // read error mid-parse — stream is incomplete
            noteFailure(t)
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
        runCatching { smbFile?.release() }
        smbFile = null
    }
}

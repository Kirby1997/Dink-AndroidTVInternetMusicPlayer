@file:OptIn(UnstableApi::class)

package com.example.dink_smb_player.data.source

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.inspector.MetadataRetriever
import com.example.dink_smb_player.data.source.smb.DinkDataSourceFactory
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit

/**
 * Process-wide gate for bulk tag reads — every SMB walk (import / monitor, any number of shares
 * at once) and the retag go through it. It used to be a new Semaphore per walk: two shares
 * walking alongside a retag then ran more reads than Media3's retriever cap, the overflow
 * queued inside Media3, and that queue time counted against [TagReader]'s timeout — spurious
 * transient Errors. One gate keeps the sum under the cap (see MAX_PARALLEL_RETRIEVALS).
 *
 * This is the process-wide CEILING, sized for a user-started retag. A walk takes far fewer:
 * its reads nest a narrow per-walk gate inside this one (SmbImporter.WALK_READ_CONCURRENCY,
 * and REREAD_CONCURRENCY for its budgeted re-reads), so a background monitor pass can never
 * fill all [PERMITS] beside its own directory listings.
 *
 * Two permits for a tail-loaded container (M4A/MP4 — moov atom at end-of-file, multi-MB
 * buffers): one of the [HEAVY_PERMITS] heap cap, then one of the [PERMITS] total. Front-loaded
 * containers (MP3/FLAC) read a small header and are latency-bound, so they only need the total.
 */
object TagReadGate {
    /** Total in-flight bulk tag reads. Retag's own sub-caps (16 front-loaded + 6 tail-loaded)
     *  fit inside it exactly, so a lone retag runs at full speed. */
    const val PERMITS = 22
    /** In-flight tail-loaded reads, process-wide — bounds peak heap. */
    const val HEAVY_PERMITS = 6

    private val all = Semaphore(PERMITS)
    private val heavy = Semaphore(HEAVY_PERMITS)

    /** Container extensions whose metadata/duration live near END-OF-FILE (the moov atom). */
    private val TAIL_LOADED_EXTS = setOf("m4a", "m4b", "mp4", "m4p", "aac", "mov")

    fun isTailLoaded(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in TAIL_LOADED_EXTS

    /** Run [block] (a [TagReader] read) holding the permits for [path]'s container. The heavy
     *  permit is taken FIRST, so a queued tail-loaded read doesn't sit on a total permit. */
    suspend fun <T> withPermit(path: String, block: suspend () -> T): T =
        if (isTailLoaded(path)) heavy.withPermit { all.withPermit { block() } }
        else all.withPermit { block() }

    /** Free total permits right now. Visible for tests. */
    internal val available: Int get() = all.availablePermits
}

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
 * Blocks (network) — call from Dispatchers.IO. [readResult] distinguishes "the file has no
 * usable tags" ([ReadResult.Absent]) from "couldn't read it right now" ([ReadResult.Error]:
 * NAS down, timeout, dropped connection) so the retag doesn't record a transient failure as
 * a permanent "no tags". [read] is the legacy nullable form: callers keep their
 * filename/folder-derived values on null.
 */
object TagReader {

    data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        /** Album-artist tag (ID3v2 TPE2, Vorbis ALBUMARTIST, MP4 aART, APE "Album Artist"). */
        val albumArtist: String? = null,
        val year: Int? = null,
        val trackNumber: Int? = null,
        val durationMs: Long? = null,
    )

    // ID3v2 headers sit at the start of the file, so a healthy read returns well under a second.
    // A longer timeout only ever burns on untagged/unreadable files. Kept at 10s (not 3s): a slow
    // NAS legitimately takes several seconds to serve an MP3 whose first audio frame is pushed
    // megabytes in by a large embedded ID3v2 cover, and 3s was cutting those valid reads short.
    private const val TIMEOUT_SECONDS = 10L

    /** Media3 retrievals that may run at once: every gated bulk read ([TagReadGate.PERMITS])
     *  plus headroom for the ungated one-at-a-time readers (embedded lyrics, cloud import). */
    internal const val MAX_PARALLEL_RETRIEVALS = TagReadGate.PERMITS + 10

    init {
        // Media3 queues retrievals past 5 in flight (a process-wide cap), and queue time counts
        // against our TIMEOUT_SECONDS — queued files would time out and be recorded as transient
        // failures. Bulk readers share one process-wide gate ([TagReadGate]), so lift the cap
        // above it; a read then never waits inside Media3.
        MetadataRetriever.setMaximumParallelRetrievals(MAX_PARALLEL_RETRIEVALS)
    }

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
    ): Tags? = readResult(context, uri, tagsNeeded, durationNeeded).valueOrNull()

    /**
     * As [read], classified: Found (something read, every needed read conclusive), Absent
     * (every needed read conclusive, nothing found), or Error (a needed read failed
     * transiently — whatever else WAS read rides along as [ReadResult.Error.partial]).
     */
    fun readResult(
        context: Context,
        uri: String,
        tagsNeeded: Boolean = true,
        durationNeeded: Boolean = true,
    ): ReadResult<Tags> {
        val appContext = context.applicationContext
        // First transient failure of any needed read. Definitive failures (unparseable
        // container, file gone) are simply "no value", as before.
        var transient: Throwable? = null
        fun note(t: Throwable?) { if (transient == null && ReadFailures.isTransient(t)) transient = t }
        val mm: MediaMetadata? = if (!tagsNeeded) null else {
            retrieveEntries(appContext, uri, ::note)?.let { entries ->
                val builder = MediaMetadata.Builder()
                entries.forEach { it.populateMediaMetadata(builder) }
                builder.build()
            }
        }
        // Duration is a separate probe (Xing header / platform retriever) — Media3's
        // retrieveDurationUs() estimates VBR MP3s without a Xing header from the first frame's
        // bitrate. Header-only, byte-budgeted, time-capped, no download (see DurationReader).
        // Skip it entirely when the caller already has a valid duration: that's a second SMB
        // open+read saved on every row that only missed its title.
        val durationMs = if (durationNeeded) {
            DurationReader.probe(appContext, uri).also { if (it.durationMs == null) note(it.failure) }.durationMs
        } else {
            null
        }
        var tags = Tags(
            title = mm?.title?.toString()?.trim()?.ifBlank { null },
            artist = (mm?.artist ?: mm?.albumArtist)?.toString()?.trim()?.ifBlank { null },
            album = mm?.albumTitle?.toString()?.trim()?.ifBlank { null },
            albumArtist = mm?.albumArtist?.toString()?.trim()?.ifBlank { null },
            year = mm?.recordingYear ?: mm?.releaseYear,
            trackNumber = mm?.trackNumber,
            durationMs = durationMs?.takeIf { it > 0 },
        )
        // Media3 found no ID3v2 at the file start → this is likely an ID3v1/APEv2-only MP3
        // (common in old / EAC-ripped libraries). Read the trailing tag ourselves. Gated to
        // MP3 so non-ID3 containers (M4A/FLAC) don't pay a wasted SMB open on every row.
        if (tagsNeeded && tags.title == null && uri.substringBefore('?').endsWith(".mp3", ignoreCase = true)) {
            val fbResult = TagFallbackReader.read(appContext, uri)
            if (fbResult is ReadResult.Error) note(fbResult.cause)
            fbResult.valueOrNull()?.let { fb ->
                tags = tags.copy(
                    title = tags.title ?: fb.title,
                    artist = tags.artist ?: fb.artist,
                    album = tags.album ?: fb.album,
                    albumArtist = tags.albumArtist ?: fb.albumArtist,
                    year = tags.year ?: fb.year,
                    trackNumber = tags.trackNumber ?: fb.trackNumber,
                )
            }
        }
        // All-null (no tags AND no duration) → treat as nothing so the caller keeps its
        // path-derived values.
        val found = tags.takeUnless { it == Tags() }
        return when {
            transient != null -> ReadResult.Error(transient, found)
            found != null -> ReadResult.Found(found)
            else -> ReadResult.Absent
        }
    }

    /**
     * Every embedded metadata entry of [uri] (ID3 frames, Vorbis comments, MP4 atoms), read
     * ON DEMAND for the lyrics pane — USLT/SYLT arrive as ID3 BinaryFrames, MP4 `©lyr` as a
     * "USLT" TextInformationFrame, FLAC/Ogg as a LYRICS Vorbis comment. Lyric text is never
     * stored in the library index (its size matters at 25k rows); the lyrics chain caches
     * the resolved result instead. Blocks (network) up to the tag timeout; the future.get is
     * interruptible, so a caller using runInterruptible can abandon it on a track skip.
     */
    fun readLyricEntries(context: Context, uri: String): ReadResult<List<Metadata.Entry>> {
        var failure: Throwable? = null
        val entries = retrieveEntries(context.applicationContext, uri) { t ->
            if (failure == null && ReadFailures.isTransient(t)) failure = t
        }
        return when {
            failure != null -> ReadResult.Error(failure, entries)
            entries != null -> ReadResult.Found(entries)
            else -> ReadResult.Absent
        }
    }

    /** Metadata entries of every track format of [uri], or null when there are none (or
     *  the read failed — reported through [onError]). */
    private fun retrieveEntries(
        appContext: Context,
        uri: String,
        onError: (Throwable?) -> Unit,
    ): List<Metadata.Entry>? {
        val sourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(DinkDataSourceFactory(appContext))
        // Instance-based retriever (AutoCloseable). All retrievers share Media3's one
        // worker Looper; each extraction loads on its own period loader thread. On
        // timeout/error we CANCEL the future, and close() then releases the media
        // period — which cancels the in-flight load and drops its multi-MB read buffers
        // instead of leaving them running after .get() gave up (across a 25k rescan those
        // leaks piled up and OOMed the process in smbj's reader).
        return MetadataRetriever.Builder(appContext, MediaItem.fromUri(uri))
            .setMediaSourceFactory(sourceFactory)
            .build()
            .use { retriever ->
                val future = retriever.retrieveTrackGroups()
                try {
                    val trackGroups = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    val out = ArrayList<Metadata.Entry>()
                    for (g in 0 until trackGroups.length) {
                        val group = trackGroups.get(g)
                        for (f in 0 until group.length) {
                            val md = group.getFormat(f).metadata ?: continue
                            for (i in 0 until md.length()) out += md.get(i)
                        }
                    }
                    out.ifEmpty { null }
                } catch (t: Throwable) {
                    // TimeoutException (slow/unreachable NAS) or ExecutionException wrapping
                    // the data source's IOException / the extractor's ParserException.
                    onError(t)
                    null
                } finally {
                    // Idempotent after a successful get. close() waits for every future
                    // it handed out to complete, so cancel first or a timed-out load
                    // would keep the retrieval (and its loader) alive.
                    future.cancel(true)
                }
            }
    }
}

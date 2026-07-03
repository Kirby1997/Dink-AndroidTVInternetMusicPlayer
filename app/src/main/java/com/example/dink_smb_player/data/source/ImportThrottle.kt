package com.example.dink_smb_player.data.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Backpressure between background importing and active playback.
 *
 * A library import spins up to TAG_CONCURRENCY Media3 extractors, each buffering
 * multi-MB chunks over the SAME SMB link the player streams from — so an import
 * running during playback starves the player's reads and thrashes GC, making
 * playback janky. The player sets [playbackActive] while a track is streaming; the
 * importer calls [gate] before each heavy per-file tag read and yields a beat when
 * playback is live, ceding bandwidth/CPU. Import just runs slower while you listen.
 *
 * [narrowWhilePlaying] does the same for the WALK's directory listings: the walk
 * runs CONCURRENCY-way (16) normally, but while a track streams every list() also
 * has to pass a 2-permit gate — cutting effective listing concurrency to 2. Even
 * with playback on its own socket, a 16-way monitor walk of a 25k-file share
 * caused GC storms on the 32-bit TV (allocation churn across dozens of
 * coroutines) that starved the player's loader thread.
 */
object ImportThrottle {

    @Volatile
    var playbackActive: Boolean = false

    /** Per-file delay applied to tag reads while a track is playing. With TAG_CONCURRENCY
     *  parallel readers this caps the burst rate enough to keep playback smooth without
     *  stalling the import outright. */
    private const val THROTTLE_MS = 150L

    /** Listing concurrency ceiling that applies only while playback is live. */
    private val narrowGate = Semaphore(2)

    suspend fun gate() {
        if (playbackActive) delay(THROTTLE_MS)
    }

    /** Run [block] (one directory-list round-trip) at reduced concurrency while a
     *  track is streaming; full speed otherwise. Checked per call, so a walk
     *  started while paused narrows as soon as playback begins. */
    suspend fun <T> narrowWhilePlaying(block: suspend () -> T): T =
        if (playbackActive) narrowGate.withPermit { block() } else block()
}

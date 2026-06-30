package com.example.dink_smb_player.data.source

import kotlinx.coroutines.delay

/**
 * Backpressure between background importing and active playback.
 *
 * A library import spins up to TAG_CONCURRENCY Media3 extractors, each buffering
 * multi-MB chunks over the SAME SMB link the player streams from — so an import
 * running during playback starves the player's reads and thrashes GC, making
 * playback janky. The player sets [playbackActive] while a track is streaming; the
 * importer calls [gate] before each heavy per-file tag read and yields a beat when
 * playback is live, ceding bandwidth/CPU. Import just runs slower while you listen.
 */
object ImportThrottle {

    @Volatile
    var playbackActive: Boolean = false

    /** Per-file delay applied to tag reads while a track is playing. With TAG_CONCURRENCY
     *  parallel readers this caps the burst rate enough to keep playback smooth without
     *  stalling the import outright. */
    private const val THROTTLE_MS = 150L

    suspend fun gate() {
        if (playbackActive) delay(THROTTLE_MS)
    }
}

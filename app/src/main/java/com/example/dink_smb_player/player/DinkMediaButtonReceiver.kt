package com.example.dink_smb_player.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver

/**
 * Media keys with no live MediaSession (app swiped away / process dead). Media3's receiver
 * starts [PlayerService] as a FOREGROUND service, which then owes the system a
 * startForeground() within seconds. The notification only comes once playback resumes, so
 * with nothing to resume the app died with ForegroundServiceDidNotStartInTimeException.
 * Don't start the service at all when there's no session to resume; the remaining
 * failure cases (corrupt or unresolvable session) are covered in
 * PlayerService.onPlaybackResumption.
 */
@UnstableApi
class DinkMediaButtonReceiver : MediaButtonReceiver() {

    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean =
        shouldStart(
            hasSavedSession = PlaybackStore.hasSession(context),
            hasLiveSession = PlayerState.sharedOrNull()?.currentSong != null,
        )

    companion object {
        /** A saved session on disk, or one held in memory by this (warm) process. */
        internal fun shouldStart(hasSavedSession: Boolean, hasLiveSession: Boolean): Boolean =
            hasSavedSession || hasLiveSession
    }
}

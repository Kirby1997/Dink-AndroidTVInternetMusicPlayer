package com.example.dink_smb_player.player

import androidx.media3.common.PlaybackException
import com.example.dink_smb_player.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Error-skip runs in the service with no UI attached. An error raised then must not toast
 * when the app is next opened: [PlayerState.consumePlaybackError] drops anything older than
 * [PlayerState.PLAYBACK_ERROR_TTL_MS].
 */
class PlayerStatePlaybackErrorTest {

    private fun song(id: String) = Song(
        id = id,
        title = "Title $id",
        artist = "Artist",
        albumId = null,
        albumTitle = null,
        durationSec = 180,
        playCount = 0,
        sourcePath = "/music/$id.mp3",
        bitrate = "320",
    )

    private var now = 1_000_000L

    private fun failing(): PlayerState = PlayerState().also { s ->
        s.clockMs = { now }
        s.playFrom(listOf(song("a"), song("b")), 0)
        s.onPlaybackError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
    }

    @Test
    fun `fresh error is handed to the UI once`() {
        val s = failing()
        now += 500
        val msg = s.consumePlaybackError()
        assertNotNull(msg)
        assertEquals(true, msg!!.contains("Title a"))
        assertNull(s.playbackError)
        assertNull(s.consumePlaybackError())
    }

    @Test
    fun `error older than the TTL is dropped and cleared`() {
        val s = failing()
        now += PlayerState.PLAYBACK_ERROR_TTL_MS + 1
        assertNull(s.consumePlaybackError())
        assertNull(s.playbackError)
    }

    @Test
    fun `error exactly at the TTL still shows`() {
        val s = failing()
        now += PlayerState.PLAYBACK_ERROR_TTL_MS
        assertNotNull(s.consumePlaybackError())
    }

    @Test
    fun `a newer error restarts the clock`() {
        val s = failing() // skips a → b
        now += 60_000
        s.onPlaybackError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        now += 100
        val msg = s.consumePlaybackError()
        assertNotNull(msg)
        assertEquals(true, msg!!.contains("Title b"))
    }
}

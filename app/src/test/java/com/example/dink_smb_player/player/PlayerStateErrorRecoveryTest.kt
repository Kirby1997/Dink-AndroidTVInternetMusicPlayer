package com.example.dink_smb_player.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.example.dink_smb_player.data.model.Song
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * After a source error ExoPlayer drops to STATE_IDLE and ignores playWhenReady /
 * seekTo-only recovery until prepare() is called again. These tests pin the two
 * recovery paths that must re-prepare an idle engine: the auto-skip to the next
 * track (moveTo) and the user pressing play (togglePlayPause).
 */
class PlayerStateErrorRecoveryTest {

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
        mediaUri = "smb://nas/music/$id.mp3",
    )

    private class Rig(queueSize: Int) {
        val engine: Player = mock()
        val state = PlayerState()
        val listener: Player.Listener

        init {
            whenever(engine.mediaItemCount).thenReturn(queueSize)
            state.attachEngine(engine)
            val captor = argumentCaptor<Player.Listener>()
            verify(engine).addListener(captor.capture())
            listener = captor.firstValue
        }

        /** Puts the mocked engine into the post-error state: IDLE with playerError set. */
        fun failSource(): PlaybackException {
            val error = PlaybackException(
                "Source error", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )
            whenever(engine.playerError).thenReturn(error)
            whenever(engine.playbackState).thenReturn(Player.STATE_IDLE)
            return error
        }
    }

    @Test
    fun `source error skip within window re-prepares idle engine`() {
        val rig = Rig(queueSize = 2)
        rig.state.playFrom(listOf(song("a"), song("b")), 0)
        clearInvocations(rig.engine)

        rig.listener.onPlayerError(rig.failSource())

        // Skip target (index 1) is inside the engine window: moveTo seeks — but the
        // engine is IDLE, so it must also re-prepare or playback never restarts.
        verify(rig.engine).seekTo(1, 0L)
        verify(rig.engine).prepare()
    }

    @Test
    fun `play after error stop re-prepares instead of no-op`() {
        val rig = Rig(queueSize = 1)
        rig.state.playFrom(listOf(song("a")), 0)
        clearInvocations(rig.engine)

        // Single-track queue: no skip target, handler stops the engine.
        rig.listener.onPlayerError(rig.failSource())
        verify(rig.engine).stop()
        clearInvocations(rig.engine)

        rig.state.togglePlayPause()

        assertTrue(rig.state.isPlaying)
        verify(rig.engine).prepare()
        verify(rig.engine).setPlayWhenReady(true)
    }

    @Test
    fun `pause on healthy engine does not re-prepare`() {
        val rig = Rig(queueSize = 2)
        rig.state.playFrom(listOf(song("a"), song("b")), 0)
        whenever(rig.engine.playbackState).thenReturn(Player.STATE_READY)
        clearInvocations(rig.engine)

        rig.state.togglePlayPause() // playing → paused

        verify(rig.engine).setPlayWhenReady(false)
        verify(rig.engine, never()).prepare()
    }
}

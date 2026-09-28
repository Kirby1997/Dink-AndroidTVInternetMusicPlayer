package com.example.dink_smb_player.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.dink_smb_player.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * A restored-paused session defers its engine apply (pendingEngineApply) until the
 * first play intent. A remote ▶ then arrives at MediaSession, not PlayerState, and
 * Media3 serves it via onPlaybackResumption. These tests pin the warm-process
 * handoff: [PlayerState.resumptionWindow] must hand back the live session (and
 * count as the deferred apply), an engine that starts playing behind the façade
 * must be adopted rather than clobbered, and stale deferred positions must not
 * leak into later applies.
 */
class PlayerStateResumptionTest {

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

    private fun mediaItem(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    private fun snapshot(ids: List<String>, index: Int, posSec: Float) = PlaybackStore.Snapshot(
        queueIds = ids,
        index = index,
        positionSec = posSec,
        shuffle = false,
        repeat = RepeatMode.Off.name,
    )

    /** PlayerState with a restored-paused (deferred) session and an empty mock engine. */
    private class Rig(songs: List<Song>, index: Int, posSec: Float) {
        val engine: Player = mock()
        val state = PlayerState()
        val listener: Player.Listener

        init {
            whenever(engine.mediaItemCount).thenReturn(0)
            state.songResolver = { ids ->
                songs.associateBy { it.id }.filterKeys { it in ids.toSet() }
            }
            state.restore(
                PlaybackStore.Snapshot(
                    queueIds = songs.map { it.id },
                    index = index,
                    positionSec = posSec,
                    shuffle = false,
                    repeat = RepeatMode.Off.name,
                ),
                songs.associateBy { it.id },
            )
            state.attachEngine(engine)
            val captor = argumentCaptor<Player.Listener>()
            verify(engine).addListener(captor.capture())
            listener = captor.firstValue
        }
    }

    @Test
    fun `resumption window serves the live deferred session`() {
        val songs = listOf(song("a"), song("b"), song("c"))
        val rig = Rig(songs, index = 1, posSec = 42f)

        val win = requireNotNull(rig.state.resumptionWindow())

        assertEquals(songs.map { it.id }, win.songs.map { it.id })
        assertEquals(1, win.startIndex)
        assertEquals(42_000L, win.positionMs)
    }

    @Test
    fun `resumption window satisfies the deferred apply`() {
        val rig = Rig(listOf(song("a"), song("b")), index = 0, posSec = 10f)

        rig.state.resumptionWindow()
        // Media3 hands the window to the engine itself; simulate that apply.
        whenever(rig.engine.mediaItemCount).thenReturn(2)
        clearInvocations(rig.engine)

        rig.state.togglePlayPause()

        // The deferred apply is spent: play must not rebuild the queue (which would
        // clobber the session Media3 just started).
        verify(rig.engine, never()).setMediaItems(any(), any(), any())
    }

    @Test
    fun `resumption window is null with no session`() {
        val state = PlayerState()
        assertNull(state.resumptionWindow())
    }

    @Test
    fun `engine playing while apply deferred adopts the engine session`() {
        val songs = listOf(song("a"), song("b"))
        val rig = Rig(songs, index = 0, posSec = 42f)

        // A disk-path playback resumption populated and started the engine behind
        // the façade (activity alive, but provider not consulted).
        whenever(rig.engine.mediaItemCount).thenReturn(2)
        whenever(rig.engine.getMediaItemAt(0)).thenReturn(mediaItem("a"))
        whenever(rig.engine.getMediaItemAt(1)).thenReturn(mediaItem("b"))
        whenever(rig.engine.currentMediaItem).thenReturn(mediaItem("b"))
        whenever(rig.engine.isPlaying).thenReturn(true)
        whenever(rig.engine.currentPosition).thenReturn(5_000L)
        clearInvocations(rig.engine)

        rig.listener.onIsPlayingChanged(true)

        assertTrue(rig.state.isPlaying)
        assertEquals("b", rig.state.currentSong?.id)
        // Adopted = deferred apply cleared; the next user action must not rebuild
        // the queue over the live playback.
        rig.state.togglePlayPause()
        verify(rig.engine, never()).setMediaItems(any(), any(), any())
    }

    @Test
    fun `prev restart clears the deferred resume position`() {
        val rig = Rig(listOf(song("a"), song("b")), index = 1, posSec = 42f)

        rig.state.prev() // timeSec 42 > 3: restart-of-track branch

        assertEquals(0f, rig.state.timeSec)
        // First play after the restart must start at 0, not seek back to 42s.
        rig.state.togglePlayPause()
        verify(rig.engine).setMediaItems(any(), any(), eq(0L))
    }

    @Test
    fun `play with an engine that lost its items re-applies the queue`() {
        val songs = listOf(song("a"), song("b"))
        val rig = Rig(songs, index = 0, posSec = 10f)

        // Deferred apply spent, but the engine never actually received items
        // (failed handoff / service restart): play must re-apply, not no-op.
        rig.state.resumptionWindow()
        clearInvocations(rig.engine)

        rig.state.togglePlayPause()

        verify(rig.engine).setMediaItems(any(), any(), any())
        verify(rig.engine).prepare()
    }
}

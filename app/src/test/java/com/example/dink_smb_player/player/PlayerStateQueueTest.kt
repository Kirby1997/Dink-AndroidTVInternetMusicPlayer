package com.example.dink_smb_player.player

import androidx.media3.common.Player
import com.example.dink_smb_player.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Queue / skip bookkeeping between the [PlayerState] façade and the engine: skips that
 * arrive via MediaSession, positions that must not leak across tracks, the shuffle base
 * order, play-after-end, and when a track counts as played.
 */
class PlayerStateQueueTest {

    private fun song(id: String, durationSec: Int = 180) = Song(
        id = id,
        title = "Title $id",
        artist = "Artist",
        albumId = null,
        albumTitle = null,
        durationSec = durationSec,
        playCount = 0,
        sourcePath = "/music/$id.mp3",
        bitrate = "320",
        mediaUri = "smb://nas/music/$id.mp3",
    )

    private fun songs(vararg ids: String) = ids.map { song(it) }

    private class Rig(itemCount: Int = 0) {
        val engine: Player = mock()
        val state = PlayerState()
        val listener: Player.Listener

        init {
            whenever(engine.mediaItemCount).thenReturn(itemCount)
            state.attachEngine(engine)
            val captor = argumentCaptor<Player.Listener>()
            verify(engine).addListener(captor.capture())
            listener = captor.firstValue
        }
    }

    // --- Skips that bypass the façade ------------------------------------------

    @Test
    fun `session skip (SEEK transition) moves the UI to the new track`() {
        val rig = Rig(itemCount = 3)
        rig.state.playFrom(songs("a", "b", "c"), 0)

        // TV remote ⏭ → MediaSession → engine.seekToNext() directly.
        whenever(rig.engine.currentMediaItemIndex).thenReturn(1)
        rig.listener.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

        assertEquals("b", rig.state.currentSong?.id)
        assertEquals(1, rig.state.currentIndex)
        assertEquals(0f, rig.state.timeSec)
    }

    @Test
    fun `next after a session skip advances instead of restarting the same song`() {
        val rig = Rig(itemCount = 3)
        rig.state.playFrom(songs("a", "b", "c"), 0)
        whenever(rig.engine.currentMediaItemIndex).thenReturn(1)
        rig.listener.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        clearInvocations(rig.engine)

        rig.state.next()

        assertEquals("c", rig.state.currentSong?.id)
        verify(rig.engine).seekTo(2, 0L)
    }

    @Test
    fun `session next and prev route through the facade`() {
        val rig = Rig(itemCount = 3)
        rig.state.playFrom(songs("a", "b", "c"), 0)
        val transport = PlayerService.transport
        assertNotNull(transport)

        transport!!.next()
        assertEquals("b", rig.state.currentSong?.id)
        transport.prev() // timeSec 0 → previous track, not restart
        assertEquals("a", rig.state.currentSong?.id)

        rig.state.detachEngine()
        assertEquals(null, PlayerService.transport)
    }

    @Test
    fun `gapless transition refreshes the track duration once`() {
        val rig = Rig(itemCount = 2)
        rig.state.playFrom(listOf(song("a"), song("b", durationSec = 0)), 0)
        var persisted = 0
        rig.state.onMetadataResolved = { if (it.durationMs != null) persisted++ }

        whenever(rig.engine.currentMediaItemIndex).thenReturn(1)
        whenever(rig.engine.duration).thenReturn(200_000L)
        rig.listener.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(200, rig.state.durationSec)

        // A later READY (seek/rebuffer) with the same duration must not rewrite the index.
        whenever(rig.engine.playbackState).thenReturn(Player.STATE_READY)
        rig.listener.onPlaybackStateChanged(Player.STATE_READY)
        assertEquals(1, persisted)
    }

    // --- Positions must not leak across tracks ---------------------------------

    @Test
    fun `skip before the engine binds does not start the next track at the restored position`() {
        val engine: Player = mock()
        whenever(engine.mediaItemCount).thenReturn(0)
        val state = PlayerState()
        val list = songs("a", "b", "c")
        state.restore(
            PlaybackStore.Snapshot(queueIds = list.map { it.id }, index = 0, positionSec = 42f, shuffle = false, repeat = RepeatMode.Off.name),
            list.associateBy { it.id },
        )

        state.next() // engine not bound yet
        state.attachEngine(engine)

        assertEquals("b", state.currentSong?.id)
        verify(engine).setMediaItems(any(), eq(1), eq(0L))
    }

    @Test
    fun `poll during a deferred restore keeps the restored position`() {
        val rig = Rig(itemCount = 0)
        val list = songs("a", "b")
        // Attach first, then restore — the usual launch order.
        rig.state.restore(
            PlaybackStore.Snapshot(queueIds = list.map { it.id }, index = 1, positionSec = 42f, shuffle = false, repeat = RepeatMode.Off.name),
            list.associateBy { it.id },
        )

        rig.state.pollTick()
        assertEquals(42f, rig.state.timeSec)

        // So prev() restarts the restored track rather than jumping back a track.
        rig.state.prev()
        assertEquals("b", rig.state.currentSong?.id)
        assertEquals(0f, rig.state.timeSec)
    }

    @Test
    fun `seek on a track with unknown duration is not clamped to zero`() {
        val rig = Rig(itemCount = 1)
        rig.state.playFrom(listOf(song("a", durationSec = 0)), 0)

        rig.state.seek(50f)

        assertEquals(50f, rig.state.timeSec)
        verify(rig.engine).seekTo(50_000L)
    }

    // --- Queue order -------------------------------------------------------------

    @Test
    fun `shuffle after loading a track outside the queue keeps playing that track`() {
        val rig = Rig(itemCount = 3)
        rig.state.playFrom(songs("a", "b", "c"), 0)
        val d = song("d")
        rig.state.load(d, null, emptyList())

        rig.state.toggleShuffle()

        assertEquals("d", rig.state.currentSong?.id)
        assertEquals("d", rig.state.queue[rig.state.currentIndex].id)
        assertEquals(4, rig.state.queue.size)

        rig.state.toggleShuffle() // back to the base order, d still present and current
        assertEquals(listOf("a", "b", "c", "d"), rig.state.queue.map { it.id })
        assertEquals(3, rig.state.currentIndex)
    }

    @Test
    fun `shuffle toggle rebuilds the engine at the current position without a second seek`() {
        val rig = Rig(itemCount = 3)
        rig.state.playFrom(songs("a", "b", "c"), 1)
        rig.state.seek(42f)
        clearInvocations(rig.engine)

        rig.state.toggleShuffle()

        // Current track goes first in the shuffled queue and starts where it was.
        verify(rig.engine).setMediaItems(any(), eq(0), eq(42_000L))
        verify(rig.engine, never()).seekTo(any<Long>())
        assertEquals("b", rig.state.currentSong?.id)
        assertEquals(42f, rig.state.timeSec)

        clearInvocations(rig.engine)
        rig.state.toggleShuffle() // unshuffle: back to base order, same spot
        verify(rig.engine).setMediaItems(any(), eq(1), eq(42_000L))
        verify(rig.engine, never()).seekTo(any<Long>())
    }

    @Test
    fun `shuffle toggle at position zero does not inherit a stale resume position`() {
        val rig = Rig(itemCount = 0)
        val list = songs("a", "b", "c")
        rig.state.restore(
            PlaybackStore.Snapshot(queueIds = list.map { it.id }, index = 0, positionSec = 42f, shuffle = false, repeat = RepeatMode.Off.name),
            list.associateBy { it.id },
        )
        rig.state.prev() // restart the restored track: position 0
        clearInvocations(rig.engine)

        rig.state.toggleShuffle()

        verify(rig.engine).setMediaItems(any(), eq(0), eq(0L))
    }

    @Test
    fun `growing past the engine window re-asserts the repeat mode`() {
        val rig = Rig(itemCount = 100)
        rig.state.playFrom((1..100).map { song("s$it") }, 0)
        rig.state.cycleRepeatMode() // Off → All: engine loops its 100 items
        clearInvocations(rig.engine)

        rig.state.addToQueue(song("s101")) // now windowed: façade drives Repeat-All

        verify(rig.engine).repeatMode = Player.REPEAT_MODE_OFF
    }

    @Test
    fun `play after the queue ended replays the current track`() {
        val rig = Rig(itemCount = 1)
        rig.state.playFrom(songs("a"), 0)
        whenever(rig.engine.playbackState).thenReturn(Player.STATE_ENDED)
        rig.listener.onPlaybackStateChanged(Player.STATE_ENDED)
        assertFalse(rig.state.isPlaying)
        clearInvocations(rig.engine)

        rig.state.togglePlayPause()

        verify(rig.engine).seekTo(0, 0L)
        verify(rig.engine).playWhenReady = true
        assertTrue(rig.state.isPlaying)
    }

    // --- Play state ------------------------------------------------------------

    @Test
    fun `buffering after a skip still reads as playing`() {
        val rig = Rig(itemCount = 2)
        rig.state.playFrom(songs("a", "b"), 0)
        whenever(rig.engine.playWhenReady).thenReturn(true)
        whenever(rig.engine.playbackState).thenReturn(Player.STATE_BUFFERING)
        whenever(rig.engine.isPlaying).thenReturn(false)

        rig.listener.onIsPlayingChanged(false)

        assertTrue(rig.state.isPlaying)
    }

    // --- Play credit -----------------------------------------------------------

    @Test
    fun `a track counts as played only after 30 seconds of listening`() {
        val rig = Rig(itemCount = 2)
        val played = mutableListOf<String>()
        rig.state.onTrackPlayed = { played += it }
        rig.state.playFrom(songs("a", "b"), 0)
        whenever(rig.engine.isPlaying).thenReturn(true)

        repeat(40) { rig.state.pollTick() } // 10 s of "a", then skipped
        rig.state.next()
        repeat(119) { rig.state.pollTick() }
        assertTrue(played.isEmpty())
        repeat(200) { rig.state.pollTick() }

        assertEquals(listOf("b"), played)
    }

    @Test
    fun `paused time does not count towards a play`() {
        val rig = Rig(itemCount = 1)
        val played = mutableListOf<String>()
        rig.state.onTrackPlayed = { played += it }
        rig.state.playFrom(songs("a"), 0)
        whenever(rig.engine.isPlaying).thenReturn(false)

        repeat(400) { rig.state.pollTick() }

        assertTrue(played.isEmpty())
    }
}

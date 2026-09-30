package com.example.dink_smb_player.player

import androidx.media3.common.Player
import com.example.dink_smb_player.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The façade driven by [PlayerService] with no UI (PLAY-6), plus the pieces that ride on
 * it: persistence decisions (PLAY-12), the shuffle base order (PLAY-7), single engine
 * apply for a deferred session (PLAY-13) and the resumption window's side effects.
 * No Compose runtime is involved anywhere here, which is the point: the service runs the
 * same code whether or not an activity exists.
 */
class PlayerStateServiceSideTest {

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

    private fun songs(n: Int) = (0 until n).map { song("$it") }

    private fun attach(state: PlayerState, engine: Player): Player.Listener {
        state.attachEngine(engine)
        val captor = argumentCaptor<Player.Listener>()
        verify(engine).addListener(captor.capture())
        return captor.firstValue
    }

    private fun restored(list: List<Song>, index: Int, posSec: Float, repeat: RepeatMode = RepeatMode.Off) =
        PlayerState().apply {
            restore(
                PlaybackStore.Snapshot(
                    queueIds = list.map { it.id },
                    index = index,
                    positionSec = posSec,
                    repeat = repeat.name,
                ),
                list.associateBy { it.id },
            )
        }

    private fun emptyEngine(): Player = mock<Player>().also { whenever(it.mediaItemCount).thenReturn(0) }

    // --- PLAY-6: advance with no UI ----------------------------------------------

    @Test
    fun `end of the engine window advances into a rebuilt window`() {
        val engine: Player = mock()
        whenever(engine.mediaItemCount).thenReturn(100)
        val state = PlayerState()
        val listener = attach(state, engine)
        state.playFrom(songs(150), 99) // window = queue 49..148
        clearInvocations(engine)

        // Engine gapless-advanced to its last item, then ran out.
        whenever(engine.currentMediaItemIndex).thenReturn(99)
        listener.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals("148", state.currentSong?.id)
        listener.onPlaybackStateChanged(Player.STATE_ENDED)

        assertEquals("149", state.currentSong?.id)
        // New window 50..149, started on the new track.
        verify(engine).setMediaItems(any(), eq(99), eq(0L))
    }

    @Test
    fun `service teardown parks the session for the next engine`() {
        val engine: Player = mock()
        whenever(engine.mediaItemCount).thenReturn(3)
        whenever(engine.currentPosition).thenReturn(30_000L)
        val state = PlayerState()
        attach(state, engine)
        state.playFrom(songs(3), 1)
        state.pollTick()
        assertEquals(30f, state.timeSec)

        state.detachEngine()
        assertFalse(state.isPlaying)

        // Next service instance: attaching must not rebuild the queue (launch-path cost);
        // the first play applies it once, at the parked position.
        val next = emptyEngine()
        attach(state, next)
        verify(next, never()).setMediaItems(any(), any(), any())
        state.togglePlayPause()
        verify(next, times(1)).setMediaItems(any(), eq(1), eq(30_000L))
    }

    // --- PLAY-13: one engine apply for a deferred session -------------------------

    @Test
    fun `deferred jump applies the engine once at the target`() {
        val engine = emptyEngine()
        val state = restored(songs(3), index = 0, posSec = 42f)
        attach(state, engine)

        state.jumpTo(2)

        verify(engine, times(1)).setMediaItems(any(), any(), any())
        verify(engine).setMediaItems(any(), eq(2), eq(0L))
        verify(engine, never()).seekTo(any(), any())
    }

    @Test
    fun `deferred jump outside the window applies once`() {
        val engine = emptyEngine()
        val state = restored(songs(300), index = 0, posSec = 42f)
        attach(state, engine)

        state.jumpTo(250) // window 200..299

        verify(engine, times(1)).setMediaItems(any(), any(), any())
        verify(engine).setMediaItems(any(), eq(50), eq(0L))
    }

    @Test
    fun `deferred seek prepares at the target once`() {
        val engine = emptyEngine()
        val state = restored(songs(3), index = 1, posSec = 42f)
        attach(state, engine)

        state.seek(90f)

        verify(engine, times(1)).setMediaItems(any(), eq(1), eq(90_000L))
        verify(engine, never()).seekTo(any())
        verify(engine).playWhenReady = false
    }

    // --- Resumption window side effects -------------------------------------------

    @Test
    fun `resumption query does not spend the deferred apply`() {
        val engine = emptyEngine()
        val state = restored(songs(3), index = 1, posSec = 42f)
        attach(state, engine)

        assertNotNull(state.resumptionWindow(claim = false))
        state.togglePlayPause()

        verify(engine).setMediaItems(any(), eq(1), eq(42_000L))
    }

    @Test
    fun `claimed resumption hands the engine the restored repeat mode`() {
        val engine = emptyEngine()
        val state = PlayerState()
        attach(state, engine) // service attaches first, empty
        val list = songs(3)
        state.restore(
            PlaybackStore.Snapshot(queueIds = list.map { it.id }, index = 0, repeat = RepeatMode.One.name),
            list.associateBy { it.id },
        )
        clearInvocations(engine)

        state.resumptionWindow()

        verify(engine).repeatMode = Player.REPEAT_MODE_ONE
    }

    // --- PLAY-7: shuffle base order survives a restart ----------------------------

    @Test
    fun `restored shuffle can be turned off`() {
        val list = songs(20)
        val live = PlayerState()
        live.toggleShuffle() // shuffle on before anything plays
        live.playFrom(list, 5)
        val snap = requireNotNull(live.snapshot())
        assertNotNull(snap.baseOrder)

        val restored = PlayerState()
        restored.restore(snap, list.associateBy { it.id })
        val current = restored.currentSong?.id
        restored.toggleShuffle() // off

        assertEquals(list.map { it.id }, restored.queue.map { it.id })
        assertEquals(current, restored.currentSong?.id)
    }

    @Test
    fun `unshuffled session saves no base order`() {
        val live = PlayerState()
        live.playFrom(songs(5), 0)
        assertEquals(null, requireNotNull(live.snapshot()).baseOrder)
    }

    // --- PLAY-12: what the service loop writes ------------------------------------

    @Test
    fun `first tick is the baseline and writes nothing`() {
        val p = SessionPersistPolicy()
        assertEquals(SessionPersistPolicy.Action.None, p.onTick(0, "s0", isPlaying = true))
    }

    @Test
    fun `structural change saves the session once after the debounce`() {
        val p = SessionPersistPolicy(structuralDebounceMs = 800, positionEveryMs = 5_000)
        p.onTick(0, "s0", false)
        assertEquals(SessionPersistPolicy.Action.None, p.onTick(250, "s1", false))
        assertEquals(SessionPersistPolicy.Action.None, p.onTick(500, "s2", false)) // burst restarts it
        assertEquals(SessionPersistPolicy.Action.None, p.onTick(1_050, "s2", false))
        assertEquals(SessionPersistPolicy.Action.SaveSession, p.onTick(1_300, "s2", false))
        assertEquals(SessionPersistPolicy.Action.None, p.onTick(1_550, "s2", false))
    }

    @Test
    fun `playing checkpoints only the position every interval`() {
        val p = SessionPersistPolicy(structuralDebounceMs = 800, positionEveryMs = 5_000)
        p.onTick(0, "s", true)
        assertEquals(SessionPersistPolicy.Action.None, p.onTick(4_750, "s", true))
        assertEquals(SessionPersistPolicy.Action.SavePosition, p.onTick(5_000, "s", true))
        assertEquals(SessionPersistPolicy.Action.None, p.onTick(5_250, "s", true))
    }

    @Test
    fun `pause saves the position immediately and paused ticks write nothing`() {
        val p = SessionPersistPolicy(structuralDebounceMs = 800, positionEveryMs = 5_000)
        p.onTick(0, "s", true)
        assertEquals(SessionPersistPolicy.Action.SavePosition, p.onTick(1_000, "s", false))
        assertEquals(SessionPersistPolicy.Action.None, p.onTick(60_000, "s", false))
    }

    @Test
    fun `flush prefers a pending structural change`() {
        val p = SessionPersistPolicy()
        p.onTick(0, "s0", true)
        p.onTick(250, "s1", true)
        assertEquals(SessionPersistPolicy.Action.SaveSession, p.flush(300, "s1"))
        assertEquals(SessionPersistPolicy.Action.SavePosition, p.flush(400, "s1"))
    }
}

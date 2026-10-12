package com.example.dink_smb_player.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.example.dink_smb_player.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * addToQueue lets the same song id sit in the queue twice. Anything that located "the
 * current track" by id (`indexOfFirst`) landed on the FIRST copy: unshuffling jumped the
 * highlight and the next track, a restore resumed on the wrong slot, and engine tag
 * enrichment skipped the copy actually playing. These pin the slot-based behaviour.
 */
class PlayerStateDuplicateIdTest {

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

    private fun songs(vararg ids: String) = ids.map { song(it) }

    private fun mediaItem(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    private class Rig(itemCount: Int) {
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

    @Test
    fun `shuffle round trip stays on the playing copy of a song queued twice`() {
        val rig = Rig(itemCount = 4)
        rig.state.playFrom(songs("a", "b"), 0)
        rig.state.addToQueue(song("a")) // queued again: [a b a]
        rig.state.addToQueue(song("c")) // [a b a c]
        rig.state.jumpTo(2) // the SECOND a
        assertEquals(2, rig.state.currentIndex)

        rig.state.toggleShuffle()
        assertEquals(0, rig.state.currentIndex)
        assertEquals("a", rig.state.currentSong?.id)

        rig.state.toggleShuffle() // off
        assertEquals(listOf("a", "b", "a", "c"), rig.state.queue.map { it.id })
        assertEquals(2, rig.state.currentIndex) // was 0: the first copy
        rig.state.next()
        assertEquals("c", rig.state.currentSong?.id) // was "b"
    }

    @Test
    fun `unshuffle after moving onto the other copy lands on that copy's slot`() {
        val rig = Rig(itemCount = 4)
        rig.state.toggleShuffle() // shuffle on before anything plays
        rig.state.playFrom(songs("a", "b", "a", "c"), 0) // base slot 0 leads the shuffle
        // Walk the shuffled queue to the other copy of a (base slot 2).
        while (rig.state.currentIndex == 0 || rig.state.currentSong?.id != "a") rig.state.next()
        assertTrue(rig.state.currentIndex > 0)

        rig.state.toggleShuffle() // off

        assertEquals(listOf("a", "b", "a", "c"), rig.state.queue.map { it.id })
        assertEquals(2, rig.state.currentIndex)
    }

    @Test
    fun `re-loading the current track keeps its slot`() {
        val rig = Rig(itemCount = 3)
        val list = songs("a", "b", "a")
        rig.state.playFrom(list, 2)

        rig.state.load(list[2], null, emptyList())

        assertEquals(2, rig.state.currentIndex)
        assertEquals(3, rig.state.queue.size)
    }

    @Test
    fun `restore resumes on the saved slot, not the first copy`() {
        val state = PlayerState()
        val list = songs("a", "b", "a")
        state.restore(
            PlaybackStore.Snapshot(queueIds = listOf("a", "b", "a"), index = 2, positionSec = 42f),
            list.associateBy { it.id },
        )

        assertEquals(2, state.currentIndex)
        assertEquals(42f, state.timeSec)
    }

    @Test
    fun `restore follows the saved slot through dropped ids`() {
        val state = PlayerState()
        val byId = songs("a", "c").associateBy { it.id } // "b" deleted from the library
        state.restore(
            PlaybackStore.Snapshot(queueIds = listOf("a", "b", "a", "c"), index = 2, positionSec = 42f),
            byId,
        )

        assertEquals(listOf("a", "a", "c"), state.queue.map { it.id })
        assertEquals(1, state.currentIndex)
        assertEquals(42f, state.timeSec)
    }

    @Test
    fun `restored shuffle with a duplicate unshuffles onto the playing slot`() {
        val live = PlayerState()
        live.toggleShuffle()
        live.playFrom(songs("a", "b", "a", "c"), 2) // the second a leads
        val snap = requireNotNull(live.snapshot())

        val restored = PlayerState()
        restored.restore(snap, songs("a", "b", "c").associateBy { it.id })
        restored.toggleShuffle() // off

        assertEquals(listOf("a", "b", "a", "c"), restored.queue.map { it.id })
        assertEquals(2, restored.currentIndex)
    }

    @Test
    fun `engine tags enrich the playing copy of a duplicate`() {
        val rig = Rig(itemCount = 3)
        rig.state.playFrom(songs("a", "b", "a"), 2)
        whenever(rig.engine.currentMediaItem).thenReturn(mediaItem("a"))
        whenever(rig.engine.currentMediaItemIndex).thenReturn(2)

        rig.listener.onMediaMetadataChanged(MediaMetadata.Builder().setTitle("Real").build())

        assertEquals("Real", rig.state.currentSong?.title)
        assertEquals(listOf("Real", "Title b", "Real"), rig.state.queue.map { it.title })
    }

    @Test
    fun `adopting a live engine session uses the engine's current slot`() {
        val list = songs("a", "b", "a")
        val engine: Player = mock()
        whenever(engine.mediaItemCount).thenReturn(3)
        list.forEachIndexed { i, s -> whenever(engine.getMediaItemAt(i)).thenReturn(mediaItem(s.id)) }
        whenever(engine.currentMediaItem).thenReturn(mediaItem("a"))
        whenever(engine.currentMediaItemIndex).thenReturn(2)
        whenever(engine.isPlaying).thenReturn(true)
        val state = PlayerState()
        state.songResolver = { ids -> list.associateBy { it.id }.filterKeys { it in ids } }

        state.attachEngine(engine) // engine already playing → adopted

        assertEquals(listOf("a", "b", "a"), state.queue.map { it.id })
        assertEquals(2, state.currentIndex)
    }
}

package com.example.dink_smb_player.player

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.example.dink_smb_player.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Session bookkeeping that has to stay consistent across persistence and shuffle:
 * the disk-path resumption following the saved slot (not the first copy of an id),
 * reshuffles reaching the save signature, and engine-resolved tags/durations surviving
 * an unshuffle (which rebuilds the queue from the unshuffled base order).
 */
class PlayerStateSessionConsistencyTest {

    private fun song(id: String, uri: String? = "smb://nas/music/$id.mp3") = Song(
        id = id,
        title = "Title $id",
        artist = "Artist",
        albumId = null,
        albumTitle = null,
        durationSec = 180,
        playCount = 0,
        sourcePath = "/music/$id.mp3",
        bitrate = "320",
        mediaUri = uri,
    )

    private fun songs(vararg ids: String) = ids.map { song(it) }

    private fun attached(itemCount: Int): PlayerState {
        val engine: Player = mock()
        whenever(engine.mediaItemCount).thenReturn(itemCount)
        return PlayerState().also { it.attachEngine(engine) }
    }

    // --- resolveSavedQueue (PlayerService.loadResumption disk path + restore) -------

    @Test
    fun `resolved queue resumes on the saved slot of a duplicate, not the first copy`() {
        val byId = songs("a", "b").associateBy { it.id }
        val rq = resolveSavedQueue(listOf("a", "b", "a"), 2) { byId[it] }

        assertNotNull(rq)
        assertEquals(listOf("a", "b", "a"), rq!!.songs.map { it.id })
        assertEquals(2, rq.found)
        assertEquals(2, rq.index) // indexOfFirst gave 0
    }

    @Test
    fun `resolved queue follows the saved slot through unplayable and deleted ids`() {
        // loadResumption's resolve: unknown ids and tracks without a URI both drop out.
        val byId = (songs("a", "c") + song("x", uri = null)).associateBy { it.id }
        val rq = resolveSavedQueue(listOf("x", "a", "b", "a", "c"), 3) { id ->
            byId[id]?.takeIf { it.mediaUri != null }
        }

        assertEquals(listOf("a", "a", "c"), rq!!.songs.map { it.id })
        assertEquals(intArrayOf(-1, 0, -1, 1, 2).toList(), rq.compactOf.toList())
        assertEquals(1, rq.index) // the second a, not the first
        assertEquals(1, rq.found)
    }

    @Test
    fun `missing saved track falls back to the clamped index and reports not found`() {
        val byId = songs("a", "b").associateBy { it.id }
        val rq = resolveSavedQueue(listOf("a", "b", "gone"), 2) { byId[it] }

        assertEquals(-1, rq!!.found) // caller starts the fallback track at 0
        assertEquals(1, rq.index)
    }

    @Test
    fun `nothing resolving yields null`() {
        assertNull(resolveSavedQueue(listOf("a", "b"), 0) { null })
    }

    // --- saveSignature ---------------------------------------------------------

    @Test
    fun `reshuffle while already shuffled at index zero changes the save signature`() {
        val state = attached(itemCount = 4)
        state.toggleShuffle()
        state.playFrom(songs("a", "b", "c", "d"), 0)
        assertEquals(0, state.currentIndex)
        val before = state.saveSignature()
        assertEquals(before, state.saveSignature()) // stable without a change

        state.reshuffle() // same track, index 0, shuffle on, same length

        assertEquals(0, state.currentIndex)
        assertNotEquals(before, state.saveSignature())
    }

    @Test
    fun `shuffled replay of the same list changes the save signature`() {
        val state = attached(itemCount = 3)
        state.toggleShuffle()
        val list = songs("a", "b", "c")
        state.playFrom(list, 0)
        val before = state.saveSignature()

        state.playFrom(list, 0)

        assertNotEquals(before, state.saveSignature())
    }

    // --- Enrichment reaches baseOrder ------------------------------------------

    @Test
    fun `engine tags survive an unshuffle`() {
        val state = attached(itemCount = 3)
        state.toggleShuffle()
        state.playFrom(songs("a", "b", "c"), 0) // a leads the shuffle
        assertEquals("a", state.currentSong?.id)

        state.onEngineMetadata(MediaMetadata.Builder().setTitle("Real").build(), "a")
        state.toggleShuffle() // off: queue rebuilt from baseOrder

        assertEquals(listOf("Real", "Title b", "Title c"), state.queue.map { it.title })
        assertEquals("Real", state.currentSong?.title)
    }

    @Test
    fun `engine duration survives an unshuffle and fixes every copy`() {
        val state = attached(itemCount = 3)
        state.toggleShuffle()
        state.playFrom(songs("a", "b", "a"), 0)
        assertEquals("a", state.currentSong?.id)

        state.setEngineDurationMs(240_000L)
        assertEquals(240, state.currentSong?.durationSec)
        state.toggleShuffle() // off

        assertEquals(listOf(240, 180, 240), state.queue.map { it.durationSec })
        assertEquals(240, state.currentSong?.durationSec)
    }
}

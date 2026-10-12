package com.example.dink_smb_player.player

import androidx.media3.common.Player
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.player.radio.WeightedSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PlayerStateRadioTest {

    private fun song(id: String) = Song(
        id = id, title = "T$id", artist = "A$id", albumId = null, albumTitle = null, durationSec = 180,
        playCount = 0, sourcePath = "/music/$id.mp3", bitrate = "320", mediaUri = "smb://nas/music/$id.mp3",
    )

    /** Synchronous fake: records each request and answers with fresh songs r1, r2, … */
    private class FakeSource : PlayerState.RadioSource {
        val requests = mutableListOf<Triple<List<WeightedSeed>, Set<String>, Int>>()
        var next = 1
        var answer: ((Set<String>, Int) -> List<Song>)? = null
        var deferred: (() -> Unit)? = null
        var defer = false
        override fun request(seeds: List<WeightedSeed>, exclude: Set<String>, previous: Song?, count: Int, onResult: (List<Song>) -> Unit) {
            requests += Triple(seeds, exclude, count)
            val result = answer?.invoke(exclude, count) ?: List(count) { mk("r${next++}") }
            if (defer) deferred = { onResult(result) } else onResult(result)
        }
        private fun mk(id: String) = Song(
            id = id, title = id, artist = "X$id", albumId = null, albumTitle = null, durationSec = 180,
            playCount = 0, sourcePath = "/m/$id.mp3", bitrate = "320", mediaUri = "smb://nas/m/$id.mp3",
        )
    }

    private class Rig {
        val engine: Player = mock()
        val state = PlayerState()
        val source = FakeSource()
        val listener: Player.Listener
        init {
            whenever(engine.mediaItemCount).thenReturn(0)
            state.attachEngine(engine)
            val captor = argumentCaptor<Player.Listener>()
            verify(engine).addListener(captor.capture())
            listener = captor.firstValue
            state.radioSource = source
        }
    }

    @Test
    fun `startRadio plays the seed first then appends the initial batch`() {
        val rig = Rig()
        val seed = song("s")
        rig.state.startRadio(seed)
        assertEquals(seed, rig.state.currentSong)
        assertEquals(seed, rig.state.radioSeed)
        assertEquals(1 + PlayerState.RADIO_INITIAL, rig.state.queue.size)
        assertEquals("s", rig.state.queue.first().id)
        val (seeds, exclude, count) = rig.source.requests.single()
        assertEquals(PlayerState.RADIO_INITIAL, count)
        assertEquals(listOf("s"), seeds.map { it.song.id })
        assertEquals(setOf("s"), exclude)
    }

    @Test
    fun `refill fires at low water, once, excluding everything queued`() {
        val rig = Rig()
        rig.state.startRadio(song("s"))
        val size = rig.state.queue.size // 25
        // Jump to the track leaving exactly RADIO_LOW_WATER after it.
        rig.state.jumpTo(size - 1 - PlayerState.RADIO_LOW_WATER)
        assertEquals(2, rig.source.requests.size)
        assertEquals(size + PlayerState.RADIO_REFILL, rig.state.queue.size)
        assertEquals(size, rig.source.requests[1].second.size) // all 25 queued ids excluded
        // Seeds drift: original + current (+ the two before it).
        val seedIds = rig.source.requests[1].first.map { it.song.id }
        assertEquals("s", seedIds.first())
        assertEquals(4, seedIds.size)
    }

    @Test
    fun `no refill above low water`() {
        val rig = Rig()
        rig.state.startRadio(song("s"))
        rig.state.jumpTo(1)
        assertEquals(1, rig.source.requests.size)
    }

    @Test
    fun `in-flight refill is single-flight`() {
        val rig = Rig()
        rig.state.startRadio(song("s"))
        rig.source.defer = true
        val size = rig.state.queue.size
        rig.state.jumpTo(size - 3)
        rig.state.jumpTo(size - 2)
        assertEquals(2, rig.source.requests.size)
        rig.source.deferred!!.invoke()
        assertEquals(size + PlayerState.RADIO_REFILL, rig.state.queue.size)
    }

    @Test
    fun `empty result retries once with relaxed exclusions`() {
        val rig = Rig()
        var calls = 0
        rig.source.answer = { _, _ -> calls++; emptyList() }
        rig.state.startRadio(song("s"))
        assertEquals(2, calls)
        assertEquals(1, rig.state.queue.size)
        assertEquals(song("s"), rig.state.radioSeed)
    }

    @Test
    fun `playFrom turns radio off and drops late results`() {
        val rig = Rig()
        rig.source.defer = true
        rig.state.startRadio(song("s"))
        rig.state.playFrom(listOf(song("a"), song("b")), 0)
        assertNull(rig.state.radioSeed)
        rig.source.deferred!!.invoke()
        assertEquals(listOf("a", "b"), rig.state.queue.map { it.id })
    }

    @Test
    fun `stopRadio keeps the queue and stops refilling`() {
        val rig = Rig()
        rig.state.startRadio(song("s"))
        val q = rig.state.queue.map { it.id }
        rig.state.stopRadio()
        assertNull(rig.state.radioSeed)
        rig.state.jumpTo(q.size - 1)
        assertEquals(q, rig.state.queue.map { it.id })
        assertEquals(1, rig.source.requests.size)
    }

    @Test
    fun `startRadioFromCurrent keeps the current song and replaces upcoming`() {
        val rig = Rig()
        rig.state.playFrom(listOf(song("a"), song("b"), song("c"), song("d")), 1)
        rig.state.startRadioFromCurrent()
        assertEquals("b", rig.state.currentSong?.id)
        assertEquals(listOf("a", "b"), rig.state.queue.take(2).map { it.id })
        assertTrue(rig.state.queue.drop(2).all { it.id.startsWith("r") })
        assertEquals("b", rig.state.radioSeed?.id)
    }

    @Test
    fun `snapshot carries the radio seed and restore re-enables radio`() {
        val rig = Rig()
        rig.state.startRadio(song("s"))
        val snap = rig.state.snapshot()!!
        assertEquals("s", snap.radioSeedId)
        val fresh = PlayerState()
        val byId = rig.state.queue.associateBy { it.id }
        fresh.restore(snap, byId)
        assertEquals("s", fresh.radioSeed?.id)
    }
}

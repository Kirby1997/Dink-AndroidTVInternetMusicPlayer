package com.example.dink_smb_player.player

import androidx.media3.common.Player
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.player.radio.WeightedSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
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

    /** Engine mock whose mediaItemCount tracks what the façade applied/appended/trimmed. */
    private class CountingRig {
        val engine: Player = mock()
        val state = PlayerState()
        var count = 0
        init {
            whenever(engine.mediaItemCount).thenAnswer { count }
            whenever(engine.setMediaItems(any<List<androidx.media3.common.MediaItem>>(), any<Int>(), any<Long>()))
                .thenAnswer { count = (it.arguments[0] as List<*>).size; Unit }
            whenever(engine.addMediaItems(any<List<androidx.media3.common.MediaItem>>()))
                .thenAnswer { count += (it.arguments[0] as List<*>).size; Unit }
            whenever(engine.addMediaItem(any<androidx.media3.common.MediaItem>()))
                .thenAnswer { count += 1; Unit }
            whenever(engine.removeMediaItems(any<Int>(), any<Int>()))
                .thenAnswer { count -= (it.arguments[1] as Int) - (it.arguments[0] as Int); Unit }
            state.attachEngine(engine)
        }
    }

    @Test
    fun `append past the engine window still reaches the engine, head trimmed behind current`() {
        val rig = CountingRig()
        rig.state.playFrom((1..100).map { song("q$it") }, 95)
        assertEquals(100, rig.count)
        rig.state.addAllToQueue((1..20).map { song("n$it") })
        val captor = argumentCaptor<List<androidx.media3.common.MediaItem>>()
        verify(rig.engine).addMediaItems(captor.capture())
        assertEquals(20, captor.firstValue.size)
        // current engine idx 95, count 120 -> trim 20 (leaves 100); prev item (94) is kept.
        verify(rig.engine).removeMediaItems(0, 20)
        assertEquals(100, rig.count)
        assertEquals("q96", rig.state.currentSong?.id)
    }

    @Test
    fun `head trim never removes the current or previous item`() {
        val rig = CountingRig()
        rig.state.playFrom((1..100).map { song("q$it") }, 5)
        rig.state.addAllToQueue((1..20).map { song("n$it") })
        verify(rig.engine).removeMediaItems(0, 4) // current engine idx 5 -> keep 4 (prev) and 5
        assertEquals(116, rig.count)
    }

    @Test
    fun `small radio refill appends to the engine`() {
        val rig = CountingRig()
        rig.state.playFrom((1..10).map { song("q$it") }, 2)
        rig.state.addAllToQueue((1..20).map { song("n$it") })
        verify(rig.engine).addMediaItems(any<List<androidx.media3.common.MediaItem>>())
        assertEquals(30, rig.count)
    }
}

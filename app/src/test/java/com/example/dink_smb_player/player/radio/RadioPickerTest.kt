package com.example.dink_smb_player.player.radio

import com.example.dink_smb_player.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RadioPickerTest {

    private fun song(
        id: String,
        artist: String = "Artist $id",
        album: String? = "Album $id",
        genre: String? = null,
        year: Int? = null,
        // Own folder per song, no grandparent: by default songs share no folder signal.
        path: String = "/$id/$id.mp3",
    ) = Song(
        id = id, title = "T$id", artist = artist, albumId = null, albumTitle = album, durationSec = 200,
        playCount = 0, sourcePath = path, bitrate = "MP3", mediaUri = "smb://nas$path",
        genre = genre, year = year,
    )

    private fun pick(seed: Song, lib: List<Song>, count: Int, exclude: Set<String> = emptySet(), previous: Song? = seed, rnd: Int = 1) =
        RadioPicker.pick(listOf(WeightedSeed(seed, 1.0)), lib, exclude, previous, count, Random(rnd))

    // 300 unrelated filler songs so similarity, not lack of choice, decides.
    private val filler = (1..300).map { song("f$it", genre = "Polka", year = 1950) }

    @Test
    fun `same-artist songs dominate`() {
        val seed = song("s", artist = "Muse", genre = "Rock", year = 2001)
        val museTracks = (1..3).map { song("m$it", artist = "Muse", album = "Album m$it") }
        val picks = pick(seed, listOf(seed) + museTracks + filler, count = 10)
        assertEquals(10, picks.size)
        assertTrue(picks.map { it.id }.containsAll(museTracks.map { it.id }))
    }

    @Test
    fun `genre overlap counts`() {
        val seed = song("s", genre = "Shoegaze")
        val gaze = (1..8).map { song("g$it", genre = "Rock; Shoegaze") }
        val picks = pick(seed, listOf(seed) + gaze + filler, count = 10)
        assertTrue(picks.count { it.genre?.contains("Shoegaze") == true } >= 7)
    }

    @Test
    fun `year window counts when no stronger signal`() {
        val seed = song("s", year = 1994, genre = null)
        val near = (1..8).map { song("y$it", year = 1993 + (it % 3)) }
        val far = (1..300).map { song("z$it", year = 2020) }
        val picks = pick(seed, listOf(seed) + near + far, count = 10)
        assertTrue(picks.count { it.id.startsWith("y") } >= 7)
    }

    @Test
    fun `small folder counts, big flat folder does not`() {
        val seed = song("s", path = "/music/Band/Record/s.mp3")
        val siblings = (1..8).map { song("b$it", path = "/music/Band/Record/b$it.mp3") }
        val flat = (1..400).map { song("x$it", path = "/music/dump/x$it.mp3") }
        val picks = pick(seed, listOf(seed) + siblings + flat, count = 10)
        assertTrue(picks.count { it.id.startsWith("b") } >= 7)
        // seed in the big flat folder: its neighbours get no folder bonus → no scored pool → random
        val flatSeed = flat.first()
        val flatPicks = pick(flatSeed, listOf(seed) + siblings + flat, count = 10)
        assertEquals(10, flatPicks.size)
    }

    @Test
    fun `at most three per artist and never back to back`() {
        val seed = song("s", artist = "Muse", genre = "Rock")
        val muse = (1..20).map { song("m$it", artist = "Muse", album = "A${it % 4}", genre = "Rock") }
        val rock = (1..20).map { song("r$it", genre = "Rock") }
        val picks = pick(seed, listOf(seed) + muse + rock + filler, count = 20)
        assertTrue(picks.count { it.artist == "Muse" } <= 3)
        val keys = (listOf(seed) + picks).map { it.artist }
        keys.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
    }

    @Test
    fun `excluded and seed songs never returned, no duplicates`() {
        val seed = song("s", artist = "Muse")
        val muse = (1..5).map { song("m$it", artist = "Muse", album = "A$it") }
        val picks = pick(seed, listOf(seed) + muse + filler, count = 30, exclude = setOf("m1", "m2"))
        val ids = picks.map { it.id }
        assertFalse("s" in ids)
        assertFalse("m1" in ids || "m2" in ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `thin tags still fill the batch from wildcards`() {
        val seed = song("s", artist = "Unknown", album = null)
        val lib = listOf(seed) + (1..50).map { song("u$it", artist = "Unknown", album = null) }
        assertEquals(20, pick(seed, lib, count = 20).size)
    }

    @Test
    fun `returns fewer only when the library runs out`() {
        val seed = song("s")
        val lib = listOf(seed, song("a"), song("b"))
        assertEquals(2, pick(seed, lib, count = 20).size)
        assertTrue(pick(seed, emptyList(), count = 5).isEmpty())
        assertTrue(pick(seed, lib, count = 0).isEmpty())
    }

    @Test
    fun `deterministic for a seeded random`() {
        val seed = song("s", genre = "Rock")
        val lib = listOf(seed) + (1..40).map { song("r$it", genre = "Rock") } + filler
        assertEquals(pick(seed, lib, 20, rnd = 7), pick(seed, lib, 20, rnd = 7))
    }

    @Test
    fun `generic artist names do not match each other`() {
        val seed = song("s", artist = "Various Artists", album = null)
        val va = (1..10).map { song("v$it", artist = "Various Artists", album = null) }
        val others = (1..10).map { song("o$it", genre = null) }
        // no signal at all → pure wildcards; just ensure no crash and full batch
        assertEquals(15, pick(seed, listOf(seed) + va + others, count = 15).size)
    }
}

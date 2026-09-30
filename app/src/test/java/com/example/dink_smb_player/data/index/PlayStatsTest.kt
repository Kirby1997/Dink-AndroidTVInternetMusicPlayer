package com.example.dink_smb_player.data.index

import com.example.dink_smb_player.data.library.GuardedJsonFile
import com.example.dink_smb_player.data.library.LibraryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** LIB-15: play stats live in a map beside the track list; LIB-3: they reach disk. */
class PlayStatsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun row(id: String, playCount: Int = 0, lastPlayedMs: Long? = null) = TrackEntity(
        id = id, title = "T$id", durationMs = 1000, sourceType = SourceType.Smb, sourceId = "s",
        path = "/$id.mp3", uri = "smb://h/$id.mp3", sizeBytes = 1, addedAtMs = 0,
        playCount = playCount, lastPlayedMs = lastPlayedMs,
    )

    private fun dao(vararg rows: TrackEntity): Pair<IndexDao, MutableStateFlow<List<TrackEntity>>> {
        val tracks = MutableStateFlow(rows.toList())
        return IndexDao(tracks, MutableStateFlow(emptyList())) { false } to tracks
    }

    @Test
    fun `a play patches the stats map and leaves the track list instance alone`() = runBlocking {
        val (dao, tracks) = dao(row("a"), row("b"))
        val before = tracks.value
        assertTrue(dao.markPlayed("a", 100))
        assertTrue(dao.markPlayed("a", 200))
        assertSame(before, tracks.value)
        assertEquals(PlayStat(2, 200), dao.playStats.value["a"])
        assertNull(dao.playStats.value["b"])
    }

    @Test
    fun `a play of an unindexed id records nothing`() = runBlocking {
        val (dao, _) = dao(row("a"))
        assertFalse(dao.markPlayed("zzz", 100))
        assertTrue(dao.playStats.value.isEmpty())
    }

    @Test
    fun `restore seeds stats, strips rows and adds plays recorded before it`() = runBlocking {
        val (dao, tracks) = dao(row("a"))       // e.g. a local row MediaStore already indexed
        dao.markPlayed("a", 900)                // played while the file was still loading
        dao.restoreTracks(listOf(row("a", playCount = 5, lastPlayedMs = 500), row("b", playCount = 2, lastPlayedMs = 50)))
        assertEquals(PlayStat(6, 900), dao.playStats.value["a"])
        assertEquals(PlayStat(2, 50), dao.playStats.value["b"])
        assertTrue(tracks.value.all { it.playCount == 0 && it.lastPlayedMs == null })
    }

    @Test
    fun `persisted JSON round-trip keeps counts`() = runBlocking {
        val (dao, _) = dao()
        dao.restoreTracks(listOf(row("a", playCount = 3, lastPlayedMs = 30), row("b")))
        dao.markPlayed("a", 40)
        dao.markPlayed("b", 41)
        val (t, s) = dao.persistSnapshot()
        val file = LibraryStore.guardedFile(tmp.root)
        assertTrue(file.save(LibraryStore.Snapshot(t, s)))
        val loaded = (file.load() as GuardedJsonFile.Load.Ok).value

        assertEquals(4, loaded.tracks.first { it.id == "a" }.playCount)
        assertEquals(40L, loaded.tracks.first { it.id == "a" }.lastPlayedMs)
        val (next, _) = dao()
        next.restoreTracks(loaded.tracks)
        assertEquals(PlayStat(4, 40), next.playStats.value["a"])
        assertEquals(PlayStat(1, 41), next.playStats.value["b"])
    }

    @Test
    fun `recently played is newest first and only indexed rows`() = runBlocking {
        val (dao, tracks) = dao(row("a"), row("b"), row("c"))
        dao.markPlayed("a", 10)
        dao.markPlayed("c", 30)
        dao.markPlayed("b", 20)
        assertEquals(listOf("c", "b", "a"), dao.observeRecentlyPlayed(10).first().map { it.id })
        assertEquals(listOf("c"), dao.observeRecentlyPlayed(1).first().map { it.id })
        tracks.value = tracks.value.filterNot { it.id == "c" }   // source removed
        assertEquals(listOf("b", "a"), dao.observeRecentlyPlayed(10).first().map { it.id })
    }
}

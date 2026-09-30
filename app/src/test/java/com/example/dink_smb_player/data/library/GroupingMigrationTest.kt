package com.example.dink_smb_player.data.library

import com.example.dink_smb_player.data.index.IndexDao
import com.example.dink_smb_player.data.index.PlayStat
import com.example.dink_smb_player.data.index.SourceEntity
import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** LIB-6: the one-time recompute of pre-LIB-6 (title-only) album keys on restore. */
class GroupingMigrationTest {

    // A row as an older build wrote it: title-only albumKey, play stats on the row.
    private fun oldRow(id: String, artist: String, plays: Int, addedAt: Long) = TrackEntity(
        id = id, title = id, artist = artist, albumTitle = "Greatest Hits", durationMs = 1000,
        sourceType = SourceType.Smb, sourceId = "s", path = "/$artist/GH/$id.mp3", uri = "smb://h/$artist/GH/$id.mp3",
        sizeBytes = 100, addedAtMs = addedAt, playCount = plays, lastPlayedMs = if (plays > 0) 99L else null,
        artistKey = artist.lowercase(), albumKey = "greatesthits", artistLabel = artist,
    )

    private fun dao(): Pair<MutableStateFlow<List<TrackEntity>>, IndexDao> {
        val tracks = MutableStateFlow<List<TrackEntity>>(emptyList())
        val sources = MutableStateFlow(listOf(SourceEntity(id = "s", type = SourceType.Smb, displayName = "S", createdAtMs = 0)))
        return tracks to IndexDao(tracks, sources) { false }
    }

    @Test
    fun `restore recomputes title-only album keys and keeps play stats and addedAt`() = runBlocking {
        val (tracks, dao) = dao()
        val disk = listOf(oldRow("q", "Queen", plays = 5, addedAt = 111), oldRow("a", "ABBA", plays = 0, addedAt = 222))
        dao.restoreTracks(disk)

        assertTrue(LibraryRepository.migrateGroupingKeys(dao, disk))

        val byId = tracks.value.associateBy { it.id }
        assertEquals("queen|greatesthits", byId.getValue("q").albumKey)
        assertEquals("abba|greatesthits", byId.getValue("a").albumKey)
        assertEquals(111L, byId.getValue("q").addedAtMs)
        assertEquals(222L, byId.getValue("a").addedAtMs)
        assertEquals(PlayStat(5, 99L), dao.playStats.value["q"])
        // What goes to disk still carries the plays on the row.
        val onDisk = dao.persistSnapshot().first.associateBy { it.id }
        assertEquals(5, onDisk.getValue("q").playCount)
        assertEquals(99L, onDisk.getValue("q").lastPlayedMs)

        // Second boot: keys are current, nothing to do.
        val before = tracks.value
        assertFalse(LibraryRepository.migrateGroupingKeys(dao, onDisk.values.toList()))
        assertSame(before, tracks.value)
    }

    @Test
    fun `a play landing during the migration is not lost`() = runBlocking {
        val (tracks, dao) = dao()
        val disk = listOf(oldRow("q", "Queen", plays = 1, addedAt = 1))
        dao.restoreTracks(disk)
        dao.markPlayed("q", 500)
        LibraryRepository.migrateGroupingKeys(dao, disk)
        assertEquals(2, dao.playStats.value.getValue("q").count)
        assertEquals("queen|greatesthits", tracks.value.single().albumKey)
    }
}

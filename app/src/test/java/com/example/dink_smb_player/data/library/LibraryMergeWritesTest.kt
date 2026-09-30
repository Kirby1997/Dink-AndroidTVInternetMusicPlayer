package com.example.dink_smb_player.data.library

import com.example.dink_smb_player.data.index.IndexDao
import com.example.dink_smb_player.data.index.SourceEntity
import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-B repository write paths against an in-memory DAO: monitor no-delta skip (SRC-6),
 *  change → re-read decision (SRC-8), enrich key recompute (LIB-13), local merge (item 8). */
class LibraryMergeWritesTest {

    private val source = SourceEntity(id = "s", type = SourceType.Smb, displayName = "S", createdAtMs = 0)
    private val prefix = "/m/"

    private fun row(id: String, title: String = id, artist: String? = "Artist") = TrackEntity(
        id = id, title = title, artist = artist, albumTitle = "Album", durationMs = 1000,
        sourceType = SourceType.Smb, sourceId = "s", path = "/m/$id.mp3", uri = "smb://h/m/$id.mp3",
        sizeBytes = 100, addedAtMs = 1, fileMtimeMs = 50,
    )

    private class Fixture(rows: List<TrackEntity>, removed: Set<String> = emptySet()) {
        val tracks = MutableStateFlow(rows)
        val sources = MutableStateFlow(listOf(SourceEntity(id = "s", type = SourceType.Smb, displayName = "S", createdAtMs = 0)))
        val dao = IndexDao(tracks, sources) { it in removed }
    }

    private suspend fun keyed(vararg rows: TrackEntity): Fixture {
        val f = Fixture(rows.toList())
        LibraryRepository.recomputeGroupingKeys(f.dao)
        return f
    }

    @Test
    fun `monitor pass with no delta skips stats, recompute and persist`() = runBlocking {
        val f = keyed(row("a"), row("b"))
        f.dao.markPlayed("a", 5)
        val tracksBefore = f.tracks.value
        val sourcesBefore = f.sources.value
        // The walk hands back its (stale, pre-play) reuse of every row.
        val fresh = listOf(row("a"), row("b"))
        val changed = LibraryRepository.reconcileMonitored(f.dao, source, fresh, listOf(prefix), prune = true)
        assertFalse(changed)
        assertSame(tracksBefore, f.tracks.value)
        assertSame("no stats update", sourcesBefore, f.sources.value)
        assertEquals(1, f.dao.playStats.value.getValue("a").count)
    }

    @Test
    fun `monitor pass reports new, changed and pruned rows`() = runBlocking {
        val f = keyed(row("a"), row("b"))
        assertTrue(LibraryRepository.reconcileMonitored(f.dao, source, listOf(row("a"), row("b"), row("c")), listOf(prefix), prune = true))
        assertEquals(setOf("a", "b", "c"), f.tracks.value.map { it.id }.toSet())
        assertTrue("new row keyed", f.tracks.value.first { it.id == "c" }.artistKey != null)
        // b deleted on the NAS: a complete walk prunes it.
        assertTrue(LibraryRepository.reconcileMonitored(f.dao, source, listOf(row("a"), row("c")), listOf(prefix), prune = true))
        assertEquals(setOf("a", "c"), f.tracks.value.map { it.id }.toSet())
        assertEquals(2, f.sources.value.single().trackCount)
    }

    @Test
    fun `monitor pass for a removed source writes nothing`() = runBlocking {
        val f = Fixture(listOf(row("a")), removed = setOf("s"))
        val before = f.tracks.value
        assertFalse(LibraryRepository.reconcileMonitored(f.dao, source, listOf(row("a"), row("new")), listOf(prefix), prune = true))
        assertSame(before, f.tracks.value)
    }

    @Test
    fun `rescan read decision - changed file, missing duration, otherwise reuse`() {
        val stamped = row("a").copy(retagAttemptedMs = 1, retagVersion = LibraryRepository.RETAG_VERSION)
        assertFalse(LibraryRepository.needsRescanRead(stamped, 100, 50))
        assertTrue("size changed", LibraryRepository.needsRescanRead(stamped, 101, 50))
        assertTrue("mtime changed", LibraryRepository.needsRescanRead(stamped, 100, 51))
        assertFalse("legacy row, mtime unknown", LibraryRepository.needsRescanRead(stamped.copy(fileMtimeMs = null), 100, 51))
        val noDuration = row("a").copy(durationMs = 0)
        assertTrue("never conclusively read", LibraryRepository.needsRescanRead(noDuration, 100, 50))
        assertTrue("legacy v0 stamp", LibraryRepository.needsRescanRead(noDuration.copy(retagAttemptedMs = 1, retagVersion = 0), 100, 50))
        assertFalse(
            "conclusively read, genuinely no duration",
            LibraryRepository.needsRescanRead(noDuration.copy(retagAttemptedMs = 1, retagVersion = LibraryRepository.RETAG_VERSION), 100, 50),
        )
    }

    @Test
    fun `enrich recomputes grouping keys when artist or album changes`() = runBlocking {
        val f = keyed(row("a", title = "a", artist = "Folder Name"))
        val before = f.tracks.value.single()
        assertEquals("foldername", before.artistKey)
        f.dao.markPlayed("a", 9)
        assertTrue(
            LibraryRepository.enrichIn(f.dao, "a", "Song", "The Beatles", "Abbey Road", 1969, 1, 259_000),
        )
        val after = f.tracks.value.single()
        assertEquals("beatles", after.artistKey)
        assertEquals("beatles|abbeyroad", after.albumKey)  // LIB-6: album artist + title
        assertEquals("The Beatles", after.artistLabel)
        assertEquals("Song", after.title)
        assertEquals(1, f.dao.persistSnapshot().first.single().playCount)
        // Same tags again: nothing to write.
        assertFalse(LibraryRepository.enrichIn(f.dao, "a", "Song", "The Beatles", "Abbey Road", 1969, 1, 259_000))
    }

    @Test
    fun `enrich without an artist or album change keeps keys`() = runBlocking {
        val f = keyed(row("a"))
        val keysBefore = f.tracks.value.single().artistKey
        assertTrue(LibraryRepository.enrichIn(f.dao, "a", "Better Title", null, null, null, null, null))
        assertEquals(keysBefore, f.tracks.value.single().artistKey)
        assertEquals("Better Title", f.tracks.value.single().title)
    }

    @Test
    fun `local import carries plays, retag version, mtime and size and skips when unchanged`() = runBlocking {
        val local = SourceEntity(id = "local", type = SourceType.Local, displayName = "L", createdAtMs = 0)
        fun localRow(id: String) = TrackEntity(
            id = id, title = "T$id", artist = "A", albumTitle = "B", durationMs = 1000,
            sourceType = SourceType.Local, sourceId = "local", path = "/sd/$id.mp3", uri = "content://$id",
            sizeBytes = 0, addedAtMs = System.currentTimeMillis(),
        )
        val f = Fixture(emptyList())
        assertTrue(LibraryRepository.mergeSource(f.dao, local, listOf(localRow("x"))))
        val first = f.tracks.value.single()
        f.tracks.value = listOf(first.copy(playCount = 3, retagAttemptedMs = 7, retagVersion = 1, fileMtimeMs = 8, sizeBytes = 9))
        val before = f.tracks.value
        // A later refresh rebuilds the row from MediaStore with a new addedAtMs and no stats.
        assertFalse(LibraryRepository.mergeSource(f.dao, local, listOf(localRow("x").copy(addedAtMs = first.addedAtMs + 1))))
        assertSame(before, f.tracks.value)
        assertTrue(LibraryRepository.mergeSource(f.dao, local, listOf(localRow("x").copy(title = "Retitled"))))
        val after = f.tracks.value.single()
        assertEquals("Retitled", after.title)
        assertEquals(first.addedAtMs, after.addedAtMs)
        assertEquals(3, after.playCount)
        assertEquals(1, after.retagVersion)
        assertEquals(8L, after.fileMtimeMs)
        assertEquals(9L, after.sizeBytes)
        assertNotEquals(null, after.artistKey)
    }

    @Test
    fun `a retag that fills the album-artist tag re-keys the album after the recompute`() = runBlocking {
        val f = keyed(row("a", artist = "Queen"))
        val before = f.tracks.value.single()
        assertTrue(before.albumKey!!.startsWith("queen|"))
        f.dao.upsertTracks(listOf(before.copy(albumArtist = "Various Artists")), com.example.dink_smb_player.data.index.TrackMerges.retag(mapOf("a" to before)))
        LibraryRepository.recomputeGroupingKeys(f.dao)
        val after = f.tracks.value.single()
        assertEquals("Various Artists", after.albumArtist)
        assertTrue(after.albumKey, after.albumKey!!.startsWith("variousartists|"))
        assertEquals("track artist is untouched", "queen", after.artistKey)
    }
}

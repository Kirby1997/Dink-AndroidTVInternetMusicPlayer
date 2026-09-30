package com.example.dink_smb_player.data.index

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** LIB-4 / SRC-6: per-writer merges applied against the CURRENT row inside the DAO update. */
class TrackMergeTest {

    private fun row(id: String = "a", source: String = "s") = TrackEntity(
        id = id, title = "01 file", artist = "Folder", albumTitle = "Dir", durationMs = 0L,
        sourceType = SourceType.Smb, sourceId = source, path = "/m/$id.mp3", uri = "smb://h/$id.mp3",
        sizeBytes = 1000, addedAtMs = 10, fileMtimeMs = 500,
    )

    /** Row as it looks after plays, enrichment, a recompute and a retag. */
    private fun enrichedPlayed(id: String = "a") = row(id).copy(
        title = "Real Title", artist = "Real Artist", albumTitle = "Real Album", durationMs = 200_000,
        playCount = 7, lastPlayedMs = 999, artistKey = "realartist", albumKey = "realalbum",
        artistLabel = "Real Artist", retagAttemptedMs = 42, retagVersion = 1,
    )

    private fun dao(vararg rows: TrackEntity, removed: Set<String> = emptySet()): Pair<IndexDao, MutableStateFlow<List<TrackEntity>>> {
        val tracks = MutableStateFlow(rows.toList())
        return IndexDao(tracks, MutableStateFlow(emptyList())) { it in removed } to tracks
    }

    @Test
    fun `walk over an enriched played row keeps plays and enrichment`() = runBlocking {
        val current = enrichedPlayed()
        // The walk reused a snapshot taken before the plays/enrichment: stale everything.
        val stale = row().copy(fileMtimeMs = 500)
        val (dao, state) = dao(current)
        assertEquals(0, dao.upsertTracks(listOf(stale), TrackMerges.Walk))
        assertSame("unchanged → list not replaced", current, state.value.single())
    }

    @Test
    fun `walk backfills listing fields only`() {
        val current = enrichedPlayed().copy(fileMtimeMs = null)
        val merged = TrackMerges.Walk.merge(current, row().copy(fileMtimeMs = 777))!!
        assertEquals(current.copy(fileMtimeMs = 777), merged)
    }

    @Test
    fun `walk takes a changed file's re-read tags but keeps plays`() {
        val current = enrichedPlayed()
        val reread = row().copy(title = "New Title", sizeBytes = 2000, fileMtimeMs = 900, addedAtMs = 5000)
        val merged = TrackMerges.Walk.merge(current, reread)!!
        assertEquals("New Title", merged.title)
        assertEquals(2000, merged.sizeBytes)
        assertEquals(900L, merged.fileMtimeMs)
        assertEquals(7, merged.playCount)
        assertEquals(999L, merged.lastPlayedMs)
        assertEquals(10L, merged.addedAtMs)
    }

    @Test
    fun `walk re-read of an unchanged unstamped file takes its tags with the stamp`() {
        // Row indexed from the path, never conclusively read, no duration: the walk re-reads it.
        val current = row().copy(playCount = 7, retagAttemptedMs = null, retagVersion = 0)
        val reread = current.copy(
            title = "Real Title", artist = "Real Artist", albumTitle = "Real Album", albumArtist = "Band",
            year = 1999, trackNumber = 4, durationMs = 180_000, retagAttemptedMs = 100, retagVersion = 1,
        )
        val merged = TrackMerges.Walk.merge(current, reread)!!
        assertEquals("Real Title", merged.title)
        assertEquals("Real Artist", merged.artist)
        assertEquals("Real Album", merged.albumTitle)
        assertEquals("Band", merged.albumArtist)
        assertEquals(1999, merged.year)
        assertEquals(4, merged.trackNumber)
        assertEquals(180_000L, merged.durationMs)
        assertEquals(100L, merged.retagAttemptedMs)
        assertEquals(7, merged.playCount)
    }

    @Test
    fun `walk re-read never replaces stored tags with empty ones`() {
        val current = enrichedPlayed().copy(durationMs = 0, retagAttemptedMs = null, retagVersion = 0)
        val probe = current.copy(
            title = "", artist = null, albumTitle = " ", year = null, trackNumber = null,
            durationMs = 180_000, retagAttemptedMs = 100, retagVersion = 1,
        )
        val merged = TrackMerges.Walk.merge(current, probe)!!
        assertEquals("Real Title", merged.title)
        assertEquals("Real Artist", merged.artist)
        assertEquals("Real Album", merged.albumTitle)
        assertEquals(180_000L, merged.durationMs)
        assertEquals(100L, merged.retagAttemptedMs)
    }

    @Test
    fun `walk without a newer stamp leaves an unchanged file's tags alone`() {
        val current = enrichedPlayed()
        val stale = row().copy(title = "01 file", retagAttemptedMs = 42, retagVersion = 1)
        assertEquals(current, TrackMerges.Walk.merge(current, stale))
    }

    @Test
    fun `restore fills missing rows and keeps rows written before it`() = runBlocking {
        val fresh = row("a").copy(title = "Fresh", addedAtMs = 999)
        val (dao, state) = dao(fresh)
        dao.restoreTracks(listOf(row("a").copy(title = "Disk", addedAtMs = 1, playCount = 3), row("b")))
        val a = state.value.single { it.id == "a" }
        assertEquals("Fresh", a.title)
        assertEquals(1L, a.addedAtMs)
        assertEquals(3, dao.playStats.value.getValue("a").count)
        assertEquals(listOf("a", "b"), state.value.map { it.id })
    }

    @Test
    fun `walk inserts a new file as read`() {
        val r = row()
        assertEquals(r, TrackMerges.Walk.merge(null, r))
    }

    @Test
    fun `retag keeps plays and patches only what it changed`() {
        val base = row()                               // what retag read
        val retagged = base.copy(title = "Tagged", durationMs = 1234, retagAttemptedMs = 50, retagVersion = 1)
        // Meanwhile: played twice and enriched the album.
        val current = base.copy(playCount = 2, lastPlayedMs = 77, albumTitle = "Enriched Album")
        val merged = TrackMerges.retag(mapOf(base.id to base)).merge(current, retagged)!!
        assertEquals("Tagged", merged.title)
        assertEquals(1234L, merged.durationMs)
        assertEquals(50L, merged.retagAttemptedMs)
        assertEquals(2, merged.playCount)
        assertEquals(77L, merged.lastPlayedMs)
        assertEquals("Enriched Album", merged.albumTitle)
    }

    @Test
    fun `stale snapshot can't regress playCount`() = runBlocking {
        val base = row()
        val (dao, state) = dao(base)
        val snapshot = dao.snapshot().first.single()   // retag / recompute read it here
        dao.markPlayed(base.id, 1_000)                 // a play lands meanwhile
        dao.markPlayed(base.id, 2_000)
        dao.upsertTracks(listOf(snapshot.copy(title = "Tagged")), TrackMerges.retag(mapOf(base.id to snapshot)))
        dao.upsertTracks(listOf(snapshot.copy(artistKey = "k")), TrackMerges.keys(mapOf(base.id to snapshot)))
        val now = state.value.single()
        // Plays live in the stats map (LIB-15); the persisted view carries them.
        val persisted = dao.persistSnapshot().first.single()
        assertEquals(2, persisted.playCount)
        assertEquals(2_000L, persisted.lastPlayedMs)
        assertEquals("Tagged", now.title)
        assertEquals("k", now.artistKey)
    }

    @Test
    fun `retag, keys and enrich never re-create a pruned row`() = runBlocking {
        val (dao, state) = dao()
        val r = row()
        assertEquals(0, dao.upsertTracks(listOf(r), TrackMerges.retag(emptyMap())))
        assertEquals(0, dao.upsertTracks(listOf(r), TrackMerges.keys(emptyMap())))
        assertEquals(0, dao.upsertTracks(listOf(r), TrackMerges.enrich(r)))
        assertTrue(state.value.isEmpty())
    }

    @Test
    fun `local refresh keeps index-owned fields, retag version, mtime and size`() {
        val current = enrichedPlayed().copy(sourceType = SourceType.Local, fileMtimeMs = 5, sizeBytes = 99)
        val fresh = row().copy(sourceType = SourceType.Local, title = "MediaStore Title", sizeBytes = 0, fileMtimeMs = null, addedAtMs = 123)
        val merged = TrackMerges.Local.merge(current, fresh)!!
        assertEquals("MediaStore Title", merged.title)
        assertEquals(10L, merged.addedAtMs)
        assertEquals(7, merged.playCount)
        assertEquals(42L, merged.retagAttemptedMs)
        assertEquals(1, merged.retagVersion)
        assertEquals(5L, merged.fileMtimeMs)
        assertEquals(99L, merged.sizeBytes)
        assertEquals("realartist", merged.artistKey)
    }

    @Test
    fun `fileChanged ignores unknown values`() {
        val stored = row()
        assertFalse(TrackMerges.fileChanged(stored, 1000, 500))
        assertTrue(TrackMerges.fileChanged(stored, 1001, 500))
        assertTrue(TrackMerges.fileChanged(stored, 1000, 501))
        assertFalse("legacy row without mtime", TrackMerges.fileChanged(stored.copy(fileMtimeMs = null), 1000, 501))
        assertFalse("listing without mtime", TrackMerges.fileChanged(stored, 1000, null))
        assertFalse("unknown stored size", TrackMerges.fileChanged(stored.copy(sizeBytes = 0), 1234, 500))
    }

    @Test
    fun `upsert returns 0 and keeps the list when nothing changes`() = runBlocking {
        val r = enrichedPlayed()
        val (dao, state) = dao(r)
        val before = state.value
        assertEquals(0, dao.upsertTracks(listOf(r), TrackMerges.Walk))
        assertSame(before, state.value)
        assertEquals(1, dao.upsertTracks(listOf(row("b")), TrackMerges.Walk))
        assertEquals(listOf("a", "b"), state.value.map { it.id })
    }

    @Test
    fun `writes for a removed source are dropped`() = runBlocking {
        val live = row("a", source = "live")
        val gone = row("b", source = "gone")
        val (dao, state) = dao(live, gone, removed = setOf("gone"))
        assertEquals(0, dao.upsertTracks(listOf(row("c", source = "gone")), TrackMerges.Walk))
        assertEquals(0, dao.upsertTracks(listOf(gone.copy(title = "x")), TrackMerges.Restore))
        assertEquals(1, dao.upsertTracks(listOf(row("d", source = "live")), TrackMerges.Walk))
        assertEquals(0, dao.pruneSource(SourceType.Smb, "gone", emptyList()))
        dao.upsertSource(SourceEntity(id = "gone", type = SourceType.Smb, displayName = "G", createdAtMs = 0))
        assertTrue(dao.snapshot().second.isEmpty())
        assertFalse(dao.acceptsWrites("gone"))
        assertTrue(dao.acceptsWrites("live"))
        assertEquals(listOf("a", "b", "d"), state.value.map { it.id })
        assertNull(state.value.firstOrNull { it.id == "c" })
    }

    @Test
    fun `album artist is a tag field - retag patches it, walk takes it from a changed file`() = runBlocking {
        val current = enrichedPlayed()
        val (dao, state) = dao(current)
        // Retag read the album-artist tag; nothing else changed relative to its base.
        dao.upsertTracks(listOf(current.copy(albumArtist = "Various Artists")), TrackMerges.retag(mapOf("a" to current)))
        assertEquals("Various Artists", state.value.single().albumArtist)
        assertEquals(7, state.value.single().playCount)
        // A retag that didn't touch it (same as its base) never reverts a newer value.
        val stale = current.copy(title = "Retitled")
        dao.upsertTracks(listOf(stale), TrackMerges.retag(mapOf("a" to current)))
        assertEquals("Various Artists", state.value.single().albumArtist)
        // Walk: a changed file carries its re-read album artist.
        val changed = row().copy(sizeBytes = 2000, albumArtist = "Queen")
        assertEquals("Queen", TrackMerges.Walk.merge(state.value.single(), changed)!!.albumArtist)
    }
}

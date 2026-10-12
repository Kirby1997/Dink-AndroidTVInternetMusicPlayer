package com.example.dink_smb_player.data.library

import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import com.example.dink_smb_player.data.source.ReadResult
import com.example.dink_smb_player.data.source.TagReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GenreBackfillTest {

    private fun row(
        type: SourceType = SourceType.Smb,
        genre: String? = null,
        stampedMs: Long? = 10,
        version: Int = 1,
        year: Int? = null,
        title: String = "Real Title",
        durationMs: Long = 200_000,
    ) = TrackEntity(
        id = "t", title = title, artist = "A", durationMs = durationMs, sourceType = type,
        sourceId = "s", path = "/m/t.mp3", uri = "smb://nas/m/t.mp3", sizeBytes = 1, addedAtMs = 0,
        retagAttemptedMs = stampedMs, retagVersion = version, year = year, genre = genre,
    )

    @Test
    fun `retag version is 2`() = assertEquals(2, LibraryRepository.RETAG_VERSION)

    @Test
    fun `selects SMB rows without genre below current version that retag would not read`() {
        assertTrue(LibraryRepository.needsGenreBackfill(row()))
        // Unstamped v0 row with a real title + duration: retag/walk never pick it up.
        assertTrue(LibraryRepository.needsGenreBackfill(row(stampedMs = null, version = 0)))
        assertTrue(LibraryRepository.needsGenreBackfill(row(stampedMs = 10, version = 0)))
        assertFalse(LibraryRepository.needsGenreBackfill(row(genre = "Rock")))
        assertFalse(LibraryRepository.needsGenreBackfill(row(version = 2)))
        assertFalse(LibraryRepository.needsGenreBackfill(row(type = SourceType.Local)))
        // Rows retag WOULD read are left to it (stamping would hide the missing title/duration).
        assertFalse(LibraryRepository.needsGenreBackfill(row(title = "t")))
        assertFalse(LibraryRepository.needsGenreBackfill(row(durationMs = 0)))
    }

    @Test
    fun `retagWouldRead matches the retag content filter`() {
        assertTrue(LibraryRepository.retagWouldRead(row(title = "t")))
        assertTrue(LibraryRepository.retagWouldRead(row(durationMs = 0)))
        assertFalse(LibraryRepository.retagWouldRead(row()))
    }

    @Test
    fun `found writes genre and missing year only, and stamps`() {
        val r = row(year = null)
        val out = LibraryRepository.genreBackfilled(
            r, ReadResult.Found(TagReader.Tags(title = "Other Title", artist = "B", year = 1999, genre = "Jazz")), 500,
        )!!
        assertEquals("Jazz", out.genre)
        assertEquals(1999, out.year)
        assertEquals("Real Title", out.title)
        assertEquals("A", out.artist)
        assertEquals(500L, out.retagAttemptedMs)
        assertEquals(2, out.retagVersion)
        val keepYear = LibraryRepository.genreBackfilled(
            row(year = 1980), ReadResult.Found(TagReader.Tags(year = 1999, genre = "Jazz")), 500,
        )!!
        assertEquals(1980, keepYear.year)
    }

    @Test
    fun `absent stamps without genre, transient error writes nothing`() {
        val absent = LibraryRepository.genreBackfilled(row(), ReadResult.Absent, 500)!!
        assertNull(absent.genre)
        assertEquals(2, absent.retagVersion)
        assertNull(LibraryRepository.genreBackfilled(row(), ReadResult.Error(IOException("down"), null), 500))
    }
}

package com.example.dink_smb_player.data.source.smb

import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import com.example.dink_smb_player.data.index.TrackMerges
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.source.ReadResult
import com.example.dink_smb_player.data.source.TagReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Review finding 1 + sources follow-up: a walk's re-read of an already-indexed file. */
class SmbRereadRowTest {

    private val stored = TrackEntity(
        id = "a", title = "Real Title", artist = "Real Artist", albumTitle = "Real Album", durationMs = 200_000,
        sourceType = SourceType.Smb, sourceId = "s", path = "/smb/share/Artist/Album/01 file.mp3",
        uri = "smb://h/share/Artist/Album/01%20file.mp3", sizeBytes = 1000, addedAtMs = 10, fileMtimeMs = 500,
        retagAttemptedMs = 42, retagVersion = LibraryRepository.RETAG_VERSION,
    )

    /** Indexed from the path before tag reading worked: filename title, no duration, no stamp. */
    private val pathOnly = stored.copy(
        title = "01 file", artist = "Artist", albumTitle = "Album", durationMs = 0,
        retagAttemptedMs = null, retagVersion = 0,
    )

    @Test
    fun `changed file with a failed read keeps the old tags and the old listing`() {
        val row = SmbImporter.rereadRow(stored, ReadResult.Error(IOException("timeout")), 2000, 900, nowMs = 1)
        assertSame(stored, row)
        // The next walk still sees the change and retries the read.
        assertTrue(LibraryRepository.needsRescanRead(row, 2000, 900))
        // And the walk merge leaves the stored row as it is.
        assertEquals(stored, TrackMerges.Walk.merge(stored, row))
    }

    @Test
    fun `changed file with no tags keeps the stored tags, takes the new listing, is stamped`() {
        val row = SmbImporter.rereadRow(stored, ReadResult.Absent, 2000, 900, nowMs = 77)
        assertEquals("Real Title", row.title)
        assertEquals("Real Artist", row.artist)
        assertEquals("Real Album", row.albumTitle)
        assertEquals(200_000L, row.durationMs)
        assertEquals(2000L, row.sizeBytes)
        assertEquals(900L, row.fileMtimeMs)
        assertEquals(77L, row.retagAttemptedMs)
        assertEquals("Real Title", TrackMerges.Walk.merge(stored, row)!!.title)
    }

    @Test
    fun `changed file with tags takes them`() {
        val tags = TagReader.Tags(title = "New", artist = "", album = null, durationMs = 190_000)
        val row = SmbImporter.rereadRow(stored, ReadResult.Found(tags), 2000, 900, nowMs = 77)
        assertEquals("New", row.title)
        assertEquals("blank tag doesn't replace", "Real Artist", row.artist)
        assertEquals("Real Album", row.albumTitle)
        assertEquals(190_000L, row.durationMs)
    }

    @Test
    fun `unchanged path-only row re-read for its duration ends up with its real tags`() {
        // needsRescanRead picked it: no duration and never conclusively read.
        assertTrue(LibraryRepository.needsRescanRead(pathOnly, 1000, 500))
        val tags = TagReader.Tags(title = "Real Title", artist = "Real Artist", album = "Real Album", durationMs = 200_000)
        val row = SmbImporter.rereadRow(pathOnly, ReadResult.Found(tags), 1000, 500, nowMs = 77)
        val merged = TrackMerges.Walk.merge(pathOnly, row)!!
        assertEquals("Real Title", merged.title)
        assertEquals("Real Artist", merged.artist)
        assertEquals("Real Album", merged.albumTitle)
        assertEquals(200_000L, merged.durationMs)
        assertEquals(77L, merged.retagAttemptedMs)
        assertFalse(LibraryRepository.needsRescanRead(merged, 1000, 500))
    }

    @Test
    fun `unchanged row with a failed re-read stays unstamped for the next pass`() {
        val row = SmbImporter.rereadRow(pathOnly, ReadResult.Error(IOException("busy")), 1000, 500, nowMs = 77)
        val merged = TrackMerges.Walk.merge(pathOnly, row)!!
        assertNull(merged.retagAttemptedMs)
        assertTrue(LibraryRepository.needsRescanRead(merged, 1000, 500))
    }
}

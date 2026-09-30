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
import java.util.concurrent.TimeoutException

/** SRC-11: only a conclusive read stamps a row as retag-attempted; a transient failure is retried. */
class RetagStampTest {

    private val row = TrackEntity(
        id = "t", title = "01 track", durationMs = 0L, sourceType = SourceType.Smb,
        sourceId = "s", path = "/m/01 track.mp3", uri = "smb://nas/m/01%20track.mp3?sid=s",
        sizeBytes = 1, addedAtMs = 0,
    )

    @Test
    fun `found and absent stamp, error does not`() {
        val found = LibraryRepository.retagStamped(row, ReadResult.Found(TagReader.Tags(title = "X")), 42L)
        assertEquals(42L, found.retagAttemptedMs)
        assertEquals(LibraryRepository.RETAG_VERSION, found.retagVersion)

        val absent = LibraryRepository.retagStamped(row, ReadResult.Absent, 42L)
        assertEquals(42L, absent.retagAttemptedMs)

        val error = LibraryRepository.retagStamped(row, ReadResult.Error<TagReader.Tags>(TimeoutException()), 42L)
        assertNull(error.retagAttemptedMs)
        assertTrue(LibraryRepository.needsRetagAttempt(error, force = false))
    }

    @Test
    fun `error keeps a previous stamp untouched`() {
        val legacy = row.copy(retagAttemptedMs = 7L, retagVersion = 0)
        assertEquals(legacy, LibraryRepository.retagStamped(legacy, ReadResult.Error<Nothing>(TimeoutException()), 42L))
    }

    @Test
    fun `selection skips conclusive stamps, retries legacy stamps once`() {
        assertTrue(LibraryRepository.needsRetagAttempt(row, force = false))
        val legacy = row.copy(retagAttemptedMs = 7L) // stamped by the pre-classification logic
        assertTrue(LibraryRepository.needsRetagAttempt(legacy, force = false))
        val current = LibraryRepository.retagStamped(legacy, ReadResult.Absent, 42L)
        assertFalse(LibraryRepository.needsRetagAttempt(current, force = false))
        assertTrue(LibraryRepository.needsRetagAttempt(current, force = true))
    }
}

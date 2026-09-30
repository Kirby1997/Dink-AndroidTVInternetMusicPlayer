package com.example.dink_smb_player.data

import com.example.dink_smb_player.data.index.IndexDao
import com.example.dink_smb_player.data.index.SourceEntity
import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-N #8: an empty MediaStore result must not wipe the indexed local tracks. */
class LocalScanGuardTest {

    private val song = Song(
        id = "x", title = "t", artist = "a", albumId = null, albumTitle = null, durationSec = 1,
        playCount = 0, sourcePath = "/sdcard/t.mp3", bitrate = "MPEG", mediaUri = "content://m/1",
    )

    @Test
    fun `an empty automatic scan is not applied, a forced or non-empty one is`() {
        assertFalse(MediaLibrary.scanApplies(emptyList(), force = false))
        assertTrue(MediaLibrary.scanApplies(emptyList(), force = true)) // volume removed / user Scan
        assertTrue(MediaLibrary.scanApplies(listOf(song), force = false))
    }

    @Test
    fun `the local import prunes everything an empty scan omits - hence the guard`() = runBlocking {
        val local = TrackEntity(
            id = "l1", title = "t", durationMs = 1000, sourceType = SourceType.Local,
            sourceId = MediaLibrary.LOCAL_SOURCE_ID, path = "/sdcard/t.mp3", uri = "content://m/1",
            sizeBytes = 0, addedAtMs = 1,
        )
        val tracks = MutableStateFlow(listOf(local))
        val dao = IndexDao(tracks, MutableStateFlow(emptyList())) { false }
        val source = SourceEntity(id = MediaLibrary.LOCAL_SOURCE_ID, type = SourceType.Local, displayName = "Local", createdAtMs = 0)
        LibraryRepository.mergeSource(dao, source, emptyList())
        assertTrue(tracks.value.isEmpty())
    }
}

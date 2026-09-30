package com.example.dink_smb_player.data.library

import com.example.dink_smb_player.data.index.IndexDao
import com.example.dink_smb_player.data.index.SourceEntity
import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** WP-N #6: a folder-scoped import prunes only a COMPLETE walk's scope, never other folders. */
class ImportScopedPruneTest {

    private val source = SourceEntity(id = "s", type = SourceType.Smb, displayName = "S", createdAtMs = 0)

    private fun row(path: String, sourceId: String = "s") = TrackEntity(
        id = "$sourceId:$path", title = path.substringAfterLast('/'), artist = "A", albumTitle = "B", durationMs = 1000,
        sourceType = SourceType.Smb, sourceId = sourceId, path = path, uri = "smb://h$path",
        sizeBytes = 100, addedAtMs = 1, fileMtimeMs = 50,
    )

    private fun fixture(vararg rows: TrackEntity): Pair<IndexDao, MutableStateFlow<List<TrackEntity>>> {
        val tracks = MutableStateFlow(rows.toList())
        return IndexDao(tracks, MutableStateFlow(listOf(source))) { false } to tracks
    }

    private val rock1 = row("/m/Rock/1.mp3")
    private val rock2 = row("/m/Rock/2.mp3")
    private val jazz = row("/m/Jazz/1.mp3")
    private val otherShare = row("/m/Rock/9.mp3", sourceId = "t")

    @Test
    fun `incomplete walk upserts but never prunes`() = runBlocking {
        val (dao, state) = fixture(rock1, rock2, jazz, otherShare)
        val newFile = row("/m/Rock/3.mp3")
        // The walk only saw rock1 (+ a new file) — rock2 is unseen, not deleted.
        val total = LibraryRepository.importScopedIn(dao, source, listOf(rock1, newFile), listOf("/m/Rock/"), prune = false)
        assertEquals(setOf(rock1.id, rock2.id, jazz.id, otherShare.id, newFile.id), state.value.map { it.id }.toSet())
        assertEquals(4, total)
    }

    @Test
    fun `complete walk prunes only inside its scope`() = runBlocking {
        val (dao, state) = fixture(rock1, rock2, jazz, otherShare)
        val total = LibraryRepository.importScopedIn(dao, source, listOf(rock1), listOf("/m/Rock/"), prune = true)
        // rock2 vanished from the scanned folder → pruned. Jazz (another imported folder of the
        // same share) and the other share's row under a matching path are left alone.
        assertEquals(setOf(rock1.id, jazz.id, otherShare.id), state.value.map { it.id }.toSet())
        assertEquals(2, total)
    }

    @Test
    fun `removing a folder is a complete empty scan of its scope`() = runBlocking {
        val (dao, state) = fixture(rock1, rock2, jazz)
        assertEquals(1, LibraryRepository.importScopedIn(dao, source, emptyList(), listOf("/m/Rock/"), prune = true))
        assertEquals(listOf(jazz.id), state.value.map { it.id })
    }
}

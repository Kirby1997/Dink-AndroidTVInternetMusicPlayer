package com.example.dink_smb_player.data.library

import com.example.dink_smb_player.data.index.SourceEntity
import com.example.dink_smb_player.data.index.TrackEntity
import com.example.dink_smb_player.data.index.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** LIB-9: library_index.json load outcomes, backup fallback/rotation, transient ≠ corrupt. */
class LibraryStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun snap(vararg ids: String) = LibraryStore.Snapshot(
        tracks = ids.map { id ->
            TrackEntity(
                id = id, title = "T$id", durationMs = 1000, sourceType = SourceType.Smb,
                sourceId = "s", path = "/$id.mp3", uri = "smb://h/$id.mp3", sizeBytes = 1, addedAtMs = 0,
            )
        },
        sources = listOf(SourceEntity(id = "s", type = SourceType.Smb, displayName = "S", createdAtMs = 0)),
    )

    private fun store() = LibraryStore.guardedFile(tmp.root)
    private fun ids(r: GuardedJsonFile.Load<LibraryStore.Snapshot>) =
        (r as GuardedJsonFile.Load.Ok).value.tracks.map { it.id }

    @Test
    fun missingFileIsMissing() {
        assertEquals(GuardedJsonFile.Load.Missing, store().load())
    }

    @Test
    fun saveThenLoadRoundTrips() {
        assertTrue(store().save(snap("a", "b")))
        val r = store().load()
        assertEquals(listOf("a", "b"), ids(r))
        assertFalse((r as GuardedJsonFile.Load.Ok).fromBackup)
        assertFalse(File(tmp.root, "library_index.json.tmp").exists())
    }

    @Test
    fun corruptWithoutBackupIsCorruptAndPreservedNotOverwritten() {
        val main = File(tmp.root, "library_index.json")
        main.writeText("{\"tracks\":[{\"id\":")   // torn write
        val r = store().load()
        assertTrue(r is GuardedJsonFile.Load.Corrupt)
        // Quarantined under a timestamped name with the original bytes; main gone so the
        // next boot starts clean instead of failing forever.
        assertFalse(main.exists())
        val copies = SafeFiles.corruptCopies(main)
        assertEquals(1, copies.size)
        assertEquals("{\"tracks\":[{\"id\":", copies[0].readText())
        assertEquals(GuardedJsonFile.Load.Missing, store().load())
    }

    @Test
    fun backupRotatesOncePerProcessFromKnownGoodFile() {
        store().save(snap("a"))                   // fresh install: nothing to rotate
        val s = store()                           // "next process"
        assertEquals(listOf("a"), ids(s.load()))
        assertTrue(s.save(snap("a", "b")))        // first save rotates the loaded file
        assertTrue(s.save(snap("a", "b", "c")))   // later saves don't
        assertEquals(listOf("a"), ids(parseFile(s.backup)))
        assertEquals("library_index.bak.json", s.backup.name)
    }

    @Test
    fun corruptMainFallsBackToBackup() {
        store().save(snap("a"))
        val s = store(); s.load(); s.save(snap("a", "b"))   // bak = [a], main = [a,b]
        File(tmp.root, "library_index.json").writeText("garbage")
        val r = store().load()
        assertEquals(listOf("a"), ids(r))
        assertTrue((r as GuardedJsonFile.Load.Ok).fromBackup)
        assertEquals(1, SafeFiles.corruptCopies(File(tmp.root, "library_index.json")).size)
    }

    @Test
    fun corruptMainNeverRotatedIntoBackup() {
        store().save(snap("a"))
        val s = store(); s.load(); s.save(snap("a", "b"))   // bak = [a]
        File(tmp.root, "library_index.json").writeText("garbage")
        val t = store()
        assertEquals(listOf("a"), ids(t.load()))            // from backup
        assertTrue(t.save(snap("a", "z")))                  // must not copy garbage over bak
        assertEquals(listOf("a"), ids(parseFile(t.backup)))
        assertEquals(listOf("a", "z"), ids(store().load()))
    }

    @Test
    fun missingMainButBackupPresentUsesBackup() {
        store().save(snap("a"))
        val s = store(); s.load(); s.save(snap("a", "b"))
        File(tmp.root, "library_index.json").delete()
        assertEquals(listOf("a"), ids(store().load()))
    }

    @Test
    fun ioExceptionAndOomAreTransientAndTouchNothing() {
        store().save(snap("a"))
        val main = File(tmp.root, "library_index.json")
        val before = main.readText()
        for (err in listOf<Throwable>(IOException("EIO"), OutOfMemoryError("heap"))) {
            val flaky = GuardedJsonFile<LibraryStore.Snapshot>(main, "t", { throw err }, { _, _ -> })
            val r = flaky.load()
            assertTrue("$err", r is GuardedJsonFile.Load.Transient)
            assertTrue(main.exists())
            assertEquals(before, main.readText())
            assertTrue(SafeFiles.corruptCopies(main).isEmpty())
        }
    }

    @Test
    fun transientIsRetriedThenSucceeds() = runBlocking {
        store().save(snap("a"))
        val main = File(tmp.root, "library_index.json")
        var calls = 0
        val real = store()
        val flaky = GuardedJsonFile(main, "t", { input ->
            if (++calls < 3) throw IOException("busy")
            kotlinx.serialization.json.Json.decodeFromString<LibraryStore.Snapshot>(input.reader().readText())
        }, { _: LibraryStore.Snapshot, _ -> })
        assertEquals(listOf("a"), ids(flaky.loadWithRetry(attempts = 3, backoffMs = 0)))
        assertEquals(3, calls)
        // Exhausted retries stay Transient (never Corrupt).
        val dead = GuardedJsonFile<LibraryStore.Snapshot>(main, "t", { throw IOException("gone") }, { _, _ -> })
        assertTrue(dead.loadWithRetry(attempts = 2, backoffMs = 0) is GuardedJsonFile.Load.Transient)
        assertEquals(listOf("a"), ids(real.load()))
    }

    @Test
    fun corruptCopiesAreCapped() {
        val main = File(tmp.root, "library_index.json")
        repeat(5) { i ->
            main.writeText("bad$i")
            SafeFiles.quarantine(main, nowMs = 1000L + i)
        }
        val copies = SafeFiles.corruptCopies(main)
        assertEquals(SafeFiles.KEEP_CORRUPT, copies.size)
        assertEquals("bad4", copies.first().readText())   // newest kept
    }

    @Test
    fun loadResultMapping() {
        assertEquals(LibraryStore.LoadResult.Missing, LibraryStore.toLoadResult(GuardedJsonFile.Load.Missing))
        val e = IOException()
        assertEquals(LibraryStore.LoadResult.Transient(e), LibraryStore.toLoadResult(GuardedJsonFile.Load.Transient(e)))
        assertEquals(LibraryStore.LoadResult.Corrupt(e), LibraryStore.toLoadResult(GuardedJsonFile.Load.Corrupt(e)))
        assertEquals(
            LibraryStore.LoadResult.Ok(snap("a"), fromBackup = true),
            LibraryStore.toLoadResult(GuardedJsonFile.Load.Ok(snap("a"), fromBackup = true)),
        )
    }

    private fun parseFile(f: File): GuardedJsonFile.Load<LibraryStore.Snapshot> =
        GuardedJsonFile(f, "t", { kotlinx.serialization.json.Json.decodeFromString<LibraryStore.Snapshot>(it.reader().readText()) }, { _, _ -> }).load()
}

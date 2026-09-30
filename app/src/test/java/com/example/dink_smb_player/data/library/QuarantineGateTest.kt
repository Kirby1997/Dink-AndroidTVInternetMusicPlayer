package com.example.dink_smb_player.data.library

import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Gate follow-up: a corrupt index that was quarantined aside reopens persistence; one still in
 *  place keeps it closed. Plus LIB-12's .tmp cleanup on a failed write. */
class QuarantineGateTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = LibraryStore.guardedFile(tmp.root)
    private val main get() = File(tmp.root, "library_index.json")

    private fun gateFor(r: GuardedJsonFile.Load<LibraryStore.Snapshot>): PersistGate {
        val c = LibraryStore.toLoadResult(r) as LibraryStore.LoadResult.Corrupt
        return PersistGate().apply { onCorrupt(quarantined = c.quarantined) }
    }

    @Test
    fun quarantinedCorruptIndexReopensGate() {
        main.writeText("{\"tracks\":[")
        val r = store().load()
        assertTrue(r is GuardedJsonFile.Load.Corrupt)
        assertTrue((r as GuardedJsonFile.Load.Corrupt).quarantined)
        assertFalse(main.exists())
        assertTrue(gateFor(r).canPersist)
    }

    @Test
    fun corruptMainAndBackupBothQuarantinedReopensGate() {
        main.writeText("garbage")
        File(tmp.root, "library_index.bak.json").writeText("garbage too")
        val r = store().load() as GuardedJsonFile.Load.Corrupt
        assertTrue(r.quarantined)
        assertTrue(gateFor(r).canPersist)
        assertEquals(1, SafeFiles.corruptCopies(main).size)
    }

    @Test
    fun corruptIndexLeftInPlaceKeepsGateClosed() {
        main.writeText("garbage")
        // Quarantine can't move or copy the file out of a read-only directory.
        assumeTrue(tmp.root.setWritable(false))
        try {
            assumeTrue("running as root ignores permissions", !File(tmp.root, "probe").let { runCatching { it.createNewFile() }.getOrDefault(false) })
            val r = store().load() as GuardedJsonFile.Load.Corrupt
            assertFalse(r.quarantined)
            assertTrue(main.exists())
            val gate = gateFor(r)
            assertFalse(gate.canPersist)
            assertFalse(gate.retryable)
        } finally {
            tmp.root.setWritable(true)
        }
    }

    @Test
    fun gateOnCorruptFlag() {
        assertTrue(PersistGate().apply { onCorrupt(quarantined = true) }.canPersist)
        assertFalse(PersistGate().apply { onCorrupt(quarantined = false) }.canPersist)
        assertFalse(PersistGate().apply { onCorrupt() }.canPersist)
    }

    @Test
    fun failedWriteDeletesTempAndKeepsOldFile() {
        val s = store()
        val snap = LibraryStore.Snapshot(
            tracks = listOf(
                TrackEntity(
                    id = "a", title = "A", durationMs = 1, sourceType = SourceType.Smb, sourceId = "s",
                    path = "/a.mp3", uri = "smb://h/a.mp3", sizeBytes = 1, addedAtMs = 0,
                ),
            ),
        )
        assertTrue(s.save(snap))
        val before = main.readText()
        val failing = GuardedJsonFile<LibraryStore.Snapshot>(main, "t", { error("unused") }, { _, out ->
            out.write("partial".toByteArray())
            throw IOException("disk full")
        })
        assertTrue(failing.saveResult(snap).isFailure)
        assertFalse(File(tmp.root, "library_index.json.tmp").exists())
        assertEquals(before, main.readText())
    }
}

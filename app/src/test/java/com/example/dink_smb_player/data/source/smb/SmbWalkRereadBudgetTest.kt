package com.example.dink_smb_player.data.source.smb

import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.source.TagReadGate
import com.example.dink_smb_player.data.source.smb.SmbImporter.FileAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * The walk's read budget: new / changed files are always read, unchanged rows that still lack
 * a duration only get a small budgeted batch per pass — a cold monitor pass once re-read ~1,250
 * of them, 22 at a time, beside its own listings, and the listings timed out.
 */
class SmbWalkRereadBudgetTest {

    private val stamped = TrackEntity(
        id = "a", title = "Real Title", artist = "Real Artist", albumTitle = "Real Album", durationMs = 200_000,
        sourceType = SourceType.Smb, sourceId = "s", path = "/smb/share/Artist/Album/01 file.mp3",
        uri = "smb://h/share/Artist/Album/01%20file.mp3", sizeBytes = 1000, addedAtMs = 10, fileMtimeMs = 500,
        retagAttemptedMs = 42, retagVersion = LibraryRepository.RETAG_VERSION,
    )

    /** Unchanged on the NAS, but never got a duration and was never conclusively read. */
    private val noDuration = stamped.copy(durationMs = 0, retagAttemptedMs = null, retagVersion = 0)

    @Test
    fun `new and changed files are read now, whatever the backlog`() {
        assertEquals(FileAction.READ, SmbImporter.fileAction(null, 1000, 500))
        assertEquals(FileAction.READ, SmbImporter.fileAction(stamped, 2000, 500)) // size differs
        assertEquals(FileAction.READ, SmbImporter.fileAction(stamped, 1000, 900)) // mtime differs
        // Changed AND missing a duration is still "changed": never pushed into the budget.
        assertEquals(FileAction.READ, SmbImporter.fileAction(noDuration, 2000, 900))
    }

    @Test
    fun `an unchanged row without a duration is only a budget candidate`() {
        assertEquals(FileAction.REREAD, SmbImporter.fileAction(noDuration, 1000, 500))
        // Indexed before mtime was recorded: the listing's mtime is not a "change".
        assertEquals(FileAction.REREAD, SmbImporter.fileAction(noDuration.copy(fileMtimeMs = null), 1000, 500))
        // A stamp from an older retag version doesn't count as read.
        assertEquals(
            FileAction.REREAD,
            SmbImporter.fileAction(noDuration.copy(retagAttemptedMs = 7, retagVersion = LibraryRepository.RETAG_VERSION - 1), 1000, 500),
        )
    }

    @Test
    fun `unchanged rows that are complete or conclusively read are left alone`() {
        assertEquals(FileAction.KEEP, SmbImporter.fileAction(stamped, 1000, 500))
        // No duration, but a conclusive read is recorded: the file just doesn't have one.
        assertEquals(FileAction.KEEP, SmbImporter.fileAction(stamped.copy(durationMs = 0), 1000, 500))
    }

    @Test
    fun `a pass spends only its budget on the backlog while every changed file is read`() {
        // 300 files changed on the NAS + 1,229 unchanged rows still missing a duration.
        val changed = (0 until 300).map { stamped.copy(id = "c$it") }
        val backlog = (0 until 1229).map { noDuration.copy(id = "b$it") }
        val readNow = changed.count { SmbImporter.fileAction(it, 9999, 500) == FileAction.READ }
        val candidates = (changed.map { it to 9999L } + backlog.map { it to 1000L })
            .filter { (row, size) -> SmbImporter.fileAction(row, size, 500) == FileAction.REREAD }
            .map { it.first }
        assertEquals(300, readNow)
        assertEquals(1229, candidates.size)
        val picked = SmbImporter.pickRereads(candidates, SmbImporter.REREAD_BUDGET, Random(1))
        assertEquals(SmbImporter.REREAD_BUDGET, picked.size)
        assertEquals(picked.size, picked.map { it.id }.toSet().size)
        assertTrue(picked.all { it.id.startsWith("b") })
    }

    @Test
    fun `a backlog inside the budget is read whole, a bigger one is sampled differently each pass`() {
        val few = (0 until 10).toList()
        assertEquals(few, SmbImporter.pickRereads(few, 60, Random(1)))
        val many = (0 until 1000).toList()
        val a = SmbImporter.pickRereads(many, 60, Random(1))
        val b = SmbImporter.pickRereads(many, 60, Random(2))
        assertEquals(60, a.size)
        // Not "the first 60 the walk met": rows whose read keeps failing can't eat every pass.
        assertNotEquals((0 until 60).toList(), a)
        assertNotEquals(a.toSet(), b.toSet())
    }

    @Test
    fun `re-reads stop at the budget and never exceed their concurrency`() = runBlocking(Dispatchers.Default) {
        val inFlight = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        val read = Collections.synchronizedList(ArrayList<Int>())
        val run = SmbImporter.runRereads(
            candidates = (0 until 500).toList(),
            budget = SmbImporter.REREAD_BUDGET,
            concurrency = SmbImporter.REREAD_CONCURRENCY,
            maxMs = 60_000,
            random = Random(3),
        ) { c ->
            maxSeen.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            delay(3)
            inFlight.decrementAndGet()
            read += c
            c % 5 != 0 // every fifth read fails transiently
        }
        assertEquals(SmbImporter.REREAD_BUDGET, run.done)
        assertEquals(SmbImporter.REREAD_BUDGET, read.size)
        assertEquals(read.size, read.toSet().size)
        assertEquals(read.count { it % 5 == 0 }, run.errors)
        assertFalse(run.capped)
        assertTrue("max ${maxSeen.get()}", maxSeen.get() <= SmbImporter.REREAD_CONCURRENCY)
        assertTrue("batch actually ran in parallel", maxSeen.get() > 1)
    }

    @Test
    fun `the time cap stops new re-reads and leaves the rest untouched`() = runBlocking {
        val clock = AtomicLong(0)
        val run = SmbImporter.runRereads(
            candidates = (0 until 60).toList(),
            budget = 60,
            concurrency = 1,
            maxMs = 90_000,
            nowMs = clock::get,
        ) {
            clock.addAndGet(20_000) // every read burns its whole 20 s probe cap
            true
        }
        assertEquals(5, run.done) // started at 0, 20, 40, 60, 80 s — nothing at 100 s
        assertTrue(run.capped)
        assertEquals(0, run.errors)
    }

    @Test
    fun `nothing to re-read is a no-op`() = runBlocking {
        val run = SmbImporter.runRereads(emptyList<Int>(), 60, 4, 1_000) { error("no read expected") }
        assertEquals(SmbImporter.RereadRun(0, 0, false), run)
    }

    @Test
    fun `a walk's reads sit well inside the process-wide gate`() {
        // Even with the retag's gate free, a walk never runs more than this many file reads.
        assertEquals(6, SmbImporter.WALK_READ_CONCURRENCY)
        assertTrue(SmbImporter.REREAD_CONCURRENCY <= SmbImporter.WALK_READ_CONCURRENCY)
        assertTrue(SmbImporter.WALK_READ_CONCURRENCY + SmbImporter.REREAD_CONCURRENCY < TagReadGate.PERMITS)
    }

    @Test
    fun `the summary line carries the numbers a device rerun is compared on`() {
        val stats = SmbImporter.WalkStats(startMs = 1_000)
        repeat(2513) { stats.lists.incrementAndGet() }
        stats.listErrors.set(2)
        stats.listTimeouts.set(1)
        stats.reads.set(3)
        stats.rereadCandidates = 1229
        stats.rereads = 60
        stats.rereadErrors = 4
        assertEquals(
            "took=41.2s lists=2513 listErrors=2 timeouts=1 evictions=0 reads=3 readErrors=0 " +
                "rereads=60/60 (backlog=1229 errors=4) complete=true breaker=false",
            stats.summary(complete = true, breakerTripped = false, nowMs = 42_250),
        )
        stats.rereadCapped = true
        assertTrue(stats.summary(false, true, 42_250).contains("errors=4 time-capped) complete=false breaker=true"))
    }
}

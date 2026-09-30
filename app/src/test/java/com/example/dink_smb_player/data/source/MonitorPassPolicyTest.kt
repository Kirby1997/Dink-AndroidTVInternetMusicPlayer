package com.example.dink_smb_player.data.source

import com.example.dink_smb_player.data.source.SourceOutcome.CANCELLED
import com.example.dink_smb_player.data.source.SourceOutcome.COMPLETE
import com.example.dink_smb_player.data.source.SourceOutcome.FAILED
import com.example.dink_smb_player.data.source.SourceOutcome.INCOMPLETE
import com.example.dink_smb_player.data.source.SourceOutcome.ROOT_ISSUE
import com.example.dink_smb_player.data.source.SourceOutcome.SKIPPED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SRC-9: a monitor pass is stamped done only when every walk completed; a failed or
 *  partial walk retries (bounded) instead of suppressing the next catch-up. */
class MonitorPassPolicyTest {

    private fun decide(vararg o: SourceOutcome, attempt: Int = 0) =
        MonitorPassPolicy.decide(o.toList(), attempt)

    @Test
    fun `all complete stamps, no retry`() {
        assertEquals(MonitorPassPolicy.Decision(stamp = true, retry = false), decide(COMPLETE, SKIPPED))
        assertEquals(MonitorPassPolicy.Decision(stamp = true, retry = false), decide())
    }

    @Test
    fun `connection failure or partial walk retries without stamping`() {
        assertEquals(MonitorPassPolicy.Decision(stamp = false, retry = true), decide(COMPLETE, FAILED))
        assertEquals(MonitorPassPolicy.Decision(stamp = false, retry = true), decide(INCOMPLETE))
    }

    @Test
    fun `retries are bounded`() {
        assertTrue(decide(FAILED, attempt = MonitorPassPolicy.MAX_RETRIES - 1).retry)
        assertFalse(decide(FAILED, attempt = MonitorPassPolicy.MAX_RETRIES).retry)
        assertFalse(decide(FAILED, attempt = MonitorPassPolicy.MAX_RETRIES).stamp)
    }

    @Test
    fun `cancelled by the user neither stamps nor retries`() {
        assertEquals(MonitorPassPolicy.Decision(stamp = false, retry = false), decide(COMPLETE, CANCELLED))
    }

    @Test
    fun `freshness window`() {
        val hour = 3_600_000L
        assertFalse(MonitorPassPolicy.isFresh(0L, 10 * hour, hour)) // never ran
        assertTrue(MonitorPassPolicy.isFresh(10 * hour, 10 * hour + hour - 1, hour))
        assertFalse(MonitorPassPolicy.isFresh(10 * hour, 11 * hour, hour))
        assertFalse(MonitorPassPolicy.isFresh(10 * hour, 9 * hour, hour)) // clock moved back
    }

    // Review #3: a monitored folder that's gone is settled — stamp, no retry storm.
    @Test
    fun `a settled root issue stamps and does not retry`() {
        assertEquals(MonitorPassPolicy.Decision(stamp = true, retry = false), decide(COMPLETE, ROOT_ISSUE))
        assertEquals(MonitorPassPolicy.Decision(stamp = false, retry = true), decide(ROOT_ISSUE, INCOMPLETE))
    }

    @Test
    fun `share stats refresh only after a clean saved pass`() {
        assertEquals(true, MonitorPassPolicy.refreshesShareStats(allRootsComplete = true, noRootIssues = true, saved = true))
        assertEquals(false, MonitorPassPolicy.refreshesShareStats(allRootsComplete = false, noRootIssues = true, saved = true))
        assertEquals(false, MonitorPassPolicy.refreshesShareStats(allRootsComplete = true, noRootIssues = false, saved = true))
        assertEquals(false, MonitorPassPolicy.refreshesShareStats(allRootsComplete = true, noRootIssues = true, saved = false))
    }

    @Test
    fun `smb outcome`() {
        assertEquals(COMPLETE, MonitorPassPolicy.smbOutcome(anyIncomplete = false, anyRootIssue = false))
        assertEquals(ROOT_ISSUE, MonitorPassPolicy.smbOutcome(anyIncomplete = false, anyRootIssue = true))
        assertEquals(INCOMPLETE, MonitorPassPolicy.smbOutcome(anyIncomplete = true, anyRootIssue = true))
    }

    @Test
    fun `prune scope keeps failed roots out of the prune`() {
        val paths = listOf("Music", "Music\\Rock", "Podcasts", "Old")
        val bad = setOf("Music", "Old")
        fun ok(p: String) = bad.none { SourceLocks.pathsOverlap(it, p) }
        assertEquals(listOf("Podcasts") to true, MonitorPassPolicy.pruneScope(paths, ::ok))
        assertEquals(paths to true, MonitorPassPolicy.pruneScope(paths) { true })
        // Nothing listed in full → upsert-only over every path (never an empty scope).
        assertEquals(paths to false, MonitorPassPolicy.pruneScope(paths) { false })
    }
}

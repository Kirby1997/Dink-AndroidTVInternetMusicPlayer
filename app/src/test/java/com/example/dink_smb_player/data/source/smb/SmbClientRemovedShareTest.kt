package com.example.dink_smb_player.data.source.smb

import com.example.dink_smb_player.data.source.SourceLocks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Review #4 / #5: a deleted share can't get a new SMB session, and its release is fast on a
 *  dead NAS. */
class SmbClientRemovedShareTest {

    @Test
    fun `a removed share is refused before any connect`() = runBlocking {
        val id = "smb-rm-" + System.nanoTime()
        SmbClient.checkNotRemoved(id) // live share: fine
        SourceLocks.remove(id) {}
        try {
            SmbClient.checkNotRemoved(id)
            fail("removed share accepted")
        } catch (expected: IOException) {
        }
        // The real entry points refuse it too, without touching the network (host is bogus).
        try {
            SmbClient.share(id, "256.0.0.1", 445, "music", null)
            fail("removed share connected")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("removed"))
        }
        try {
            SmbClient.lease(id, "256.0.0.1", 445, "music", null, playback = true)
            fail("removed share leased")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("removed"))
        }
    }

    @Test
    fun `release forces a dead link and never probes a closed socket`() {
        var probed = false
        assertTrue(SmbClient.releaseForce(connected = false) { probed = true; true })
        assertFalse(probed)
        assertTrue(SmbClient.releaseForce(connected = true) { false }) // no ECHO reply
        assertFalse(SmbClient.releaseForce(connected = true) { true }) // alive → graceful
    }
}

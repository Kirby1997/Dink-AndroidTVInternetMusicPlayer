package com.example.dink_smb_player.data.source.smb

import com.example.dink_smb_player.data.model.ConnectionStatus
import com.example.dink_smb_player.data.model.SmbProtocol
import com.example.dink_smb_player.data.model.SmbShare
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import com.example.dink_smb_player.data.prefs.SmbCreds
import com.example.dink_smb_player.data.source.SourceLocks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** SRC-16: update() swaps the whole snapshot, so a concurrent reader never sees it empty. */
class SmbConnectionRegistryTest {

    private fun share(id: String) = SmbShare(
        id = id, name = "S$id", host = "nas", port = 445, shareName = "music",
        mountPath = "", user = "u", protocol = SmbProtocol.Auto,
        status = ConnectionStatus.Idle, trackCount = 0, sizeBytes = 0, lastSyncMs = null, signal = 0f,
    )

    @After
    fun reset() {
        SmbConnectionRegistry.update(emptyList())
        SmbConnectionRegistry.installCredLookup { null }
    }

    private val creds = SmbCreds(user = "u", password = "p", domain = null)

    @Test
    fun updateReplacesAndAddMerges() {
        SmbConnectionRegistry.update(listOf(share("a"), share("b")))
        SmbConnectionRegistry.add(share("c"))
        assertNotNull(SmbConnectionRegistry.share("a"))
        assertNotNull(SmbConnectionRegistry.share("c"))
        SmbConnectionRegistry.update(listOf(share("b")))
        assertNull(SmbConnectionRegistry.share("a"))
        assertNull(SmbConnectionRegistry.share("c"))
        assertEquals("Sb", SmbConnectionRegistry.share("b")?.name)
    }

    @Test
    fun readerNeverSeesEmptyDuringRepeatedUpdates() {
        val shares = listOf(share("a"), share("b"))
        SmbConnectionRegistry.update(shares)
        val stop = AtomicBoolean(false)
        val misses = AtomicInteger(0)
        val reader = Thread {
            while (!stop.get()) if (SmbConnectionRegistry.share("a") == null) misses.incrementAndGet()
        }
        reader.start()
        repeat(20_000) { SmbConnectionRegistry.update(shares) }
        stop.set(true)
        reader.join()
        assertEquals(0, misses.get())
    }

    // Review #1: a cold process (MonitorWorker, media-button PlayerService) hydrates from disk.
    @Test
    fun hydrateRegistersSavedSharesAndCreds() = runBlocking {
        SmbConnectionRegistry.hydrateFrom(
            loadShares = { listOf(share("a"), share("b")) },
            openCreds = { { sid -> if (sid == "a") creds else null } },
        )
        assertEquals("Sa", SmbConnectionRegistry.share("a")?.name)
        assertNotNull(SmbConnectionRegistry.share("b"))
        assertEquals(creds, SmbConnectionRegistry.creds("a"))
        assertNull(SmbConnectionRegistry.creds("b"))
    }

    @Test
    fun hydrateMergesSoAWarmWizardAddSurvives() = runBlocking {
        SmbConnectionRegistry.add(share("new"))
        SmbConnectionRegistry.hydrateFrom(loadShares = { listOf(share("a")) }, openCreds = { { null } })
        assertNotNull(SmbConnectionRegistry.share("new"))
        assertNotNull(SmbConnectionRegistry.share("a"))
    }

    @Test
    fun hydrateSurvivesAFailedPrefsRead() = runBlocking {
        SmbConnectionRegistry.update(listOf(share("a")))
        SmbConnectionRegistry.hydrateFrom(
            loadShares = { throw java.io.IOException("datastore") },
            openCreds = { { creds } },
        )
        assertNotNull(SmbConnectionRegistry.share("a"))
        assertEquals(creds, SmbConnectionRegistry.creds("a"))
    }

    @Test
    fun hydratePropagatesCancellationAndSkipsTheCredStore() = runBlocking {
        var credStoreOpened = false
        try {
            SmbConnectionRegistry.hydrateFrom(
                loadShares = { throw CancellationException("service destroyed") },
                openCreds = { credStoreOpened = true; { creds } },
            )
            fail("cancellation swallowed")
        } catch (expected: CancellationException) {
        }
        assertTrue(!credStoreOpened)
        assertNull(SmbConnectionRegistry.share("a"))
    }

    // Review #5: a share being deleted is never (re-)registered.
    @Test
    fun removedShareIsNeverRegistered() = runBlocking {
        val id = "smb-gone-" + System.nanoTime()
        SourceLocks.remove(id) {}
        SmbConnectionRegistry.update(listOf(share(id), share("a")))
        assertNull(SmbConnectionRegistry.share(id))
        SmbConnectionRegistry.add(share(id))
        assertNull(SmbConnectionRegistry.share(id))
        SmbConnectionRegistry.hydrateFrom(loadShares = { listOf(share(id)) }, openCreds = { { null } })
        assertNull(SmbConnectionRegistry.share(id))
        assertNotNull(SmbConnectionRegistry.share("a"))
        SmbConnectionRegistry.remove("a")
        assertNull(SmbConnectionRegistry.share("a"))
    }
}

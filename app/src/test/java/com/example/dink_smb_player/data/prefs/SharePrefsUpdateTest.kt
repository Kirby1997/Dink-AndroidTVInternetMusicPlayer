package com.example.dink_smb_player.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.dink_smb_player.data.model.ConnectionStatus
import com.example.dink_smb_player.data.model.SmbProtocol
import com.example.dink_smb_player.data.model.SmbShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SRC-5: write-backs after async work go through [SharePrefs.updateShare], which applies
 * to the CURRENT stored share and no-ops once the share is deleted — so an import that
 * finishes after "Delete share" can't resurrect it, and a stale copy can't clobber edits.
 */
class SharePrefsUpdateTest {

    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var prefs: SharePrefs

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("shareprefs").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        prefs = SharePrefs(
            PreferenceDataStoreFactory.create(scope = scope) { File(dir, "sources.preferences_pb") },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun share(id: String, importPaths: List<String> = emptyList()) = SmbShare(
        id = id, name = id, host = "nas", port = 445, shareName = "music", mountPath = "/smb/$id",
        user = "", protocol = SmbProtocol.Auto, status = ConnectionStatus.Idle, trackCount = 0,
        sizeBytes = 0L, lastSyncMs = null, signal = 1f, importPaths = importPaths,
    )

    @Test
    fun `update of a deleted share is a no-op and does not resurrect it`() = runBlocking {
        prefs.saveShare(share("smb-a"))
        prefs.deleteShare("smb-a")

        val result = prefs.updateShare("smb-a") { it.copy(trackCount = 42, status = ConnectionStatus.Connected) }

        assertNull(result)
        assertTrue(prefs.shares.first().isEmpty())
    }

    @Test
    fun `update applies to the current stored value, not a stale copy`() = runBlocking {
        val stale = share("smb-a")
        prefs.saveShare(stale)
        // Another writer adds a folder after `stale` was taken.
        prefs.updateShare("smb-a") { it.copy(importPaths = it.importPaths + "Rock") }

        // Completion stamp from an import that started with `stale`.
        val updated = prefs.updateShare(stale.id) { it.copy(trackCount = 7) }

        assertEquals(listOf("Rock"), updated?.importPaths)
        val stored = prefs.shares.first().single()
        assertEquals(7, stored.trackCount)
        assertEquals(listOf("Rock"), stored.importPaths)
    }

    @Test
    fun `update leaves other shares alone and keeps the id`() = runBlocking {
        prefs.saveShare(share("smb-a"))
        prefs.saveShare(share("smb-b", importPaths = listOf("Jazz")))

        prefs.updateShare("smb-a") { it.copy(id = "smb-hijack", trackCount = 3) }

        val byId = prefs.shares.first().associateBy { it.id }
        assertEquals(setOf("smb-a", "smb-b"), byId.keys)
        assertEquals(3, byId.getValue("smb-a").trackCount)
        assertEquals(listOf("Jazz"), byId.getValue("smb-b").importPaths)
    }
}

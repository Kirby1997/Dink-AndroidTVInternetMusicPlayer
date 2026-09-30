package com.example.dink_smb_player.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The removed cloud feature's provider list is deleted from the prefs; SMB shares stay. */
class SharePrefsLegacyCloudTest {

    private val legacyKey = stringSetPreferencesKey("cloud_providers_json")
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var prefs: SharePrefs

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("shareprefs-cloud").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "sources.preferences_pb") }
        prefs = SharePrefs(store)
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun share(id: String) = SmbShare(
        id = id, name = id, host = "nas", port = 445, shareName = "music", mountPath = "/smb/$id",
        user = "", protocol = SmbProtocol.Auto, status = ConnectionStatus.Idle, trackCount = 0,
        sizeBytes = 0L, lastSyncMs = null, signal = 1f,
    )

    @Test
    fun `purge removes the stored provider list and keeps the shares`() = runBlocking {
        prefs.saveShare(share("smb-a"))
        store.edit { it[legacyKey] = setOf("""{"id":"cloud-gdrive","name":"Google Drive"}""") }

        prefs.purgeLegacyCloud()

        assertNull(store.data.first()[legacyKey])
        assertEquals(listOf("smb-a"), prefs.shares.first().map { it.id })
    }

    @Test
    fun `purge with nothing stored changes nothing`() = runBlocking {
        prefs.saveShare(share("smb-a"))
        val before = store.data.first()

        prefs.purgeLegacyCloud()

        assertEquals(before, store.data.first())
    }
}

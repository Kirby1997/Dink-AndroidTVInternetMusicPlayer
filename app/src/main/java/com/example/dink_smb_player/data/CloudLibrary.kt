package com.example.dink_smb_player.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.model.CloudFolderRef
import com.example.dink_smb_player.data.model.CloudProvider
import com.example.dink_smb_player.data.model.ConnectionStatus
import com.example.dink_smb_player.data.prefs.CloudToken
import com.example.dink_smb_player.data.prefs.EncryptedShareStore
import com.example.dink_smb_player.data.prefs.SharePrefs
import com.example.dink_smb_player.data.source.MonitorWorker
import com.example.dink_smb_player.data.source.cloud.CloudConnectionRegistry
import com.example.dink_smb_player.data.source.cloud.CloudImporter
import com.example.dink_smb_player.data.source.cloud.CloudProviderSpec
import com.example.dink_smb_player.data.source.cloud.GoogleDriveClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide cloud-source state + OAuth device-flow driver. Mirror of
 * [SharesLibrary] for cloud providers. Holds per-provider indexing/error state for
 * the cloud screens and runs connect → token (then folder-scoped import) on an
 * app-lifetime scope so it survives the user leaving the modal.
 *
 * Streaming, not downloading: connecting just stores credentials; importing a folder
 * only INDEXES its audio. Playback streams via
 * [com.example.dink_smb_player.data.source.cloud.CloudDataSource].
 */
object CloudLibrary {

    val importingProviders = mutableStateMapOf<String, Boolean>()
    val errorsByProvider = mutableStateMapOf<String, String>()
    /** Tracks the provider gained from its most recent folder import (recursive). */
    val lastImportedCount = mutableStateMapOf<String, Int>()

    /** Provider whose folder browser is open. Set by CloudScreen card / connect Done. */
    var activeBrowseProviderId: String? by mutableStateOf(null)

    sealed interface ConnectState {
        object Idle : ConnectState
        object NotConfigured : ConnectState
        object Requesting : ConnectState
        data class AwaitingAuth(
            val spec: CloudProviderSpec,
            val userCode: String,
            val verificationUrl: String,
            val expiresAtMs: Long,
        ) : ConnectState
        /** Device flow succeeded — provider connected, ready to browse + pick folders. */
        data class Done(val providerId: String) : ConnectState
        data class Failed(val message: String) : ConnectState
    }

    var connectState: ConnectState by mutableStateOf(ConnectState.Idle)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectJob: Job? = null

    // ---------- connect (device flow) ----------

    fun connect(context: Context, spec: CloudProviderSpec) {
        if (spec.id != CloudProviderSpec.GOOGLE_DRIVE_ID) {
            connectState = ConnectState.Failed("${spec.name} isn't supported yet.")
            return
        }
        if (!GoogleDriveClient.isConfigured()) {
            connectState = ConnectState.NotConfigured
            return
        }
        connectJob?.cancel()
        val appContext = context.applicationContext
        connectState = ConnectState.Requesting
        connectJob = scope.launch { runConnect(appContext, spec) }
    }

    fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
        connectState = ConnectState.Idle
    }

    fun dismissConnect() {
        when (connectState) {
            is ConnectState.Done, is ConnectState.Failed, is ConnectState.NotConfigured ->
                connectState = ConnectState.Idle
            else -> {}
        }
    }

    private suspend fun runConnect(context: Context, spec: CloudProviderSpec) {
        val device = GoogleDriveClient.requestDeviceCode().getOrElse {
            connectState = ConnectState.Failed(it.message ?: "Couldn't start sign-in")
            return
        }
        val deadline = System.currentTimeMillis() + device.expiresInSec * 1000L
        connectState = ConnectState.AwaitingAuth(spec, device.userCode, device.verificationUrl, deadline)

        var intervalSec = device.intervalSec
        while (System.currentTimeMillis() < deadline) {
            delay(intervalSec * 1000L)
            when (val r = GoogleDriveClient.pollOnce(device.deviceCode)) {
                is GoogleDriveClient.TokenResult.Pending -> {}
                is GoogleDriveClient.TokenResult.SlowDown -> intervalSec += 5
                is GoogleDriveClient.TokenResult.Denied -> {
                    connectState = ConnectState.Failed("Sign-in was denied."); return
                }
                is GoogleDriveClient.TokenResult.Expired -> {
                    connectState = ConnectState.Failed("The code expired — try again."); return
                }
                is GoogleDriveClient.TokenResult.Error -> {
                    connectState = ConnectState.Failed(r.message); return
                }
                is GoogleDriveClient.TokenResult.Success -> { finishConnect(context, spec, r); return }
            }
        }
        connectState = ConnectState.Failed("The code expired — try again.")
    }

    private suspend fun finishConnect(
        context: Context,
        spec: CloudProviderSpec,
        token: GoogleDriveClient.TokenResult.Success,
    ) {
        val store = EncryptedShareStore(context)
        val prefs = SharePrefs(context)
        val expiresAtMs = System.currentTimeMillis() + token.expiresInSec * 1000L
        store.putCloudToken(spec.id, CloudToken(token.accessToken, token.refreshToken, expiresAtMs))

        val account = GoogleDriveClient.about(token.accessToken).getOrNull()
        val provider = CloudProvider(
            id = spec.id,
            name = spec.name,
            auth = spec.auth,
            status = ConnectionStatus.Connected,
            account = account?.email ?: "Connected",
            trackCount = 0,
            cacheSize = "streaming",
            lastSyncMs = System.currentTimeMillis(),
            glyph = spec.glyph,
        )
        prefs.saveProvider(provider)
        CloudConnectionRegistry.add(provider)
        // No whole-account index — the user picks folders in the browser (like SMB).
        activeBrowseProviderId = spec.id
        connectState = ConnectState.Done(spec.id)
    }

    // ---------- folder import / monitor (mirror of SharesLibrary) ----------

    /** Add [ref] to the provider's import folders, persist, then index ONLY that
     *  folder + subfolders (other imported folders untouched). */
    fun importFolder(context: Context, provider: CloudProvider, ref: CloudFolderRef) {
        val appContext = context.applicationContext
        // Importing also monitors — new files in the folder get auto-indexed later.
        val updated = provider.copy(
            importFolders = (provider.importFolders + ref).distinctBy { it.id },
            monitoredFolders = (provider.monitoredFolders + ref).distinctBy { it.id },
        )
        scope.launch {
            SharePrefs(appContext).saveProvider(updated)
            runImportFolder(appContext, updated, ref)
            MonitorWorker.reschedule(appContext)
        }
    }

    /** Remove an imported folder: drop it (+ any monitor flag) and prune its tracks. */
    fun removeImportedFolder(context: Context, provider: CloudProvider, ref: CloudFolderRef) {
        val appContext = context.applicationContext
        val updated = provider.copy(
            importFolders = provider.importFolders.filter { it.id != ref.id },
            monitoredFolders = provider.monitoredFolders.filter { it.id != ref.id },
        )
        scope.launch {
            SharePrefs(appContext).saveProvider(updated)
            val total = LibraryRepository.importScoped(
                appContext,
                CloudImporter.sourceEntityFor(updated, 0, 0L),
                freshTracks = emptyList(),
                scopePrefixes = listOf(CloudImporter.monitoredPrefix(provider, ref)),
            )
            SharePrefs(appContext).saveProvider(updated.copy(trackCount = total))
            MonitorWorker.reschedule(appContext)
        }
    }

    /** Toggle monitoring for a folder. Enabling also imports it. */
    fun setFolderMonitored(context: Context, provider: CloudProvider, ref: CloudFolderRef, enabled: Boolean) {
        val appContext = context.applicationContext
        val updated = if (enabled) {
            provider.copy(
                importFolders = (provider.importFolders + ref).distinctBy { it.id },
                monitoredFolders = (provider.monitoredFolders + ref).distinctBy { it.id },
            )
        } else {
            provider.copy(monitoredFolders = provider.monitoredFolders.filter { it.id != ref.id })
        }
        scope.launch {
            SharePrefs(appContext).saveProvider(updated)
            if (enabled) runImportFolder(appContext, updated, ref)
            MonitorWorker.reschedule(appContext)
        }
    }

    private suspend fun runImportFolder(context: Context, provider: CloudProvider, ref: CloudFolderRef) {
        importingProviders[provider.id] = true
        errorsByProvider.remove(provider.id)
        try {
            val token = CloudConnectionRegistry.validAccessToken(provider.id)
            if (token == null) {
                errorsByProvider[provider.id] = "Reconnect needed — token unavailable."
                return
            }
            val existing = LibraryRepository.sourceTrackMap(context, SourceType.Cloud, provider.id)
            withContext(Dispatchers.IO) { CloudImporter.enumerate(context, provider, token, listOf(ref), existing) }
                .onSuccess { tracks ->
                    val total = LibraryRepository.importScoped(
                        context,
                        CloudImporter.sourceEntityFor(provider, tracks.size, tracks.sumOf { it.sizeBytes }),
                        freshTracks = tracks,
                        scopePrefixes = listOf(CloudImporter.monitoredPrefix(provider, ref)),
                    )
                    lastImportedCount[provider.id] = total
                    SharePrefs(context).saveProvider(
                        provider.copy(
                            status = ConnectionStatus.Connected,
                            trackCount = total,
                            lastSyncMs = System.currentTimeMillis(),
                        ),
                    )
                }
                .onFailure { t ->
                    errorsByProvider[provider.id] = t.message ?: t::class.simpleName.orEmpty()
                    android.util.Log.e("CloudLibrary", "import failed id=${provider.id} folder=${ref.path}", t)
                }
        } finally {
            importingProviders[provider.id] = false
        }
    }

    /** Disconnect: drop indexed tracks, config, and stored token. */
    fun deleteProvider(context: Context, providerId: String) {
        importingProviders.remove(providerId)
        errorsByProvider.remove(providerId)
        if (activeBrowseProviderId == providerId) activeBrowseProviderId = null
        val appContext = context.applicationContext
        scope.launch {
            LibraryRepository.removeSource(appContext, SourceType.Cloud, providerId)
            EncryptedShareStore(appContext).deleteCloudToken(providerId)
            SharePrefs(appContext).deleteProvider(providerId)
        }
    }
}

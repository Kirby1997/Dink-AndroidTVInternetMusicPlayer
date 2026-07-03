package com.example.dink_smb_player.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.model.ConnectionStatus
import com.example.dink_smb_player.data.model.SmbShare
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.data.prefs.EncryptedShareStore
import com.example.dink_smb_player.data.prefs.SharePrefs
import com.example.dink_smb_player.data.source.smb.SmbClient
import com.example.dink_smb_player.data.source.smb.SmbImporter
import com.example.dink_smb_player.data.source.smb.SmbSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide store of per-share enumerated SMB tracks. Mirrors [MediaLibrary]
 * but keyed by [SmbShare.id] so multiple shares stay independent and SmbSharesScreen
 * can render per-share track counts without re-walking the network.
 *
 * Sync triggers:
 *   - [SmbSharesScreen] "Sync" button or first focus on a share row
 *   - [com.example.dink_smb_player.ui.screens.sources.AddShareWizard] "Finish" step
 *   - boot-time silent refresh for shares with [com.example.dink_smb_player.data.model.SyncSchedule.Auto]
 */
object SharesLibrary {

    val songsByShare = mutableStateMapOf<String, List<Song>>()
    val errorsByShare = mutableStateMapOf<String, String>()
    val syncingShares = mutableStateMapOf<String, Boolean>()

    /** True while a folder import / re-import into the library index is running. */
    val importingShares = mutableStateMapOf<String, Boolean>()

    /** Total tracks the share contributed to the library after its last import —
     *  recursive (includes every subfolder), so the UI can report real scope. */
    val lastImportedCount = mutableStateMapOf<String, Int>()

    /** Live progress of the IN-FLIGHT import: tracks found so far + throughput, updated as
     *  the walk flushes batches. Drives the "Importing… N tracks (R/s)" UI; cleared when the
     *  import finishes. Distinct from [lastImportedCount] (the final settled total). */
    data class ImportProgress(val found: Int, val ratePerSec: Double = 0.0)

    val importProgress = mutableStateMapOf<String, ImportProgress>()

    /** App-lifetime scope so a sync (and its status write) survives the user
     *  navigating away from SmbSharesScreen. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()

    /** Currently-browsed share id. Set when SmbSharesScreen card is opened or when
     *  AddShareWizard finishes a save. Cleared on Back from the browse screen. */
    var activeBrowseShareId: String? by mutableStateOf(null)

    fun allSongs(): List<Song> = songsByShare.values.flatten()

    /** Launch (or no-op if already running) a cancellable sync on the app scope. */
    fun startSync(context: Context, share: SmbShare) {
        if (jobs[share.id]?.isActive == true) return
        val appContext = context.applicationContext
        jobs[share.id] = scope.launch { sync(appContext, share) }
    }

    /** Cancel an in-flight sync. The job's CancellationException handler in
     *  [sync] reverts the persisted status to Idle. */
    fun stopSync(shareId: String) {
        jobs.remove(shareId)?.cancel()
        syncingShares[shareId] = false
    }

    /** Remove a share entirely: stop sync, drop cached tracks, delete config +
     *  encrypted creds, and tear down any live connection. */
    fun deleteShare(context: Context, shareId: String) {
        jobs.remove(shareId)?.cancel()
        clear(shareId)
        if (activeBrowseShareId == shareId) activeBrowseShareId = null
        val appContext = context.applicationContext
        scope.launch {
            runCatching { SmbClient.closeAllFor(shareId) }
            LibraryRepository.removeSource(appContext, SourceType.Smb, shareId)
            EncryptedShareStore(appContext).deleteSmbCreds(shareId)
            SharePrefs(appContext).deleteShare(shareId)
        }
    }

    /** Add [smbPath] to the share's import roots, persist, then import ONLY that
     *  folder + subfolders into the library index (other imported folders are left
     *  untouched). "" imports the whole share. Re-imports prune deleted files within
     *  the folder. */
    fun importFolder(context: Context, share: SmbShare, smbPath: String) {
        val appContext = context.applicationContext
        // Importing a folder also MONITORS it — so files added on the NAS later are
        // auto-indexed without the user toggling anything. (Monitor can still be turned
        // off per-folder in the browser.)
        val updated = share.copy(
            importPaths = (share.importPaths + smbPath).distinct(),
            monitoredPaths = (share.monitoredPaths + smbPath).distinct(),
        )
        scope.launch {
            SharePrefs(appContext).saveShare(updated)
            runImportFolder(appContext, updated, smbPath)
            com.example.dink_smb_player.data.source.MonitorWorker.reschedule(appContext)
        }
    }

    /** Remove an imported folder: drop it (and any monitor flag) from the share's
     *  roots and prune its tracks from the index, leaving sibling folders intact. */
    fun removeImportedFolder(context: Context, share: SmbShare, smbPath: String) {
        val appContext = context.applicationContext
        val updated = share.copy(
            importPaths = share.importPaths.filter { it != smbPath },
            monitoredPaths = share.monitoredPaths.filter { it != smbPath },
        )
        scope.launch {
            SharePrefs(appContext).saveShare(updated)
            val total = LibraryRepository.importScoped(
                appContext,
                SmbImporter.sourceEntityFor(updated, 0, 0L),
                freshTracks = emptyList(),
                scopePrefixes = listOf(SmbImporter.monitoredPrefix(share, smbPath)),
            )
            SharePrefs(appContext).saveShare(updated.copy(trackCount = total))
            com.example.dink_smb_player.data.source.MonitorWorker.reschedule(appContext)
        }
    }

    /** Toggle background monitoring for a single folder ([smbPath]; "" = whole share).
     *  Enabling also imports the folder (can't monitor what isn't in the library);
     *  disabling leaves the already-imported tracks in place. */
    fun setFolderMonitored(context: Context, share: SmbShare, smbPath: String, enabled: Boolean) {
        val appContext = context.applicationContext
        val updated = if (enabled) {
            share.copy(
                importPaths = (share.importPaths + smbPath).distinct(),
                monitoredPaths = (share.monitoredPaths + smbPath).distinct(),
            )
        } else {
            share.copy(monitoredPaths = share.monitoredPaths.filter { it != smbPath })
        }
        scope.launch {
            SharePrefs(appContext).saveShare(updated)
            if (enabled) runImportFolder(appContext, updated, smbPath)
            com.example.dink_smb_player.data.source.MonitorWorker.reschedule(appContext)
        }
    }

    /** Enumerate ONLY [smbPath] (+ subfolders) and reconcile it into the index
     *  without touching the share's other imported folders. ("" = whole share.) */
    private suspend fun runImportFolder(context: Context, share: SmbShare, smbPath: String) {
        importingShares[share.id] = true
        importProgress[share.id] = ImportProgress(0)
        val importStartMs = System.currentTimeMillis()
        errorsByShare.remove(share.id)
        try {
            val creds = EncryptedShareStore(context).getSmbCreds(share.id)
            val existing = LibraryRepository.sourceTrackMap(context, SourceType.Smb, share.id)
            withContext(Dispatchers.IO) {
                SmbImporter.enumerate(
                    context, share, creds, listOf(smbPath), existing,
                    // Persist new tracks in batches as the walk finds them, so a restart
                    // mid-import doesn't lose the whole walk — resumes via id reuse instead.
                    flushBatch = { batch -> LibraryRepository.upsertBatch(context, batch) },
                    // Live progress (count + throughput) for the "Importing… N tracks (R/s)" UI.
                    onProgress = { count ->
                        val elapsed = (System.currentTimeMillis() - importStartMs) / 1000.0
                        val rate = if (elapsed > 0.0) count / elapsed else 0.0
                        importProgress[share.id] = ImportProgress(count, rate)
                    },
                )
            }
                .onSuccess { res ->
                    val tracks = res.tracks
                    if (!res.complete) {
                        android.util.Log.w("SharesLibrary", "import walk incomplete id=${share.id} path=$smbPath — upsert-only, not pruning (would lose unseen tracks)")
                    }
                    val total = LibraryRepository.importScoped(
                        context,
                        SmbImporter.sourceEntityFor(share, tracks.size, tracks.sumOf { it.sizeBytes }),
                        freshTracks = tracks,
                        scopePrefixes = listOf(SmbImporter.monitoredPrefix(share, smbPath)),
                        prune = res.complete,
                    )
                    lastImportedCount[share.id] = total
                    SharePrefs(context).saveShare(
                        share.copy(
                            status = ConnectionStatus.Connected,
                            trackCount = total,
                            lastSyncMs = System.currentTimeMillis(),
                        ),
                    )
                }
                .onFailure { t ->
                    errorsByShare[share.id] = t.message ?: t::class.simpleName.orEmpty()
                    android.util.Log.e("SharesLibrary", "import failed id=${share.id} path=$smbPath", t)
                }
        } finally {
            importingShares[share.id] = false
            importProgress.remove(share.id)
        }
    }

    suspend fun sync(context: Context, share: SmbShare) {
        val prefs = SharePrefs(context.applicationContext)
        val store = EncryptedShareStore(context.applicationContext)
        val creds = store.getSmbCreds(share.id)
        syncingShares[share.id] = true
        errorsByShare.remove(share.id)
        prefs.saveShare(share.copy(status = ConnectionStatus.Syncing))
        try {
            val result = withContext(Dispatchers.IO) { SmbSync.enumerate(share, creds) }
            result
                .onSuccess { songs ->
                    songsByShare[share.id] = songs
                    prefs.saveShare(
                        share.copy(
                            status = ConnectionStatus.Connected,
                            trackCount = songs.size,
                            lastSyncMs = System.currentTimeMillis(),
                        ),
                    )
                }
                .onFailure { t ->
                    errorsByShare[share.id] = t.message ?: t::class.simpleName.orEmpty()
                    android.util.Log.e("SharesLibrary", "sync failed id=${share.id} host=${share.host}", t)
                    prefs.saveShare(share.copy(status = ConnectionStatus.Offline))
                }
        } catch (c: CancellationException) {
            // Stopped by the user — persist Idle even though the scope is cancelling.
            withContext(NonCancellable) {
                prefs.saveShare(share.copy(status = ConnectionStatus.Idle))
            }
            throw c
        } finally {
            syncingShares[share.id] = false
        }
    }

    fun clear(shareId: String) {
        songsByShare.remove(shareId)
        errorsByShare.remove(shareId)
        syncingShares.remove(shareId)
    }
}

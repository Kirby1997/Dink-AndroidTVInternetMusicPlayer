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
import com.example.dink_smb_player.data.prefs.EncryptedShareStore
import com.example.dink_smb_player.data.prefs.SharePrefs
import com.example.dink_smb_player.data.source.SourceLocks
import com.example.dink_smb_player.data.source.smb.SmbClient
import com.example.dink_smb_player.data.source.smb.SmbConnectionRegistry
import com.example.dink_smb_player.data.source.smb.SmbImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide SMB share state for the sources UI: per-share import / delete progress
 * and errors, plus the folder import / remove / monitor actions that reconcile a share
 * into the library index ([LibraryRepository]).
 */
object SharesLibrary {

    val errorsByShare = mutableStateMapOf<String, String>()

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

    /** App-lifetime scope so an import / delete (and its status write) survives the
     *  user navigating away from SmbSharesScreen. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Currently-browsed share id. Set when SmbSharesScreen card is opened or when
     *  AddShareWizard finishes a save. Cleared on Back from the browse screen. */
    var activeBrowseShareId: String? by mutableStateOf(null)

    /** Shares whose delete is in flight (cancelling jobs, dropping rows). The browse
     *  screen shows "Deleting…" meanwhile; cleared when the delete finishes. */
    val deletingShares = mutableStateMapOf<String, Boolean>()

    /** Remove a share entirely: cancel + wait out any in-flight import/monitor walk
     *  for it (so none can re-add rows afterwards), then drop its indexed tracks,
     *  encrypted creds and config. [onDone] runs on Main once the delete has finished
     *  (null) or failed — callers confirm to the user only then. */
    fun deleteShare(context: Context, shareId: String, onDone: (Throwable?) -> Unit = {}) {
        val appContext = context.applicationContext
        deletingShares[shareId] = true
        scope.launch {
            val err = runCatching {
                SourceLocks.remove(
                    shareId,
                    afterCancel = {
                        // The share is tombstoned now, so SmbClient refuses to reconnect it (a
                        // retag / art / lyric read can't re-create its session). Drop it from
                        // the registry too, then release its connections on IO without waiting:
                        // on a dead NAS the release force-closes the socket, which is what makes
                        // a walk blocked in a list()/tag read fail at once instead of sitting out
                        // the 30 s request timeout before it sees the cancel. A graceful
                        // tree-disconnect/logoff there would itself wait out that timeout.
                        SmbConnectionRegistry.remove(shareId)
                        scope.launch(Dispatchers.IO) { runCatching { SmbClient.closeAllFor(shareId) } }
                    },
                ) {
                    LibraryRepository.removeSource(appContext, SourceType.Smb, shareId)
                    EncryptedShareStore.get(appContext).deleteSmbCreds(shareId)
                    SharePrefs(appContext).deleteShare(shareId)
                }
            }.exceptionOrNull()
            if (err != null) {
                android.util.Log.e("SharesLibrary", "delete failed id=$shareId", err)
                // The tombstone is lifted on failure: make the share resolvable again.
                runCatching { SmbConnectionRegistry.hydrate(appContext) }
            }
            withContext(Dispatchers.Main) {
                deletingShares.remove(shareId)
                if (err == null) {
                    clear(shareId)
                    importingShares.remove(shareId)
                    importProgress.remove(shareId)
                    lastImportedCount.remove(shareId)
                    folderIssues.remove(shareId)
                    if (activeBrowseShareId == shareId) activeBrowseShareId = null
                }
                onDone(err)
            }
        }
    }

    /** Add [smbPath] to the share's import roots, persist, then import ONLY that
     *  folder + subfolders into the library index (other imported folders are left
     *  untouched). "" imports the whole share. Re-imports prune deleted files within
     *  the folder. */
    fun importFolder(context: Context, share: SmbShare, smbPath: String) {
        val appContext = context.applicationContext
        scope.launch {
            // Importing a folder also MONITORS it — so files added on the NAS later are
            // auto-indexed without the user toggling anything. (Monitor can still be turned
            // off per-folder in the browser.) Applied to the CURRENT stored share, not the
            // screen's copy; null = the share was deleted meanwhile.
            SharePrefs(appContext).updateShare(share.id) {
                it.copy(
                    importPaths = (it.importPaths + smbPath).distinct(),
                    monitoredPaths = (it.monitoredPaths + smbPath).distinct(),
                )
            } ?: return@launch
            SourceLocks.runExclusive(share.id, listOf(smbPath)) {
                runImportFolder(appContext, share.id, smbPath)
            }
            com.example.dink_smb_player.data.source.MonitorWorker.reschedule(appContext)
        }
    }

    /** Remove an imported folder: drop it (and any monitor flag) from the share's
     *  roots and prune its tracks from the index, leaving sibling folders intact.
     *  [onDone] runs on Main when the prune has finished (null) or failed. */
    fun removeImportedFolder(
        context: Context,
        share: SmbShare,
        smbPath: String,
        onDone: (Throwable?) -> Unit = {},
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val err = runCatching { removeImportedFolderNow(appContext, share, smbPath) }.exceptionOrNull()
            if (err != null) android.util.Log.e("SharesLibrary", "remove folder failed id=${share.id} path=$smbPath", err)
            withContext(Dispatchers.Main) { onDone(err) }
        }
    }

    private suspend fun removeImportedFolderNow(appContext: Context, share: SmbShare, smbPath: String) {
        val prefs = SharePrefs(appContext)
        prefs.updateShare(share.id) {
            it.copy(
                importPaths = it.importPaths.filter { p -> p != smbPath },
                monitoredPaths = it.monitoredPaths.filter { p -> p != smbPath },
            )
        } ?: return
        // An import (or monitor pass) of this folder still in flight would re-add its
        // rows after the prune below — stop it first.
        SourceLocks.cancelOverlapping(share.id, listOf(smbPath))
        SourceLocks.runExclusive<Unit>(share.id, listOf(smbPath)) {
            // Re-read under the lock: skip if the share was deleted while we waited
            // (importScoped would re-create its source row).
            val fresh = prefs.shares.first().firstOrNull { it.id == share.id } ?: return@runExclusive
            // A failed write throws: the caller reports "Couldn't remove", not "Removed".
            LibraryRepository.importScoped(
                appContext,
                SmbImporter.sourceEntityFor(fresh, 0, 0L),
                freshTracks = emptyList(),
                scopePrefixes = listOf(SmbImporter.monitoredPrefix(fresh, smbPath)),
            ).getOrThrow()
            refreshShareStats(appContext, prefs, share.id, synced = false)
        }
        com.example.dink_smb_player.data.source.MonitorWorker.reschedule(appContext)
    }

    /** Toggle background monitoring for a single folder ([smbPath]; "" = whole share).
     *  Enabling also imports the folder (can't monitor what isn't in the library);
     *  disabling leaves the already-imported tracks in place. */
    fun setFolderMonitored(context: Context, share: SmbShare, smbPath: String, enabled: Boolean) {
        val appContext = context.applicationContext
        scope.launch {
            SharePrefs(appContext).updateShare(share.id) {
                if (enabled) {
                    it.copy(
                        importPaths = (it.importPaths + smbPath).distinct(),
                        monitoredPaths = (it.monitoredPaths + smbPath).distinct(),
                    )
                } else {
                    it.copy(monitoredPaths = it.monitoredPaths.filter { p -> p != smbPath })
                }
            } ?: return@launch
            if (enabled) {
                SourceLocks.runExclusive(share.id, listOf(smbPath)) {
                    runImportFolder(appContext, share.id, smbPath)
                }
            }
            com.example.dink_smb_player.data.source.MonitorWorker.reschedule(appContext)
        }
    }

    /** Enumerate ONLY [smbPath] (+ subfolders) and reconcile it into the index
     *  without touching the share's other imported folders. ("" = whole share.)
     *  Call under [SourceLocks.runExclusive] for [shareId]. */
    private suspend fun runImportFolder(context: Context, shareId: String, smbPath: String) {
        // Fresh read under the source lock — the caller's copy may predate a delete or
        // another folder edit. Gone = deleted while this import was queued.
        val prefs = SharePrefs(context)
        val share = prefs.shares.first().firstOrNull { it.id == shareId } ?: return
        importingShares[share.id] = true
        importProgress[share.id] = ImportProgress(0)
        val importStartMs = System.currentTimeMillis()
        errorsByShare.remove(share.id)
        try {
            val creds = EncryptedShareStore.get(context).getSmbCreds(share.id)
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
                    // The folder itself is gone / unreadable: report it (the end-of-import
                    // toast reads errorsByShare) instead of a silent "Imported 0".
                    SmbImporter.describeRootIssues(res.rootIssues)?.let { errorsByShare[share.id] = it }
                    val total = LibraryRepository.importScoped(
                        context,
                        SmbImporter.sourceEntityFor(share, tracks.size, tracks.sumOf { it.sizeBytes }),
                        freshTracks = tracks,
                        scopePrefixes = listOf(SmbImporter.monitoredPrefix(share, smbPath)),
                        prune = res.complete,
                    ).getOrElse { t ->
                        // Indexed in memory but not saved: gone on restart. Report it as a failed
                        // import (the browser toasts errorsByShare), not "Imported N" / Connected.
                        errorsByShare[share.id] = "library couldn't be saved (${t.message ?: t::class.simpleName})"
                        android.util.Log.e("SharesLibrary", "import not saved id=${share.id} path=$smbPath", t)
                        return@onSuccess
                    }
                    lastImportedCount[share.id] = total
                    prefs.updateShare(share.id) { it.copy(status = ConnectionStatus.Connected) }
                    refreshShareStats(context, prefs, share.id, synced = true)
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

    fun clear(shareId: String) {
        errorsByShare.remove(shareId)
    }

    /** Monitor-pass status per share: the message last set by [setFolderIssue]. */
    private val folderIssues = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Bring the share card's stats in line with the library: track count and total size
     * come from the indexed rows of this share (not from whichever folder was last
     * imported), and [synced] stamps "synced just now" — pass it only for a completed
     * import or a clean monitor pass. No-op if the share was deleted meanwhile.
     */
    suspend fun refreshShareStats(context: Context, prefs: SharePrefs, shareId: String, synced: Boolean) {
        val rows = LibraryRepository.sourceTrackMap(context, SourceType.Smb, shareId).values
        val bytes = rows.sumOf { it.sizeBytes }
        prefs.updateShare(shareId) {
            it.copy(
                trackCount = rows.size,
                sizeBytes = bytes,
                lastSyncMs = if (synced) System.currentTimeMillis() else it.lastSyncMs,
            )
        }
    }

    /** Show [message] (a monitored folder is gone / unreadable) as the share's error, or with
     *  null clear the one this set before — leaving any other error (a failed import) alone. */
    fun setFolderIssue(shareId: String, message: String?) {
        if (message != null) {
            folderIssues[shareId] = message
            errorsByShare[shareId] = message
        } else {
            val prev = folderIssues.remove(shareId) ?: return
            if (errorsByShare[shareId] == prev) errorsByShare.remove(shareId)
        }
    }
}

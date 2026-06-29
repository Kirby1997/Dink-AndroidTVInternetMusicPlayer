package com.example.dink_smb_player.data.source

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.dink_smb_player.data.MediaLibrary
import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.prefs.EncryptedShareStore
import com.example.dink_smb_player.data.prefs.SharePrefs
import com.example.dink_smb_player.data.source.cloud.CloudConnectionRegistry
import com.example.dink_smb_player.data.source.cloud.CloudImporter
import com.example.dink_smb_player.data.source.smb.SmbImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodic "monitor" pass: re-scans the folders a user marked monitored so files
 * added on a NAS (or new local media) surface without a manual re-import. Only the
 * folders in [com.example.dink_smb_player.data.model.SmbShare.monitoredPaths] are
 * touched — imported-but-unmonitored folders are left alone; local MediaStore is
 * always refreshed (cheap, it's an indexed query).
 *
 * Reconciliation is scoped to monitored folders (see [LibraryRepository.refreshMonitored]),
 * so deletions inside them propagate without disturbing the rest.
 */
class MonitorWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        android.util.Log.i(TAG, "monitor pass start")

        // The index is an empty singleton at process start; WorkManager can run us
        // in a cold process where the UI's boot restore never ran. Rehydrate from
        // disk FIRST — otherwise the mutate-and-persist passes below would overwrite
        // library_index.json with a snapshot missing every imported track.
        LibraryRepository.ensureRestored(ctx)

        // Local media — re-query MediaStore and mirror into the index.
        runCatching { MediaLibrary.refresh(ctx) }

        val prefs = SharePrefs(ctx)
        val store = EncryptedShareStore(ctx)

        // SMB monitored folders.
        val shares = runCatching { prefs.shares.first() }.getOrDefault(emptyList())
        for (share in shares.filter { it.monitoredPaths.isNotEmpty() }) {
            val creds = runCatching { store.getSmbCreds(share.id) }.getOrNull()
            // Reuse already-indexed rows so only NEW files are tag-read this pass.
            val existing = LibraryRepository.sourceTrackMap(ctx, SourceType.Smb, share.id)
            SmbImporter.enumerate(ctx, share, creds, share.monitoredPaths, existing).onSuccess { res ->
                val tracks = res.tracks
                val added = tracks.count { it.id !in existing }
                android.util.Log.i(TAG, "smb '${share.name}': scanned=${tracks.size} new=$added complete=${res.complete} (monitored=${share.monitoredPaths})")
                // A partial walk (NAS slow/asleep at boot, network blip) must NOT prune —
                // pruning a subset deletes real tracks and wipes the library. Upsert-only then.
                LibraryRepository.refreshMonitored(
                    ctx,
                    SmbImporter.sourceEntityFor(share, tracks.size, tracks.sumOf { it.sizeBytes }),
                    tracks,
                    share.monitoredPaths.map { SmbImporter.monitoredPrefix(share, it) },
                    prune = res.complete,
                )
            }.onFailure { android.util.Log.w(TAG, "smb monitor failed '${share.name}'", it) }
        }

        // Cloud monitored folders. We may be in a cold process where DinkApp's boot
        // wiring never ran, so install the token store ourselves before resolving
        // (and refreshing) access tokens.
        CloudConnectionRegistry.installTokenStore(
            get = { pid -> store.getCloudToken(pid) },
            put = { pid, token -> store.putCloudToken(pid, token) },
        )
        val providers = runCatching { prefs.providers.first() }.getOrDefault(emptyList())
        for (provider in providers.filter { it.monitoredFolders.isNotEmpty() }) {
            val existing = LibraryRepository.sourceTrackMap(ctx, SourceType.Cloud, provider.id)
            val result = withContext(Dispatchers.IO) {
                val token = CloudConnectionRegistry.validAccessToken(provider.id)
                    ?: return@withContext null
                CloudImporter.enumerate(ctx, provider, token, provider.monitoredFolders, existing)
            } ?: continue
            result.onSuccess { res ->
                val tracks = res.tracks
                LibraryRepository.refreshMonitored(
                    ctx,
                    CloudImporter.sourceEntityFor(provider, tracks.size, tracks.sumOf { it.sizeBytes }),
                    tracks,
                    provider.monitoredFolders.map { CloudImporter.monitoredPrefix(provider, it) },
                    prune = res.complete,
                )
            }
        }
        ctx.getSharedPreferences(FLAGS, Context.MODE_PRIVATE)
            .edit().putLong(LAST_PASS_MS, System.currentTimeMillis()).apply()
        android.util.Log.i(TAG, "monitor pass done")
        return Result.success()
    }

    companion object {
        private const val TAG = "MonitorWorker"
        const val UNIQUE_NAME = "library-monitor"
        const val UNIQUE_NAME_NOW = "library-monitor-now"
        private const val FLAGS = "dink_flags"
        private const val LAST_PASS_MS = "monitor_last_pass_ms"
        /** Don't run the launch catch-up if a pass finished within this window — a full
         *  SMB walk over a big share is 30–90s, and the 2h periodic already covers it.
         *  Opening the app repeatedly shouldn't re-walk the whole NAS each time. */
        private val MIN_CATCHUP_GAP_MS = TimeUnit.MINUTES.toMillis(90)

        private fun networkConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Idempotent — call whenever a monitor flag changes and at app boot. UPDATE
         *  policy keeps the existing schedule but refreshes constraints/worker class.
         *  2h cadence: the TV is mains-powered, and the scan is cheap now that only new
         *  files are tag-read. */
        fun reschedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MonitorWorker>(2, TimeUnit.HOURS)
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** One-shot catch-up scan, enqueued at app launch so files added on the NAS
         *  while Dink was closed appear soon after opening — not only on the 2h tick.
         *  KEEP policy avoids piling up duplicates if launched repeatedly. */
        fun enqueueNow(context: Context) {
            val ctx = context.applicationContext
            val last = ctx.getSharedPreferences(FLAGS, Context.MODE_PRIVATE).getLong(LAST_PASS_MS, 0L)
            if (System.currentTimeMillis() - last < MIN_CATCHUP_GAP_MS) {
                android.util.Log.i(TAG, "launch catch-up skipped — last pass <90min ago")
                return
            }
            val request = OneTimeWorkRequestBuilder<MonitorWorker>()
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(UNIQUE_NAME_NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}

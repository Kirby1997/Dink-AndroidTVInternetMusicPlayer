package com.example.dink_smb_player.data.source

import android.content.Context
import androidx.work.BackoffPolicy
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
import com.example.dink_smb_player.data.SharesLibrary
import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.prefs.EncryptedShareStore
import com.example.dink_smb_player.data.prefs.SharePrefs
import com.example.dink_smb_player.data.source.smb.SmbConnectionRegistry
import com.example.dink_smb_player.data.source.smb.SmbImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    override suspend fun doWork(): Result = passLock.withLock { runPass() }

    private suspend fun runPass(): Result {
        val ctx = applicationContext
        val flags = ctx.getSharedPreferences(FLAGS, Context.MODE_PRIVATE)
        // The launch catch-up and the periodic pass can both fire close together (or queue
        // behind each other on [passLock]); the second then finds a fresh stamp and skips
        // instead of re-walking the whole NAS.
        if (MonitorPassPolicy.isFresh(flags.getLong(LAST_PASS_MS, 0L), System.currentTimeMillis(), MIN_PASS_GAP_MS)) {
            android.util.Log.i(TAG, "monitor pass skipped — a pass completed <${MIN_PASS_GAP_MS / 60_000}min ago")
            return Result.success()
        }
        android.util.Log.i(TAG, "monitor pass start (attempt ${runAttemptCount + 1})")
        val passStartMs = System.currentTimeMillis()

        // The index is an empty singleton at process start; WorkManager can run us
        // in a cold process where the UI's boot restore never ran. Rehydrate from
        // disk FIRST — otherwise the mutate-and-persist passes below would overwrite
        // library_index.json with a snapshot missing every imported track.
        LibraryRepository.ensureRestored(ctx)
        // Same for the SMB registry + cred lookup: DinkApp's wiring never ran in a cold
        // process, and every per-file tag read opens smb://…?sid= through it — without this
        // they all fail "Unknown SMB share id" and new files land with filename titles.
        SmbConnectionRegistry.hydrate(ctx)

        // Local media — re-query MediaStore and mirror into the index.
        runCatching { MediaLibrary.refresh(ctx) }

        val prefs = SharePrefs(ctx)
        val store = EncryptedShareStore.get(ctx)
        val outcomes = ArrayList<SourceOutcome>()

        // SMB monitored folders. Each share runs under its SourceLocks lock (never
        // concurrently with an import of it) as a job a share delete can cancel.
        val shares = runCatching { prefs.shares.first() }.getOrDefault(emptyList())
        for (listed in shares.filter { it.monitoredPaths.isNotEmpty() }) {
            outcomes += SourceLocks.runExclusive(listed.id, listed.monitoredPaths) {
                // Re-read under the lock: the share may have been deleted, or its folders
                // edited, while this pass waited behind an import.
                val share = runCatching { prefs.shares.first() }.getOrDefault(emptyList())
                    .firstOrNull { it.id == listed.id }
                    ?.takeIf { it.monitoredPaths.isNotEmpty() }
                    ?: return@runExclusive SourceOutcome.SKIPPED
                val creds = runCatching { store.getSmbCreds(share.id) }.getOrNull()
                // Reuse already-indexed rows so only NEW files are tag-read this pass.
                val existing = LibraryRepository.sourceTrackMap(ctx, SourceType.Smb, share.id)
                SmbImporter.enumerate(ctx, share, creds, share.monitoredPaths, existing).fold(
                    onSuccess = { res ->
                        val tracks = res.tracks
                        val added = tracks.count { it.id !in existing }
                        android.util.Log.i(TAG, "smb '${share.name}': scanned=${tracks.size} new=$added complete=${res.complete} incomplete=${res.incompleteRoots} issues=${res.rootIssues} (monitored=${share.monitoredPaths})")
                        // A partial walk (NAS slow/asleep at boot, network blip) must NOT prune
                        // what it didn't list — pruning a subset deletes real tracks and wipes the
                        // library. Folders that WERE listed in full still prune.
                        val (paths, prune) = MonitorPassPolicy.pruneScope(share.monitoredPaths, res::rootComplete)
                        val saved = LibraryRepository.refreshMonitored(
                            ctx,
                            SmbImporter.sourceEntityFor(share, tracks.size, tracks.sumOf { it.sizeBytes }),
                            tracks,
                            paths.map { SmbImporter.monitoredPrefix(share, it) },
                            prune = prune,
                        )
                        // A monitored folder that's gone/unreadable: say so on the share (the user
                        // has to act), and clear it once the folders are all readable again.
                        SharesLibrary.setFolderIssue(share.id, SmbImporter.describeRootIssues(res.rootIssues))
                        // The share card's "N tracks · synced …" was only ever written by a
                        // manual import, so it drifted from the library as monitor passes added
                        // and pruned tracks. A clean, saved pass IS a sync — stamp it.
                        if (MonitorPassPolicy.refreshesShareStats(res.incompleteRoots.isEmpty(), res.rootIssues.isEmpty(), saved.isSuccess)) {
                            SharesLibrary.refreshShareStats(ctx, prefs, share.id, synced = true)
                        }
                        MonitorPassPolicy.smbOutcome(res.incompleteRoots.isNotEmpty(), res.rootIssues.isNotEmpty())
                    },
                    onFailure = {
                        android.util.Log.w(TAG, "smb monitor failed '${share.name}'", it)
                        SourceOutcome.FAILED
                    },
                )
            } ?: SourceOutcome.CANCELLED
        }

        val decision = MonitorPassPolicy.decide(outcomes, runAttemptCount)
        // Per-share walk detail (lists, re-reads, timeouts) is SmbImporter's "walk summary" line.
        android.util.Log.i(TAG, "monitor pass done in ${(System.currentTimeMillis() - passStartMs) / 1000}s outcomes=$outcomes stamp=${decision.stamp} retry=${decision.retry}")
        // Stamp only a pass where every walk completed — a failed/partial pass stamped as
        // done would suppress the launch catch-up that should repair it.
        if (decision.stamp) {
            flags.edit().putLong(LAST_PASS_MS, System.currentTimeMillis()).apply()
        }
        return if (decision.retry) Result.retry() else Result.success()
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
        private const val CATCHUP_DELAY_S = 60L
        /** doWork skips when a pass COMPLETED within this window: catch-up + periodic
         *  overlap. Shorter than the 2h period so a jittered periodic run isn't skipped. */
        private val MIN_PASS_GAP_MS = TimeUnit.MINUTES.toMillis(60)
        /** Backoff for Result.retry() after a failed/partial walk (NAS asleep, network
         *  blip) — spaced out so a long-down NAS isn't hammered. */
        private const val RETRY_BACKOFF_MIN = 5L

        /** Serialises passes in this process (catch-up vs periodic). */
        private val passLock = Mutex()

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
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_MIN, TimeUnit.MINUTES)
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
                // Let launch settle first: the walk (parallel SMB lists + tag-read
                // extractors + index merge + library rewrite) competing with the first
                // frames, restore and Home shelves was a big part of the launch stutter.
                .setInitialDelay(CATCHUP_DELAY_S, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_MIN, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(UNIQUE_NAME_NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** How one source fared in a monitor pass. */
internal enum class SourceOutcome {
    /** Walk finished and was reconciled (pruned). */
    COMPLETE,
    /** Walk finished, but a monitored folder is gone / unreadable (its parent listing says
     *  so). The rest was pruned; that folder wasn't. Retrying can't fix it — the user must. */
    ROOT_ISSUE,
    /** Walk returned a subset (a listing failed) — upserted, not pruned. */
    INCOMPLETE,
    /** Couldn't reach the source at all (enumerate failed). */
    FAILED,
    /** Nothing to do / can't be helped by retrying (deleted, unmonitored, no token). */
    SKIPPED,
    /** Cancelled mid-walk by a share delete or folder removal. */
    CANCELLED,
}

/** Pure stamping / retry rules for [MonitorWorker], split out for unit tests. */
internal object MonitorPassPolicy {
    /** Retries per pass before giving up until the next scheduled run. */
    const val MAX_RETRIES = 3

    data class Decision(val stamp: Boolean, val retry: Boolean)

    /** Stamp LAST_PASS_MS only when every source walked completely (or had nothing to
     *  do). Retry — bounded by [runAttemptCount] — when a source was unreachable or only
     *  partly walked; a cancel is the user's doing, so neither stamp nor retry. */
    fun decide(outcomes: List<SourceOutcome>, runAttemptCount: Int): Decision {
        val clean = outcomes.all {
            it == SourceOutcome.COMPLETE || it == SourceOutcome.SKIPPED || it == SourceOutcome.ROOT_ISSUE
        }
        val network = outcomes.any { it == SourceOutcome.FAILED || it == SourceOutcome.INCOMPLETE }
        return Decision(stamp = clean, retry = network && runAttemptCount < MAX_RETRIES)
    }

    /** An SMB walk's outcome: a folder that failed transiently retries; one whose failure is
     *  settled ([ROOT_ISSUE][SourceOutcome.ROOT_ISSUE]) does not. */
    fun smbOutcome(anyIncomplete: Boolean, anyRootIssue: Boolean): SourceOutcome = when {
        anyIncomplete -> SourceOutcome.INCOMPLETE
        anyRootIssue -> SourceOutcome.ROOT_ISSUE
        else -> SourceOutcome.COMPLETE
    }

    /** Whether a pass may refresh the share card's track count and "synced" time: only a
     *  walk that listed every monitored folder, with no settled folder issue, and whose
     *  result was saved (or needed no save). Anything less isn't a sync the card can claim. */
    fun refreshesShareStats(allRootsComplete: Boolean, noRootIssues: Boolean, saved: Boolean): Boolean =
        allRootsComplete && noRootIssues && saved

    /**
     * Which monitored [paths] to reconcile, and whether to prune: the folders the walk listed
     * in full ([rootComplete]) prune; the rest are never pruned. None complete → every path,
     * upsert-only (reconcile skips an empty prefix list entirely, which would drop the upsert).
     */
    fun pruneScope(paths: List<String>, rootComplete: (String) -> Boolean): Pair<List<String>, Boolean> {
        val complete = paths.filter(rootComplete)
        return if (complete.isEmpty()) paths to false else complete to true
    }

    /** True when a pass completed less than [gapMs] ago. A stamp in the future (clock
     *  moved back) doesn't count as fresh. */
    fun isFresh(lastPassMs: Long, nowMs: Long, gapMs: Long): Boolean =
        lastPassMs > 0L && nowMs >= lastPassMs && nowMs - lastPassMs < gapMs
}

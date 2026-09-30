package com.example.dink_smb_player.data.source.smb

import android.content.Context
import com.example.dink_smb_player.data.index.SourceEntity
import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import com.example.dink_smb_player.data.index.TrackMerges
import com.example.dink_smb_player.data.model.SmbShare
import com.example.dink_smb_player.data.prefs.SmbCreds
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.library.trackIdFor
import com.example.dink_smb_player.data.source.ReadResult
import com.example.dink_smb_player.data.source.SmbProbe
import com.example.dink_smb_player.data.source.SourceLocks
import com.example.dink_smb_player.data.source.TagReadGate
import com.example.dink_smb_player.data.source.TagReader
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Walks the user-chosen folders ([SmbShare.importPaths]) of a share and produces
 * the [TrackEntity] rows for [com.example.dink_smb_player.data.library.LibraryRepository].
 * Scope is bounded by what the user imported, not the whole share.
 *
 * The walk is PARALLEL: directory listings (and the per-new-file tag reads) run
 * concurrently, bounded by [CONCURRENCY], because each smbj call is a network
 * round-trip — doing 2500 of them sequentially took minutes. smbj multiplexes
 * concurrent requests over one connection, so a shared [DiskShare] is safe here.
 *
 * Listings come first. Three things keep file reads from starving them (a cold monitor pass
 * over Wi-Fi once ran 22 probes beside the lists; the lists hit the 30 s request timeout and
 * the walk ended incomplete):
 *  - reads of NEW / CHANGED files run inline, [WALK_READ_CONCURRENCY] at a time;
 *  - opportunistic re-reads of UNCHANGED rows (still no duration) wait until every listing
 *    is done, then run as a small budgeted batch — see [runRereads];
 *  - file reads ride their own socket ([SmbClient.Channel.READS]), so a probe's bytes never
 *    sit in front of a listing reply on the wire.
 *
 * smbj blocks — [enumerate] switches to Dispatchers.IO itself.
 */
object SmbImporter {

    private const val TAG = "SmbImporter"

    private const val MAX_DEPTH = 12
    /** Max concurrent directory listings. Listing is light (one round-trip, small
     *  response), so this can be wide to keep the walk fast. */
    private const val CONCURRENCY = 16
    /** Max concurrent tag reads of NEW / CHANGED files per walk (on top of the process-wide
     *  [TagReadGate], which a retag can fill to 22). A read pulls header + duration bytes —
     *  far heavier than a listing — so the walk keeps it well under the listing width. */
    internal const val WALK_READ_CONCURRENCY = 6
    /** Opportunistic re-reads of UNCHANGED rows (no duration, no conclusive read yet) per
     *  walk. These are backlog, not news: a monitor pass exists to find new files, so the
     *  backlog gets a fixed slice and the rest waits for the next pass (or a user retag,
     *  which clears it in one go at full width). 60 at [REREAD_CONCURRENCY] is ~30–60 s of
     *  probing on a healthy link — about one walk's worth again, not minutes. */
    internal const val REREAD_BUDGET = 60
    /** Concurrent re-reads. Low: they run in the background, possibly beside playback. */
    internal const val REREAD_CONCURRENCY = 4
    /** Wall-clock cap on the re-read batch: no new re-read starts after this. Bounds a pass
     *  whose backlog is all slow files (a duration probe may take its full 20 s cap). */
    internal const val REREAD_MAX_MS = 90_000L
    /** New-track flush cap. A flush also fires on [FLUSH_INTERVAL_MS] elapsed, so the
     *  in-memory index (and thus the live Library view) updates at least once a second
     *  even on a slow share — that's what makes the library populate progressively
     *  instead of all-at-once at the end. Each flush triggers a sort of the whole index
     *  off-Main, so the cap is kept moderate to bound that re-sort frequency. */
    private const val FLUSH_BATCH = 250
    private const val FLUSH_INTERVAL_MS = 1000L

    /** Outcome of a walk. [complete] is false when ANY directory listing failed
     *  (transient network/SMB error, or a subtree past [MAX_DEPTH]) — [tracks] is then
     *  a SUBSET of what's really on the share. Callers MUST NOT prune against an
     *  incomplete set: doing so deletes real, still-present tracks the walk simply
     *  couldn't see. This is the bug that wiped libraries when a monitor pass ran
     *  against a NAS that was slow/asleep right after the TV booted.
     *
     *  Completeness is tracked PER WALK ROOT, so one bad root doesn't stop the others from
     *  pruning: [incompleteRoots] had a listing fail somewhere below them (retry later);
     *  [rootIssues] are roots that can't be listed at all and whose parent listing says why
     *  (gone, or there but unreadable) — retrying won't help, the user has to act. Neither
     *  kind may be pruned; [rootComplete] tells a caller which of its roots it may prune. */
    data class EnumResult(
        val tracks: List<TrackEntity>,
        val incompleteRoots: Set<String> = emptySet(),
        val rootIssues: Map<String, RootIssue> = emptyMap(),
        /** One-line account of the walk (duration, lists, reads, re-reads, timeouts) for logs. */
        val summary: String = "",
    ) {
        /** Every root was listed in full. */
        val complete: Boolean get() = incompleteRoots.isEmpty() && rootIssues.isEmpty()

        /** True when [root] (a walked root, or a folder nested in one) was listed in full, so
         *  rows under it that the walk didn't return are really gone and may be pruned. */
        fun rootComplete(root: String): Boolean =
            (incompleteRoots + rootIssues.keys).none { SourceLocks.pathsOverlap(it, root) }
    }

    /** Why a walk root couldn't be listed, when its parent listing settles it. */
    enum class RootIssue {
        /** The parent listing doesn't contain it: renamed, moved or deleted on the NAS. */
        NotFound,
        /** The parent lists it, but it (or a folder on the way to it) can't be read. */
        Unreadable,
    }

    /** One-line status for the share, e.g. `Folder "Music/Rock" not found on the share`. */
    fun describeRootIssues(issues: Map<String, RootIssue>): String? {
        if (issues.isEmpty()) return null
        val (root, issue) = issues.entries.sortedBy { it.key }.first()
        val what = when (issue) {
            RootIssue.NotFound -> "not found on the share"
            RootIssue.Unreadable -> "can't be read (access denied?)"
        }
        val more = if (issues.size > 1) " (+${issues.size - 1} more)" else ""
        return "Folder \"${root.replace('\\', '/')}\" $what$more"
    }

    /** Enumerate the given [roots] only (backslash smbPaths; "" = share root). Used
     *  for per-folder monitor refreshes that must not touch other imported folders.
     *  [context] reads embedded tags per file ([TagReader]). [existing] are the source's
     *  already-indexed rows by id: a file already present and unchanged is reused (keeping its
     *  tags), so only NEW or changed files are tag-read — making re-import / monitor cheap.
     *
     *  [flushBatch], when set, is invoked with each [FLUSH_BATCH]-sized batch of NEWLY
     *  tag-read tracks AS the walk progresses, so the import path can persist partial
     *  progress to the index. Without it a restart mid-walk (a 25k first import is minutes)
     *  loses everything; with it, persisted rows are reused by id on the next run, so the
     *  import resumes instead of re-reading every tag. Reused (already-indexed) rows are
     *  NOT flushed — they're already on disk. */
    suspend fun enumerate(
        context: Context,
        share: SmbShare,
        creds: SmbCreds?,
        roots: List<String>,
        existing: Map<String, TrackEntity> = emptyMap(),
        flushBatch: (suspend (List<TrackEntity>) -> Unit)? = null,
        // Invoked with the cumulative count of newly-indexed tracks after each flush,
        // so callers can surface a live "Importing… N tracks" progress count.
        onProgress: (suspend (Int) -> Unit)? = null,
    ): Result<EnumResult> = withContext(Dispatchers.IO) {
        runCatching {
            // Fail fast if the share can't be reached at all. The walk itself re-acquires the
            // DiskShare per list() (below) rather than capturing this handle — see [walk].
            SmbClient.share(share.id, share.host, share.port, share.shareName, creds)
            val out = ConcurrentHashMap<String, TrackEntity>() // dedupe overlapping roots by id
            // Circuit breaker: tripped by the first listing whose connection-level failure
            // survives the reconnect retry (host down / unplugged). Every queued folder and tag
            // read then returns at once instead of each waiting out its own 30 s timeout.
            val breaker = java.util.concurrent.atomic.AtomicBoolean(false)
            val gate = Semaphore(CONCURRENCY)
            val stats = WalkStats()
            val pass = WalkPass(
                readGate = Semaphore(WALK_READ_CONCURRENCY),
                rereads = java.util.concurrent.ConcurrentLinkedQueue(),
                stats = stats,
            )
            // Buffer newly tag-read tracks; drain to flushBatch when the cap is hit OR
            // FLUSH_INTERVAL_MS has elapsed since the last flush (so a slow trickle still
            // surfaces ~1/s). Guarded by a Mutex — the parallel walk produces tracks from
            // many coroutines. `flushed` is the running total reported via onProgress.
            val pending = ArrayList<TrackEntity>(FLUSH_BATCH)
            val flushLock = Mutex()
            var lastFlushAt = System.currentTimeMillis()
            val flushed = java.util.concurrent.atomic.AtomicInteger(0)
            val onNewTrack: suspend (TrackEntity) -> Unit = onNewTrack@{ track ->
                val sink = flushBatch ?: return@onNewTrack
                val batch = flushLock.withLock {
                    pending.add(track)
                    val now = System.currentTimeMillis()
                    if (pending.size >= FLUSH_BATCH || now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                        lastFlushAt = now
                        val copy = ArrayList(pending); pending.clear(); copy
                    } else null
                }
                if (batch != null) {
                    sink(batch)
                    onProgress?.invoke(flushed.addAndGet(batch.size))
                }
            }
            // Drop roots nested inside another monitored/imported root so an overlapping
            // pair like ["music", "music\Music"] doesn't walk the subtree twice.
            val topRoots = dropNestedRoots(roots)
            // Per root: flipped to false by [walk] if any listing under it fails — see [EnumResult].
            val completeByRoot = topRoots.associateWith { java.util.concurrent.atomic.AtomicBoolean(true) }
            val rootIssues = ConcurrentHashMap<String, RootIssue>()
            coroutineScope {
                for (root in topRoots) {
                    launch {
                        val complete = completeByRoot.getValue(root)
                        val listed = walk(this, gate, pass, context, creds, share, root, 0, out, existing, onNewTrack, complete, breaker)
                        // The root itself failed with a per-folder status: ask its parent whether
                        // it's gone. A settled answer is a user problem, not a retry.
                        if (listed == RootListing.PATH_ERROR) {
                            rootIssueFor(share, creds, root, breaker)?.let { issue ->
                                android.util.Log.w(TAG, "walk: root '$root' $issue — not pruned, not retried")
                                rootIssues[root] = issue
                                complete.set(true) // accounted for by the issue, not by a retry
                            }
                        }
                    }
                }
            }
            // Every listing is done: now spend the pass's re-read budget on unchanged rows that
            // still have no duration. Their rows are already in `out` as they were, so a re-read
            // that fails (or never runs) costs nothing — it can't make the walk incomplete.
            // Skipped when the host was judged down.
            val candidates = pass.rereads.toList()
            stats.rereadCandidates = candidates.size
            if (candidates.isNotEmpty() && !breaker.get()) {
                val run = runRereads(candidates, REREAD_BUDGET, REREAD_CONCURRENCY, REREAD_MAX_MS) { c ->
                    // Yield to active playback — keeps streaming smooth during import.
                    com.example.dink_smb_player.data.source.ImportThrottle.gate()
                    val read = TagReadGate.withPermit(c.smbPath) {
                        trackFor(context, share, c.smbPath, c.sizeBytes, c.mtimeMs, c.row)
                    }
                    // A failed read hands the row back untouched: nothing to store or flush.
                    if (read.row !== c.row) {
                        out[c.row.id] = read.row
                        onNewTrack(read.row)
                    }
                    read.conclusive
                }
                stats.rereads = run.done
                stats.rereadErrors = run.errors
                stats.rereadCapped = run.capped
            }
            // Flush the trailing partial batch so the last <FLUSH_BATCH new files are
            // persisted too (the caller's final importScoped re-upserts harmlessly).
            if (flushBatch != null) {
                val tail = flushLock.withLock { val c = ArrayList(pending); pending.clear(); c }
                if (tail.isNotEmpty()) {
                    flushBatch(tail)
                    onProgress?.invoke(flushed.addAndGet(tail.size))
                }
            }
            val incomplete = completeByRoot.filterValues { !it.get() }.keys
            val summary = stats.summary(
                complete = incomplete.isEmpty() && rootIssues.isEmpty(),
                breakerTripped = breaker.get(),
                nowMs = System.currentTimeMillis(),
            )
            android.util.Log.i(TAG, "walk summary '${share.name}': $summary")
            EnumResult(
                out.values.toList(),
                incompleteRoots = incomplete,
                rootIssues = rootIssues.toMap(),
                summary = summary,
            )
        }
            // runCatching would turn a cancel (share deleted / folder removed mid-walk, see
            // SourceLocks) into an ordinary failure; let it propagate as a cancellation.
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    /** Keep only roots that aren't a descendant of another root in the set. */
    private fun dropNestedRoots(roots: List<String>): List<String> {
        val norm = roots.map { it.trim('\\') }.distinct()
        return norm.filter { r ->
            norm.none { p -> p != r && (p.isEmpty() || r.startsWith("$p\\")) }
        }
    }

    /** Path prefix every [TrackEntity.path] under [smbPath] starts with. Lets the
     *  monitor refresh decide which existing rows fall inside a monitored folder. */
    fun monitoredPrefix(share: SmbShare, smbPath: String): String =
        if (smbPath.isEmpty()) "${share.mountPath}/"
        else "${share.mountPath}/${smbPath.replace('\\', '/')}/"

    fun sourceEntityFor(share: SmbShare, trackCount: Int, sizeBytes: Long): SourceEntity = SourceEntity(
        id = share.id,
        type = SourceType.Smb,
        displayName = share.name,
        createdAtMs = System.currentTimeMillis(),
        lastSyncMs = System.currentTimeMillis(),
        trackCount = trackCount,
        sizeBytes = sizeBytes,
    )

    /** How [walk] fared listing its OWN folder (children are reported via `complete`). */
    private enum class RootListing { LISTED, FAILED, PATH_ERROR }

    private suspend fun walk(
        scope: CoroutineScope,
        gate: Semaphore,
        pass: WalkPass,
        context: Context,
        creds: SmbCreds?,
        share: SmbShare,
        smbPath: String,
        depth: Int,
        out: MutableMap<String, TrackEntity>,
        existing: Map<String, TrackEntity>,
        onNewTrack: suspend (TrackEntity) -> Unit,
        complete: java.util.concurrent.atomic.AtomicBoolean,
        breaker: java.util.concurrent.atomic.AtomicBoolean,
    ): RootListing {
        // Hit the depth cap: deeper folders go unwalked, so the result is a subset —
        // mark incomplete so callers don't prune the tracks we never reached.
        if (depth > MAX_DEPTH) { complete.set(false); return RootListing.FAILED }
        // Re-acquire the share per list() instead of capturing one DiskShare for the whole
        // walk. Two reasons: (1) SmbClient.share refreshes its idle timer and ECHO-probes /
        // reconnects a connection that died while idle — a captured handle bypasses that and
        // every list() then waits out the full 30 s request timeout, which is what made a
        // reused walk crawl; (2) a concurrent tag-read's SmbDataSource.open can evict the cached
        // connection, which would dead-handle a captured disk. Cache hit, so this is ~free.
        // Hold a permit only for the list() round-trip, not while waiting on children,
        // so the bounded pool never deadlocks on a deep tree. While a track streams,
        // narrowWhilePlaying additionally caps effective listing concurrency at 2 —
        // a full-width walk starves the player (GC churn + LAN contention).
        var pathError = false
        val entries: List<FileIdBothDirectoryInformation> = gate.withPermit {
            // A folder can sit queued behind the permit for a long time: stop here if the walk
            // was cancelled (share deleted / folder removed, see SourceLocks) meanwhile.
            currentCoroutineContext().ensureActive()
            com.example.dink_smb_player.data.source.ImportThrottle.narrowWhilePlaying {
                listShareFolder(share, creds, smbPath, isRoot = depth == 0, breaker, pass.stats) { pathError = true }
            }
        } ?: run {
            // Unreadable folder (transient network/SMB error, or any failure on a walk
            // root). Skip it so a partial walk still surfaces what it can — but mark the
            // result incomplete so the caller upserts WITHOUT pruning. Pruning here would
            // delete every track under this folder just because we couldn't list it once.
            complete.set(false)
            return if (pathError) RootListing.PATH_ERROR else RootListing.FAILED
        }
        val jobs = ArrayList<Job>(entries.size)
        for (entry in entries) {
            val name = entry.fileName
            if (name == "." || name == "..") continue
            val child = if (smbPath.isEmpty()) name else "$smbPath\\$name"
            when (smbEntryKind(name, entry.fileAttributes)) {
                // NAS metadata / recycle / snapshot trees, hidden/system files, AppleDouble files.
                SmbEntryKind.Junk -> continue
                // Junction / symlink / hidden folder: not descended (a reparse point can loop),
                // but not known to be junk either — keep what's already indexed under it, so
                // the prune doesn't delete it, and the walk still counts as complete.
                SmbEntryKind.Unwalked -> { keepIndexedUnder(share, child, existing, out); continue }
                SmbEntryKind.Keep -> Unit
            }
            val isDir = (entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            if (isDir) {
                jobs += scope.launch { walk(scope, gate, pass, context, creds, share, child, depth + 1, out, existing, onNewTrack, complete, breaker) }
            } else if (SmbSync.isAudio(name)) {
                val id = trackIdFor(SourceType.Smb, share.id, child)
                val reused = existing[id]
                val sizeBytes = entry.endOfFile
                val mtimeMs = entry.lastWriteTime?.toEpochMillis()
                val action = fileAction(reused, sizeBytes, mtimeMs)
                if (reused != null && action != FileAction.READ) {
                    // Already indexed and unchanged → keep the row + its tags, no tag read. The
                    // listing fields ride along (fills fileMtimeMs on rows indexed before it was
                    // recorded); the repository's walk merge keeps everything else as it is now.
                    val kept = reused.copy(sizeBytes = sizeBytes, fileMtimeMs = mtimeMs ?: reused.fileMtimeMs)
                    out[id] = kept
                    // Still missing a duration: a candidate for the budgeted batch that runs once
                    // the listings are done (see enumerate). Until then — and if the budget runs
                    // out — the row stays exactly as it is, unstamped, for the next pass.
                    if (action == FileAction.REREAD) pass.rereads += RereadCandidate(child, sizeBytes, mtimeMs, kept)
                } else {
                    // New, or changed in place (size/mtime differ — SRC-8): read its tags now.
                    // Two gates, both SEPARATE from the listing gate: the walk's own (narrow —
                    // reads must not crowd out directory listing) inside the process-wide
                    // TagReadGate (heavy extractor buffers can't pile up, and concurrent walks +
                    // a retag stay under Media3's retriever cap).
                    jobs += scope.launch {
                        // Yield to active playback — keeps streaming smooth during import.
                        com.example.dink_smb_player.data.source.ImportThrottle.gate()
                        val read = pass.readGate.withPermit {
                            TagReadGate.withPermit(child) {
                                // Host judged down: don't queue a connect timeout per file. The walk
                                // is already incomplete, so the file is simply picked up next pass.
                                if (breaker.get()) null else trackFor(context, share, child, sizeBytes, mtimeMs, reused)
                            }
                        } ?: run { complete.set(false); return@launch }
                        pass.stats.reads.incrementAndGet()
                        if (!read.conclusive) pass.stats.readErrors.incrementAndGet()
                        out[id] = read.row
                        onNewTrack(read.row)
                    }
                }
            }
        }
        jobs.joinAll()
        return RootListing.LISTED
    }

    /** Put every already-indexed row under [smbPath] into [out] unchanged — see
     *  [SmbEntryKind.Unwalked]. The rows ride through the reconcile as "still there". */
    internal fun keepIndexedUnder(
        share: SmbShare,
        smbPath: String,
        existing: Map<String, TrackEntity>,
        out: MutableMap<String, TrackEntity>,
    ) {
        val prefix = monitoredPrefix(share, smbPath)
        var kept = 0
        for (row in existing.values) {
            if (row.path.startsWith(prefix)) { out[row.id] = row; kept++ }
        }
        android.util.Log.i(TAG, "walk: not descending into '$smbPath' (reparse point / hidden) — kept $kept indexed tracks")
    }

    /** Ask [root]'s parent why [root] couldn't be listed — see [rootIssue]. */
    private fun rootIssueFor(
        share: SmbShare,
        creds: SmbCreds?,
        root: String,
        breaker: java.util.concurrent.atomic.AtomicBoolean,
    ): RootIssue? {
        if (breaker.get()) return null
        return rootIssue(
            root,
            list = { parent -> SmbClient.share(share.id, share.host, share.port, share.shareName, creds).list(parent) },
            nameOf = { it.fileName },
            isPathLevel = ::isPathLevelError,
        )
    }

    /**
     * Settle a walk root that failed with a per-folder status (not found / access denied —
     * which a NAS still mounting its volume can also answer, so the root alone proves nothing).
     * Lists the nearest ancestor that CAN be listed (up to the share root):
     *  - the next folder on the path isn't in it → [RootIssue.NotFound] (renamed / deleted);
     *  - it is there → [RootIssue.Unreadable] (the folder exists but refuses us);
     *  - an ancestor listing fails for any other reason (connection) → null: unknown, the
     *    walk stays incomplete and is retried.
     * The share root itself ("") has no parent to ask → null.
     */
    internal fun <T> rootIssue(
        root: String,
        list: (String) -> List<T>,
        nameOf: (T) -> String,
        isPathLevel: (Throwable) -> Boolean,
    ): RootIssue? {
        var child = root.trim('\\')
        while (child.isNotEmpty()) {
            val parent = child.substringBeforeLast('\\', "")
            val listing = try {
                list(parent)
            } catch (t: Throwable) {
                if (parent.isNotEmpty() && isPathLevel(t)) { child = parent; continue }
                return null
            }
            val name = child.substringAfterLast('\\')
            return if (listing.any { nameOf(it).equals(name, ignoreCase = true) }) RootIssue.Unreadable
            else RootIssue.NotFound
        }
        return null
    }

    /** List one folder of [share] for [walk] — see [listFolder]. */
    private fun listShareFolder(
        share: SmbShare,
        creds: SmbCreds?,
        smbPath: String,
        isRoot: Boolean,
        breaker: java.util.concurrent.atomic.AtomicBoolean,
        stats: WalkStats,
        onRootPathError: () -> Unit,
    ): List<FileIdBothDirectoryInformation>? {
        // The DiskShare the last attempt listed on, so the evict only drops THAT connection —
        // not a fresh one a concurrent folder's retry already made (see SmbClient.close).
        var disk: DiskShare? = null
        return listFolder(
            smbPath, isRoot, breaker,
            list = {
                disk = null
                try {
                    SmbClient.share(share.id, share.host, share.port, share.shareName, creds)
                        .also { disk = it }
                        .list(smbPath)
                        .also { stats.lists.incrementAndGet() }
                } catch (t: Throwable) {
                    stats.listErrors.incrementAndGet()
                    if (SmbClient.isTimeout(t)) stats.listTimeouts.incrementAndGet()
                    throw t
                }
            },
            // SmbClient.close decides from the cause: a connection that is only slow (timeout,
            // but it answers an ECHO) or only lost this handle is kept, a dead one is dropped.
            evict = { t ->
                if (SmbClient.close(share.id, failed = disk, cause = t)) stats.evictions.incrementAndGet()
            },
            onRootPathError = onRootPathError,
            // Is the HOST still there after the retry failed too? Yes if the connection the
            // retry used is alive (kept by close), or a fresh connect succeeds right now. No if
            // the retry never got a share at all (disk == null): that WAS the reconnect failing.
            hostAlive = { t ->
                val used = disk
                used != null && (
                    !SmbClient.close(share.id, failed = used, cause = t) ||
                        runCatching { SmbClient.share(share.id, share.host, share.port, share.shareName, creds) }.isSuccess
                    )
            },
        )
    }

    /** List one folder for [walk]. Null = couldn't list it (walk becomes incomplete);
     *  empty = a subfolder we may not read or that vanished, skipped as its true listing.
     *
     *  [breaker] is the walk's circuit breaker. Once tripped, this returns null without
     *  touching the network. It trips when a connection-level failure outlives the one
     *  reconnect ([ListFailure.RECONNECT] then still failing) AND the host doesn't prove alive
     *  ([hostAlive]): the host is down, and each of the hundreds of queued folders would
     *  otherwise spend its own 30 s timeout finding that out. A per-folder status (access
     *  denied, not found) never trips it, and neither does a folder that timed out twice on a
     *  link that still answers — that folder alone is left unlisted (walk incomplete).
     *
     *  [evict] gets the failure so it can keep a connection that is only slow. [hostAlive] is
     *  asked, after the retry failed with a connection-level error, whether the host still
     *  answers; the default (no) is the plain "second failure = dead host" rule.
     *
     *  [onRootPathError] fires when a ROOT fails with a per-folder status — the walk then asks
     *  the root's parent whether it's really gone (see [rootIssue]). */
    internal fun <T> listFolder(
        smbPath: String,
        isRoot: Boolean,
        breaker: java.util.concurrent.atomic.AtomicBoolean,
        list: () -> List<T>,
        evict: (Throwable) -> Unit,
        onRootPathError: () -> Unit = {},
        hostAlive: (Throwable) -> Boolean = { false },
    ): List<T>? {
        var retried = false
        while (true) {
            if (breaker.get()) return null
            try {
                return list()
            } catch (t: Throwable) {
                when (listFailureAction(t, isRoot, retried)) {
                    // The session may have been dropped server-side, or a concurrent evict closed
                    // the share handle under us: re-acquire the share and retry once. Only for
                    // connection-level failures — evicting on a per-folder status would tear the
                    // socket out from under every concurrent list.
                    ListFailure.RECONNECT -> { evict(t); retried = true }
                    // Permission-denied / vanished subfolder: the connection is fine and the
                    // folder is genuinely unreadable, so leaving it out IS the true listing —
                    // the walk stays complete (a denied folder must not block pruning forever).
                    ListFailure.SKIP -> {
                        android.util.Log.i(TAG, "walk: skipping unreadable folder '$smbPath': ${t.message}")
                        return emptyList()
                    }
                    ListFailure.INCOMPLETE -> {
                        if (isRoot && isPathLevelError(t)) onRootPathError()
                        if (SmbClient.isConnectionError(t) && !hostAlive(t) && !breaker.getAndSet(true)) {
                            android.util.Log.w(TAG, "walk: host unreachable at '$smbPath' — aborting queued folders", t)
                        } else {
                            android.util.Log.w(TAG, "walk: couldn't list '$smbPath' — walk incomplete", t)
                        }
                        return null
                    }
                }
            }
        }
    }

    /** What [walk] does after a directory listing throws. */
    internal enum class ListFailure { RECONNECT, SKIP, INCOMPLETE }

    /** Per-folder statuses: the folder itself can't be read (or is gone), the connection
     *  is healthy. */
    private val PATH_LEVEL_STATUSES = setOf(
        NtStatus.STATUS_ACCESS_DENIED,
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
        NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
        NtStatus.STATUS_NO_SUCH_FILE,
        NtStatus.STATUS_NOT_FOUND,
        NtStatus.STATUS_NOT_A_DIRECTORY,
        NtStatus.STATUS_DELETE_PENDING,
    )

    /**
     * Classify a failed `list()` of [t]. [isRoot] is a walk root (an imported/monitored
     * folder); [retried] means we already reconnected once for this folder.
     *
     *  - Permission-denied / not-found on a SUBFOLDER → [ListFailure.SKIP]: the parent just
     *    listed it, so the status is real, and the walk still counts as complete.
     *  - Connection-level failure, or a share handle closed under us ("DiskShare has already
     *    been closed" — a concurrent evict), first time → [ListFailure.RECONNECT] (re-acquire
     *    the share + retry once).
     *  - Anything else → [ListFailure.INCOMPLETE]: never prune against it.
     *
     * A ROOT never skips: a NAS that is still mounting its volume after boot can answer
     * ACCESS_DENIED / NOT_FOUND for a folder that exists, and a "complete" empty root would
     * prune every track under it — the library-wipe this walk was hardened against.
     */
    internal fun listFailureAction(t: Throwable, isRoot: Boolean, retried: Boolean): ListFailure = when {
        !isRoot && isPathLevelError(t) -> ListFailure.SKIP
        !retried && (SmbClient.isConnectionError(t) || SmbClient.isClosedHandleError(t)) -> ListFailure.RECONNECT
        else -> ListFailure.INCOMPLETE
    }

    internal fun isPathLevelError(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            if (e is SMBApiException) return e.status in PATH_LEVEL_STATUSES
            e = e.cause
        }
        return false
    }

    /** What the walk does with one audio file it listed — see [fileAction]. */
    internal enum class FileAction { KEEP, READ, REREAD }

    /**
     * Classify a listed file against its indexed row ([reused], null = not indexed):
     *  - [FileAction.READ]: new, or changed in place (size/mtime differ) — read now, unbudgeted;
     *  - [FileAction.REREAD]: unchanged, but still without a duration and never conclusively
     *    read — a candidate for the pass's budgeted batch ([REREAD_BUDGET]);
     *  - [FileAction.KEEP]: unchanged, nothing to do.
     */
    internal fun fileAction(reused: TrackEntity?, sizeBytes: Long, mtimeMs: Long?): FileAction = when {
        reused == null -> FileAction.READ
        TrackMerges.fileChanged(reused, sizeBytes, mtimeMs) -> FileAction.READ
        LibraryRepository.needsRescanRead(reused, sizeBytes, mtimeMs) -> FileAction.REREAD
        else -> FileAction.KEEP
    }

    /** An unchanged row the walk would like to re-read; [row] is what `out` holds for it. */
    internal class RereadCandidate(val smbPath: String, val sizeBytes: Long, val mtimeMs: Long?, val row: TrackEntity)

    /** Per-walk state shared by every folder of one [enumerate]. */
    private class WalkPass(
        val readGate: Semaphore,
        val rereads: java.util.Queue<RereadCandidate>,
        val stats: WalkStats,
    )

    /** Counters behind [EnumResult.summary]. */
    internal class WalkStats(private val startMs: Long = System.currentTimeMillis()) {
        /** Folders listed. */
        val lists = java.util.concurrent.atomic.AtomicInteger(0)
        /** list() attempts that threw (any reason, retries included) / of those, timeouts. */
        val listErrors = java.util.concurrent.atomic.AtomicInteger(0)
        val listTimeouts = java.util.concurrent.atomic.AtomicInteger(0)
        /** Times a failed list dropped the connection (judged dead, or already replaced). */
        val evictions = java.util.concurrent.atomic.AtomicInteger(0)
        /** New / changed files read, and how many of those reads failed transiently. */
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        val readErrors = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile var rereadCandidates = 0
        @Volatile var rereads = 0
        @Volatile var rereadErrors = 0
        @Volatile var rereadCapped = false

        fun summary(complete: Boolean, breakerTripped: Boolean, nowMs: Long): String {
            val ms = (nowMs - startMs).coerceAtLeast(0L)
            return "took=${ms / 1000}.${(ms % 1000) / 100}s lists=${lists.get()} listErrors=${listErrors.get()} " +
                "timeouts=${listTimeouts.get()} evictions=${evictions.get()} " +
                "reads=${reads.get()} readErrors=${readErrors.get()} " +
                "rereads=$rereads/$REREAD_BUDGET (backlog=$rereadCandidates errors=$rereadErrors" +
                (if (rereadCapped) " time-capped" else "") + ") " +
                "complete=$complete breaker=$breakerTripped"
        }
    }

    /** Result of [runRereads]: reads [done] (of which [errors] failed transiently), and whether
     *  the time cap stopped the batch before the picked rows were all read. */
    internal data class RereadRun(val done: Int, val errors: Int, val capped: Boolean)

    /** The rows a pass re-reads: all of [candidates] when they fit the [budget], else a RANDOM
     *  [budget] of them. Random, not "the first N": the walk meets folders in much the same
     *  order every pass, and a row whose read keeps failing transiently is never stamped — the
     *  same N slow rows would eat every pass's budget and the rest would never be reached. */
    internal fun <C> pickRereads(candidates: List<C>, budget: Int, random: kotlin.random.Random): List<C> =
        if (candidates.size <= budget) candidates else candidates.shuffled(random).take(budget)

    /**
     * Run a pass's budgeted re-reads: at most [budget] of [candidates] ([pickRereads]),
     * [concurrency] at a time, and none started once [maxMs] has passed. [read] returns true
     * for a conclusive read, false for a transient failure (the row stays unstamped and is a
     * candidate again next pass). Rows that aren't picked, or that the time cap cuts off,
     * are simply not touched.
     */
    internal suspend fun <C> runRereads(
        candidates: List<C>,
        budget: Int,
        concurrency: Int,
        maxMs: Long,
        random: kotlin.random.Random = kotlin.random.Random.Default,
        nowMs: () -> Long = System::currentTimeMillis,
        read: suspend (C) -> Boolean,
    ): RereadRun {
        val picked = pickRereads(candidates, budget, random)
        val gate = Semaphore(concurrency)
        val deadline = nowMs() + maxMs
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val capped = java.util.concurrent.atomic.AtomicBoolean(false)
        coroutineScope {
            for (c in picked) {
                launch {
                    gate.withPermit {
                        if (nowMs() >= deadline) { capped.set(true); return@withPermit }
                        if (!read(c)) errors.incrementAndGet()
                        done.incrementAndGet()
                    }
                }
            }
        }
        return RereadRun(done.get(), errors.get(), capped.get())
    }

    /** A tag read's row, and whether the read was conclusive (false = transient failure). */
    private class FileRead(val row: TrackEntity, val conclusive: Boolean)

    /** Read [smbPath]'s tags into an index row. [reused] = the row already indexed for it (a file
     *  changed in place, or one still missing a duration), null for a new file. */
    private fun trackFor(
        context: Context,
        share: SmbShare,
        smbPath: String,
        sizeBytes: Long,
        mtimeMs: Long?,
        reused: TrackEntity?,
    ): FileRead {
        val parts = smbPath.split('\\')
        val fileName = parts.last()
        val ext = fileName.substringAfterLast('.', "").uppercase()
        val uri = SmbSync.mediaUriFor(share, smbPath)
        // Read embedded tags (header bytes only, no download). A new file falls back to
        // filename/folder when it's untagged or unreadable. The session lets the duration
        // probe and tail-tag read share one SMB handle, sized from this listing (PLAY-15).
        val result = SmbProbe.session(uri, sizeBytes) { TagReader.readResult(context, uri) }
        val conclusive = result !is ReadResult.Error
        if (reused != null) return FileRead(rereadRow(reused, result, sizeBytes, mtimeMs, System.currentTimeMillis()), conclusive)
        val tags = result.valueOrNull()
        val row = TrackEntity(
            id = trackIdFor(SourceType.Smb, share.id, smbPath),
            title = tags?.title ?: fileName.substringBeforeLast('.'),
            artist = tags?.artist ?: parts.dropLast(2).lastOrNull(),
            albumTitle = tags?.album ?: parts.dropLast(1).lastOrNull(),
            albumArtist = tags?.albumArtist,
            year = tags?.year,
            trackNumber = tags?.trackNumber,
            durationMs = tags?.durationMs ?: 0L,
            bitrate = ext.ifEmpty { null },
            mimeType = null,
            sourceType = SourceType.Smb,
            sourceId = share.id,
            path = "${share.mountPath}/$smbPath".replace('\\', '/'),
            uri = uri,
            sizeBytes = sizeBytes,
            addedAtMs = System.currentTimeMillis(),
            fileMtimeMs = mtimeMs,
        )
        // A conclusive read is the same read a retag would do: stamp it, so a file that has no
        // tags/duration isn't re-read on every walk (see needsRescanRead) or by the next retag.
        return FileRead(LibraryRepository.retagStamped(row, result, System.currentTimeMillis()), conclusive)
    }

    /**
     * The row a re-read of an already-indexed file yields: [reused] (the stored row) with the
     * tags the read found patched over it — never the filename/folder fallbacks, which would
     * overwrite real tags for a file whose read came back Absent — plus the new listing and a
     * conclusive stamp.
     *
     * A failed (transient) read returns [reused] untouched, OLD size/mtime included: a changed
     * file then still looks changed to the next walk, which retries it, instead of its stored
     * title/artist/duration being replaced by filename-derived values that the new mtime would
     * make permanent. Pure, for tests.
     */
    internal fun rereadRow(
        reused: TrackEntity,
        result: ReadResult<TagReader.Tags>,
        sizeBytes: Long,
        mtimeMs: Long?,
        nowMs: Long,
    ): TrackEntity {
        if (result is ReadResult.Error) return reused
        val tags = result.valueOrNull()
        val row = reused.copy(
            title = tags?.title?.ifBlank { null } ?: reused.title,
            artist = tags?.artist?.ifBlank { null } ?: reused.artist,
            albumTitle = tags?.album?.ifBlank { null } ?: reused.albumTitle,
            albumArtist = tags?.albumArtist?.ifBlank { null } ?: reused.albumArtist,
            year = tags?.year ?: reused.year,
            trackNumber = tags?.trackNumber ?: reused.trackNumber,
            durationMs = tags?.durationMs?.takeIf { it > 0 } ?: reused.durationMs,
            sizeBytes = sizeBytes,
            fileMtimeMs = mtimeMs ?: reused.fileMtimeMs,
        )
        return LibraryRepository.retagStamped(row, result, nowMs)
    }
}

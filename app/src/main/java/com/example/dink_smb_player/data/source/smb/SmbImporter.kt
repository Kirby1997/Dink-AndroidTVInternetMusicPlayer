package com.example.dink_smb_player.data.source.smb

import android.content.Context
import com.example.dink_smb_player.data.index.SourceEntity
import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import com.example.dink_smb_player.data.model.SmbShare
import com.example.dink_smb_player.data.prefs.SmbCreds
import com.example.dink_smb_player.data.library.trackIdFor
import com.example.dink_smb_player.data.source.TagReader
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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
 * smbj blocks — [enumerate] switches to Dispatchers.IO itself.
 */
object SmbImporter {

    private const val MAX_DEPTH = 12
    /** Max concurrent directory listings. Listing is light (one round-trip, small
     *  response), so this can be wide to keep the walk fast. */
    private const val CONCURRENCY = 16
    /** Max concurrent TAG reads. Kept LOW and SEPARATE from listing: each spins a Media3
     *  extractor that buffers multi-MB chunks (M4A/MP4 pull the moov atom from end-of-file),
     *  so a fresh import of a big share would OOM at listing concurrency. Mirrors the
     *  rescan cap in [com.example.dink_smb_player.data.library.LibraryRepository]. */
    private const val TAG_CONCURRENCY = 6
    /** New-track batch size for mid-import incremental persistence. Bounds how much a
     *  crash/restart mid-walk can lose (≤ this many newest files re-read on resume). */
    private const val FLUSH_BATCH = 500

    /** Enumerate every configured import root and return the full track set for the
     *  share. The caller passes this to LibraryRepository.importSource, which upserts
     *  present + prunes absent, so a re-import naturally drops deleted files. */
    suspend fun enumerateImports(context: Context, share: SmbShare, creds: SmbCreds?, existing: Map<String, TrackEntity> = emptyMap()): Result<List<TrackEntity>> =
        enumerate(context, share, creds, share.importPaths.ifEmpty { listOf("") }, existing)

    /** Enumerate the given [roots] only (backslash smbPaths; "" = share root). Used
     *  for per-folder monitor refreshes that must not touch other imported folders.
     *  [context] reads embedded tags per file ([TagReader]). [existing] are the source's
     *  already-indexed rows by id: a file already present is reused as-is (keeping its
     *  tags), so only genuinely NEW files are tag-read — making re-import / monitor cheap.
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
    ): Result<List<TrackEntity>> = withContext(Dispatchers.IO) {
        runCatching {
            val disk = SmbClient.share(share.id, share.host, share.port, share.shareName, creds)
            val out = ConcurrentHashMap<String, TrackEntity>() // dedupe overlapping roots by id
            val gate = Semaphore(CONCURRENCY)
            val tagGate = Semaphore(TAG_CONCURRENCY)
            // Buffer newly tag-read tracks; drain to flushBatch every FLUSH_BATCH. Guarded
            // by a Mutex because the parallel walk produces tracks from many coroutines.
            val pending = ArrayList<TrackEntity>(FLUSH_BATCH)
            val flushLock = Mutex()
            val onNewTrack: suspend (TrackEntity) -> Unit = onNewTrack@{ track ->
                val sink = flushBatch ?: return@onNewTrack
                val batch = flushLock.withLock {
                    pending.add(track)
                    if (pending.size >= FLUSH_BATCH) {
                        val copy = ArrayList(pending); pending.clear(); copy
                    } else null
                }
                if (batch != null) sink(batch)
            }
            // Drop roots nested inside another monitored/imported root so an overlapping
            // pair like ["music", "music\Music"] doesn't walk the subtree twice.
            val topRoots = dropNestedRoots(roots)
            coroutineScope {
                for (root in topRoots) {
                    launch { walk(this, gate, tagGate, context, disk, share, root, 0, out, existing, onNewTrack) }
                }
            }
            // Flush the trailing partial batch so the last <FLUSH_BATCH new files are
            // persisted too (the caller's final importScoped re-upserts harmlessly).
            if (flushBatch != null) {
                val tail = flushLock.withLock { val c = ArrayList(pending); pending.clear(); c }
                if (tail.isNotEmpty()) flushBatch(tail)
            }
            out.values.toList()
        }
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

    private suspend fun walk(
        scope: CoroutineScope,
        gate: Semaphore,
        tagGate: Semaphore,
        context: Context,
        disk: DiskShare,
        share: SmbShare,
        smbPath: String,
        depth: Int,
        out: MutableMap<String, TrackEntity>,
        existing: Map<String, TrackEntity>,
        onNewTrack: suspend (TrackEntity) -> Unit,
    ) {
        if (depth > MAX_DEPTH) return
        // Hold a permit only for the list() round-trip, not while waiting on children,
        // so the bounded pool never deadlocks on a deep tree.
        val entries: List<FileIdBothDirectoryInformation> = gate.withPermit {
            runCatching { disk.list(smbPath) }.getOrNull()
        } ?: return // unreadable folder — skip, don't fail the whole import
        val jobs = ArrayList<Job>(entries.size)
        for (entry in entries) {
            val name = entry.fileName
            if (name == "." || name == "..") continue
            val isDir = (entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            val child = if (smbPath.isEmpty()) name else "$smbPath\\$name"
            if (isDir) {
                jobs += scope.launch { walk(scope, gate, tagGate, context, disk, share, child, depth + 1, out, existing, onNewTrack) }
            } else if (SmbSync.isAudio(name)) {
                val id = trackIdFor(SourceType.Smb, share.id, child)
                val reused = existing[id]
                if (reused != null) {
                    // Already indexed → keep the row + its tags, no tag read.
                    out[id] = reused
                } else {
                    val sizeBytes = entry.endOfFile
                    // Tag reads use the SEPARATE low gate so heavy extractor buffers can't
                    // pile up (OOM) and don't starve directory listing on the wide gate.
                    jobs += scope.launch {
                        val track = tagGate.withPermit { trackFor(context, share, child, sizeBytes) }
                        out[id] = track
                        onNewTrack(track)
                    }
                }
            }
        }
        jobs.joinAll()
    }

    private fun trackFor(context: Context, share: SmbShare, smbPath: String, sizeBytes: Long): TrackEntity {
        val parts = smbPath.split('\\')
        val fileName = parts.last()
        val ext = fileName.substringAfterLast('.', "").uppercase()
        val uri = SmbSync.mediaUriFor(share, smbPath)
        // New file → read embedded tags (header bytes only, no download). Falls back to
        // filename/folder when the file is untagged or unreadable.
        val tags = TagReader.read(context, uri)
        return TrackEntity(
            id = trackIdFor(SourceType.Smb, share.id, smbPath),
            title = tags?.title ?: fileName.substringBeforeLast('.'),
            artist = tags?.artist ?: parts.dropLast(2).lastOrNull(),
            albumTitle = tags?.album ?: parts.dropLast(1).lastOrNull(),
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
        )
    }
}

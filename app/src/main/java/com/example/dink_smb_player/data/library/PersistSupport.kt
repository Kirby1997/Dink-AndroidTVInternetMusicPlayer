package com.example.dink_smb_player.data.library

import kotlinx.coroutines.delay
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Shared disk-safety helpers for [LibraryStore] and [PlaylistStore]: fsync'd atomic
 * replace, known-good backup copies, and timestamped preservation of unreadable files.
 */
internal object SafeFiles {

    /** How many timestamped corrupt copies to keep per file (oldest pruned). Library
     *  snapshots are multi-MB, so this is bounded rather than unlimited. */
    const val KEEP_CORRUPT = 3

    /**
     * Write [target] atomically: stream into a sibling temp file, flush + fsync it, then
     * ATOMIC_MOVE over the target. Without the fsync a power loss shortly after the
     * rename can leave a zero-length/torn file on ext4/f2fs even though the rename landed.
     * On failure the partial temp file is deleted (a multi-MB orphan otherwise sits in
     * filesDir until the next successful write) and the error rethrown.
     */
    fun writeAtomic(target: File, write: (OutputStream) -> Unit) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                val out = BufferedOutputStream(fos)
                write(out)
                out.flush()
                fos.fd.sync()
            }
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
    }

    /** Copy [src] to [dst] atomically (via temp + fsync + move), so a crash mid-copy
     *  can never leave a torn backup that we'd later trust. */
    fun copyAtomic(src: File, dst: File) {
        writeAtomic(dst) { out -> src.inputStream().use { it.copyTo(out) } }
    }

    /**
     * Move an unreadable [file] aside as `<stem>.corrupt-<timestamp>.<ext>` (never
     * overwriting an earlier copy) and prune to the newest [KEEP_CORRUPT]. Moving rather
     * than copying means the next boot sees the file as missing instead of re-failing on
     * it forever; the bytes stay on disk for recovery either way.
     */
    fun quarantine(file: File, nowMs: Long = System.currentTimeMillis()): File? {
        if (!file.exists()) return null
        val (stem, ext) = splitName(file.name)
        var dst = File(file.parentFile, "$stem.corrupt-$nowMs$ext")
        var n = 1
        while (dst.exists()) dst = File(file.parentFile, "$stem.corrupt-$nowMs-${n++}$ext")
        val moved = runCatching {
            Files.move(file.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.isSuccess || runCatching { file.copyTo(dst); file.delete() }.isSuccess
        pruneCorrupt(file)
        return if (moved) dst else null
    }

    /** Timestamped corrupt copies of [file], newest first. */
    fun corruptCopies(file: File): List<File> {
        val (stem, ext) = splitName(file.name)
        val prefix = "$stem.corrupt-"
        // Order by the timestamp (+ collision suffix) in the name — a moved file keeps its
        // original mtime, so lastModified() would say when it was written, not quarantined.
        fun key(f: File): Pair<Long, Int> {
            val parts = f.name.removePrefix(prefix).removeSuffix(ext).split('-')
            return (parts.getOrNull(0)?.toLongOrNull() ?: 0L) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
        }
        return file.parentFile?.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(ext) }
            ?.sortedWith(compareByDescending<File> { key(it).first }.thenByDescending { key(it).second })
            .orEmpty()
    }

    private fun pruneCorrupt(file: File) {
        corruptCopies(file).drop(KEEP_CORRUPT).forEach { runCatching { it.delete() } }
    }

    private fun splitName(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
    }

    /** Load failures that may succeed on retry (I/O hiccup, heap pressure) as opposed to
     *  a file whose bytes don't parse. Treating OOM as corruption is what used to disable
     *  persistence (and quarantine a perfectly good index) on a memory-tight boot. */
    fun isTransient(t: Throwable): Boolean = t is IOException || t is OutOfMemoryError
}

/**
 * One JSON file with a known-good backup, guarded against the two ways it has lost data:
 * a torn/partial write (fsync + atomic replace) and an unreadable file being overwritten
 * with an empty state (distinct [Load] outcomes, so the owner can gate its writes).
 *
 * Backup rotation: `<name>.bak.<ext>` is refreshed at most once per process, on the first
 * [save], by copying the current file — but only when that file is known good (it just
 * loaded cleanly, or this process wrote it). So the backup is always a snapshot that
 * parsed or was written whole, never a copy of a corrupt file. Blocking; callers own
 * threading and serialization.
 */
internal class GuardedJsonFile<T>(
    val file: File,
    private val tag: String,
    private val decode: (InputStream) -> T,
    private val encode: (T, OutputStream) -> Unit,
) {
    sealed interface Load<out T> {
        data class Ok<T>(val value: T, val fromBackup: Boolean = false) : Load<T>
        data object Missing : Load<Nothing>
        /** [quarantined]: the unreadable file(s) were moved aside, so nothing readable-later
         *  is left at the main/backup paths for a write to clobber. */
        data class Corrupt(val error: Throwable, val quarantined: Boolean = false) : Load<Nothing>
        data class Transient(val error: Throwable) : Load<Nothing>
    }

    val backup: File = run {
        val dot = file.name.lastIndexOf('.')
        val name = if (dot <= 0) "${file.name}.bak" else file.name.substring(0, dot) + ".bak" + file.name.substring(dot)
        File(file.parentFile, name)
    }

    @Volatile private var mainKnownGood = false
    @Volatile private var backupRotated = false

    /** Loads the main file, falling back to the backup when the main file is missing or
     *  unreadable. An unreadable file is quarantined ([SafeFiles.quarantine]); a
     *  transient failure touches nothing. */
    fun load(): Load<T> {
        if (file.exists()) {
            when (val r = parse(file)) {
                is Load.Ok -> { mainKnownGood = true; return r }
                is Load.Transient -> return r
                is Load.Corrupt -> {
                    val kept = SafeFiles.quarantine(file)
                    android.util.Log.e(tag, "${file.name} unreadable; preserved as ${kept?.name}", r.error)
                    return fromBackup() ?: r.copy(quarantined = !file.exists() && !backup.exists())
                }
                Load.Missing -> Unit
            }
        }
        return fromBackup() ?: Load.Missing
    }

    /** null = no usable backup (absent, or itself unreadable → quarantined). */
    private fun fromBackup(): Load<T>? {
        if (!backup.exists()) return null
        return when (val r = parse(backup)) {
            is Load.Ok -> {
                android.util.Log.w(tag, "restored from ${backup.name}")
                Load.Ok(r.value, fromBackup = true)
            }
            is Load.Transient -> r
            is Load.Corrupt -> {
                val kept = SafeFiles.quarantine(backup)
                android.util.Log.e(tag, "${backup.name} unreadable too; preserved as ${kept?.name}", r.error)
                null
            }
            Load.Missing -> null
        }
    }

    private fun parse(f: File): Load<T> = try {
        Load.Ok(f.inputStream().buffered().use(decode))
    } catch (t: Throwable) {
        if (SafeFiles.isTransient(t)) Load.Transient(t) else Load.Corrupt(t)
    }

    /** Loads, retrying transient failures with a short backoff. */
    suspend fun loadWithRetry(attempts: Int = 3, backoffMs: Long = 300): Load<T> {
        var last: Load<T> = load()
        var n = 1
        while (last is Load.Transient && n < attempts) {
            android.util.Log.w(tag, "load of ${file.name} failed transiently (attempt $n), retrying", last.error)
            delay(backoffMs * n)
            last = load()
            n++
        }
        return last
    }

    /** Returns true only once [value] is durably on disk. */
    fun save(value: T): Boolean = saveResult(value).isSuccess

    /** As [save], with the failure. */
    fun saveResult(value: T): Result<Unit> = runCatching {
        if (!backupRotated && mainKnownGood && file.exists()) {
            // A failed rotation must not block the real write.
            runCatching { SafeFiles.copyAtomic(file, backup) }
                .onFailure { android.util.Log.w(tag, "backup rotation failed", it) }
            backupRotated = true
        }
        SafeFiles.writeAtomic(file) { out -> encode(value, out) }
        mainKnownGood = true
    }.onFailure { t -> android.util.Log.e(tag, "save of ${file.name} failed", t) }
}

/**
 * Process-level "may we write this file?" gate shared by the library index and
 * playlists. The invariant it protects: never overwrite an on-disk file with an
 * in-memory state that wasn't loaded from it.
 *
 * - It starts [State.NotRestored]: nothing is loaded yet, so nothing may be written. The
 *   owner restores first (see [needsRestore]) — an early writer (a boot refresh, a worker,
 *   a media-button process) persisting the in-memory state mid-restore is what used to
 *   write a local-only index over the SMB library.
 * - [onLoaded] (Ok or Missing) enables writes.
 * - [onCorrupt] disables them for the process. A scoped import is not authoritative — it
 *   only knows its own folder — and there is no whole-library reindex, so nothing re-enables
 *   them; the next launch's restore tries again. If the unreadable file was quarantined
 *   (moved aside), nothing is left to clobber, so writes stay enabled — otherwise everything
 *   imported in that session would be lost on restart.
 * - [onTransientFailure] disables them too, but marks the state [retryable]: the owner
 *   retries the load later, and a successful retry re-enables writes.
 */
internal class PersistGate {
    enum class State { NotRestored, Enabled, DisabledCorrupt, DisabledTransient }

    @Volatile var state: State = State.NotRestored
        private set

    val canPersist: Boolean get() = state == State.Enabled
    val retryable: Boolean get() = state == State.DisabledTransient
    /** The owner must (re)run its restore before a write can be allowed. */
    val needsRestore: Boolean get() = state == State.NotRestored || state == State.DisabledTransient

    fun onLoaded() { state = State.Enabled }
    fun onCorrupt(quarantined: Boolean = false) {
        state = if (quarantined) State.Enabled else State.DisabledCorrupt
    }
    fun onTransientFailure() { state = State.DisabledTransient }

    /** Test hook: back to a fresh-process state. */
    fun resetForTest() { state = State.NotRestored }
}

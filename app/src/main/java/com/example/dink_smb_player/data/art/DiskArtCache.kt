package com.example.dink_smb_player.data.art

import java.io.File
import java.io.OutputStream

/**
 * The on-disk half of [AlbumArtCache]: downscaled covers as `<hash>.jpg` plus `<hash>.none`
 * negative markers, in a directory the system may clear at will (cacheDir).
 *
 *  - Size-capped LRU: a disk hit touches the file's mtime, and each write evicts the
 *    least-recently-used files once the total passes [maxBytes] (down to ~90%, so a full
 *    cache doesn't rescan the directory on every write).
 *  - `.none` markers expire after [noneTtlMs]: "this album has no art" is re-checked
 *    occasionally, so art added to the files later (or a marker from a bad read) heals.
 *
 * Plain file I/O, no Android APIs — unit-testable against a temp dir. Blocking: call off-main.
 */
internal class DiskArtCache(
    private val dir: File,
    private val maxBytes: Long,
    private val noneTtlMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Running total of bytes on disk; -1 = unknown (recount on next write). Guarded by `this`. */
    private var knownBytes = -1L

    private fun jpg(hash: String) = File(dir, "$hash.jpg")
    private fun none(hash: String) = File(dir, "$hash.none")

    /** The cached cover's bytes (touching it as recently used), or null. */
    fun read(hash: String): ByteArray? {
        val f = jpg(hash)
        if (!f.exists()) return null
        return runCatching { f.readBytes() }.getOrNull()?.also { f.setLastModified(clock()) }
    }

    /** Drop a corrupt cover. */
    fun delete(hash: String) {
        runCatching { jpg(hash).delete() }
        synchronized(this) { knownBytes = -1L }
    }

    /** True while a fresh "no art" marker exists; an expired marker is deleted (→ re-probe). */
    fun isKnownAbsent(hash: String): Boolean {
        val f = none(hash)
        if (!f.exists()) return false
        if (clock() - f.lastModified() < noneTtlMs) return true
        runCatching { f.delete() }
        return false
    }

    /** Record "this album has no art" for [noneTtlMs]. */
    fun markAbsent(hash: String) {
        runCatching {
            dir.mkdirs()
            val f = none(hash)
            f.createNewFile()
            f.setLastModified(clock())
        }
    }

    /** Atomically store a cover via [write] (unique temp file + rename), then enforce the cap.
     *  Returns false when the write failed (nothing is left behind). */
    fun write(hash: String, write: (OutputStream) -> Unit): Boolean {
        val target = jpg(hash)
        val ok = runCatching {
            dir.mkdirs()
            // Unique temp name: two resolves of the same key (e.g. one racing a clear) must
            // never interleave writes into one shared temp file.
            val tmp = File.createTempFile("art-$hash-", ".tmp", dir)
            try {
                tmp.outputStream().use(write)
                if (!tmp.renameTo(target)) {
                    target.delete()
                    check(tmp.renameTo(target)) { "rename failed" }
                }
            } finally {
                tmp.delete() // no-op after a successful rename
            }
        }.isSuccess
        if (!ok) return false
        target.setLastModified(clock())
        runCatching { none(hash).delete() }
        synchronized(this) {
            if (knownBytes >= 0) knownBytes += target.length()
            if (knownBytes < 0 || knownBytes > maxBytes) trimLocked()
        }
        return true
    }

    /** Delete everything (Settings → Clear art cache). */
    fun clear() {
        synchronized(this) {
            dir.listFiles()?.forEach { runCatching { it.delete() } }
            knownBytes = 0L
        }
    }

    /** Recount, drop expired markers and stale temp files, then evict least-recently-used
     *  files until under ~90% of the cap. */
    private fun trimLocked() {
        val now = clock()
        val files = dir.listFiles()?.toMutableList() ?: run { knownBytes = 0L; return }
        files.removeAll { f ->
            val stale = (f.name.endsWith(".none") && now - f.lastModified() >= noneTtlMs) ||
                (f.name.endsWith(".tmp") && now - f.lastModified() >= STALE_TMP_MS)
            stale && f.delete()
        }
        var total = files.sumOf { it.length() }
        if (total > maxBytes) {
            val floor = maxBytes * 9 / 10
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= floor) break
                if (f.name.endsWith(".tmp")) continue // may be an in-flight write
                val len = f.length()
                if (f.delete()) total -= len
            }
        }
        knownBytes = total
    }

    private companion object {
        const val STALE_TMP_MS = 60 * 60 * 1000L
    }
}

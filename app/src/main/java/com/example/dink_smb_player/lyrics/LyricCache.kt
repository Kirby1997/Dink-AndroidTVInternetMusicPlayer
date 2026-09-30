package com.example.dink_smb_player.lyrics

import android.util.Log
import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Resolved-lyrics cache (LYR-9): an in-memory LRU in front of a small bounded disk cache
 * (`cacheDir/lyrics`, one JSON file per track). Replaying or skipping back to a track no
 * longer re-runs the sidecar listing, the embedded-tag read or a dozen network lookups.
 *
 * - Key: song id + artist|title|duration ([keyFor]) — enrichment that fixes a title or
 *   duration naturally misses the old entry.
 * - Each entry records the provider-config [LyricConfig.fingerprint] it was resolved
 *   under; a lookup under a different config is a miss. [clear] (called when the user
 *   flips the master switch or a provider toggle) also drops everything outright.
 * - Negative results ([LyricResult.None]) expire after [negativeTtlMs] (~7 days) so a
 *   track whose lyrics appear upstream later gets another try. Positive results and
 *   [LyricResult.Instrumental] don't expire; the disk cache trims oldest-first past
 *   [maxDiskBytes].
 *
 * Thread-safe (all state under one lock). Disk I/O is small and done on the caller's
 * (IO) thread. [dir] null = memory only.
 */
class LyricCache(
    private val dir: File?,
    private val maxDiskBytes: Long = DEFAULT_MAX_DISK_BYTES,
    private val memEntries: Int = DEFAULT_MEM_ENTRIES,
    private val negativeTtlMs: Long = NEGATIVE_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Serializable
    internal data class Entry(
        val fp: String,
        val savedAt: Long,
        val kind: String,
        val lines: List<LyricLine> = emptyList(),
    )

    private val lock = Any()
    private val mem = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > memEntries
    }

    fun get(key: String, fingerprint: String): LyricResult? = synchronized(lock) {
        val entry = mem[key] ?: readDisk(key)?.also { mem[key] = it } ?: return null
        if (entry.fp != fingerprint || isExpired(entry)) {
            mem.remove(key)
            fileFor(key)?.delete()
            return null
        }
        entry.toResult()
    }

    /** [persist] = false keeps the entry in memory only (this process). */
    fun put(key: String, fingerprint: String, result: LyricResult, persist: Boolean = true) = synchronized(lock) {
        val entry = Entry(
            fp = fingerprint,
            savedAt = clock(),
            kind = kindOf(result),
            lines = when (result) {
                is LyricResult.Synced -> result.lines
                is LyricResult.Plain -> result.lines
                else -> emptyList()
            },
        )
        mem[key] = entry
        if (persist) writeDisk(key, entry) else fileFor(key)?.delete()
    }

    /** Drop every entry, memory and disk — provider settings changed. */
    fun clear() = synchronized(lock) {
        mem.clear()
        dir?.listFiles()?.forEach { it.delete() }
        Unit
    }

    private fun isExpired(e: Entry) = e.kind == KIND_NONE && clock() - e.savedAt > negativeTtlMs

    private fun Entry.toResult(): LyricResult = when (kind) {
        KIND_SYNCED -> LyricResult.Synced(lines)
        KIND_PLAIN -> LyricResult.Plain(lines)
        KIND_INSTRUMENTAL -> LyricResult.Instrumental
        else -> LyricResult.None
    }

    private fun kindOf(r: LyricResult) = when (r) {
        is LyricResult.Synced -> KIND_SYNCED
        is LyricResult.Plain -> KIND_PLAIN
        LyricResult.Instrumental -> KIND_INSTRUMENTAL
        LyricResult.None -> KIND_NONE
    }

    private fun fileFor(key: String): File? = dir?.let { File(it, sha1(key) + ".json") }

    private fun readDisk(key: String): Entry? {
        val f = fileFor(key) ?: return null
        if (!f.isFile) return null
        return runCatching { json.decodeFromString(Entry.serializer(), f.readText()) }
            .onSuccess { f.setLastModified(clock()) } // LRU order on disk
            .onFailure { f.delete() }
            .getOrNull()
    }

    private fun writeDisk(key: String, entry: Entry) {
        val d = dir ?: return
        val f = fileFor(key) ?: return
        runCatching {
            d.mkdirs()
            val tmp = File(d, f.name + ".tmp")
            tmp.writeText(json.encodeToString(Entry.serializer(), entry))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            f.setLastModified(clock())
            trimDisk(d)
        }.onFailure { Log.w(TAG, "lyric cache write failed: ${it.message}") }
    }

    /** Past [maxDiskBytes], delete least-recently-used files down to 80% of it. */
    private fun trimDisk(d: File) {
        val files = d.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxDiskBytes) return
        val target = maxDiskBytes * 8 / 10
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= target) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    companion object {
        private const val TAG = "LyricCache"
        const val DEFAULT_MAX_DISK_BYTES = 5L * 1024 * 1024
        const val DEFAULT_MEM_ENTRIES = 64
        const val NEGATIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000

        internal const val KIND_SYNCED = "synced"
        internal const val KIND_PLAIN = "plain"
        internal const val KIND_INSTRUMENTAL = "instrumental"
        internal const val KIND_NONE = "none"

        private val json = Json { ignoreUnknownKeys = true }

        fun keyFor(song: Song): String =
            "${song.id}|${song.artist.trim().lowercase()}|${song.title.trim().lowercase()}|${song.durationSec}"

        private const val HEX = "0123456789abcdef"

        private fun sha1(s: String): String {
            val bytes = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return sb.toString()
        }
    }
}

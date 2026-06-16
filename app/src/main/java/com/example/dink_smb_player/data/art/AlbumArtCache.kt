package com.example.dink_smb_player.data.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.example.dink_smb_player.data.model.Song
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Lazy, cached store of EMBEDDED cover art, keyed per album. Real tracks carry no art in
 * the index, so the UI shows procedural art immediately and asks here for a real cover;
 * the first request for an album extracts it ([ArtExtractor], over the network, header
 * bytes only), downscales + caches it to disk, and a recomposition swaps it in.
 *
 * Why lazy (not at import): extracting art for 25k tracks up front would be a network +
 * storage storm. Here we only ever fetch covers for albums the user actually looks at.
 *
 * Three states per key:
 *  - in [mem] (decoded bitmap) — instant.
 *  - on disk as `<hash>.jpg` (downscaled) — decoded on demand.
 *  - on disk as `<hash>.none` — a negative marker so an art-less album isn't re-probed
 *    over the network on every visit / relaunch.
 *
 * Keyed on artist+album when known (so two different "Greatest Hits" don't collide),
 * else the track id.
 */
object AlbumArtCache {

    /** Decoded covers. Count-bounded (covers are downscaled to [MAX_DIM]); only on-screen
     *  albums populate it, so a small cap is plenty and keeps heap flat. */
    private val mem = LruCache<String, Bitmap>(32)

    /** One in-flight resolve per key — concurrent cards for the same album share the work. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Caps concurrent NETWORK extractions so a fast scroll through thousands of distinct
     *  albums can't open hundreds of SMB reads at once. Memory/disk hits never reach it. */
    private val extractGate = Semaphore(4)

    private const val MAX_DIM = 512

    fun keyFor(song: Song): String {
        val album = song.albumTitle?.takeIf { it.isNotBlank() }
        return if (album != null) "${song.artist}|$album" else song.id
    }

    /** Main-thread-safe synchronous peek — memory only, never touches disk or network. */
    fun peek(key: String): Bitmap? = mem.get(key)

    /**
     * Return the album's cover, resolving through memory → disk → network extraction.
     * Suspends on IO work; safe to call concurrently for the same key (deduped). Returns
     * null when the album has no embedded art (and records a negative marker so it isn't
     * re-probed). [sampleUri] is any track of the album to read the picture from.
     */
    suspend fun resolve(context: Context, key: String, sampleUri: String): Bitmap? {
        mem.get(key)?.let { return it }
        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            // Re-check after acquiring: another coroutine may have just resolved it.
            mem.get(key)?.let { return@withLock it }
            val dir = cacheDir(context)
            val hash = hash(key)
            val jpg = File(dir, "$hash.jpg")
            val none = File(dir, "$hash.none")
            if (none.exists()) return@withLock null
            if (jpg.exists()) {
                decodeScaled(jpg.readBytes())?.let { mem.put(key, it); return@withLock it }
                // Corrupt cache file — fall through to re-extract.
                runCatching { jpg.delete() }
            }
            // Gate only the network read — disk/mem hits above already returned. The
            // permit is held across the blocking extract; cancellation while waiting to
            // acquire (row scrolled off) is honoured at the suspending acquire. Embedded
            // picture first; if none, fall back to a sibling cover.jpg / folder.jpg.
            val bytes = extractGate.withPermit {
                ArtExtractor.extract(context, sampleUri)
                    ?: ArtExtractor.extractFolderImage(context, sampleUri)
            }
            if (bytes == null) {
                runCatching { none.createNewFile() }
                return@withLock null
            }
            val bmp = decodeScaled(bytes)
            if (bmp == null) {
                runCatching { none.createNewFile() }
                return@withLock null
            }
            // Persist the downscaled cover so it survives relaunch without re-reading the NAS.
            runCatching {
                File(dir, "$hash.jpg.tmp").also { tmp ->
                    tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    tmp.renameTo(jpg)
                }
            }
            mem.put(key, bmp)
            bmp
        }
    }

    private fun cacheDir(context: Context): File =
        File(context.applicationContext.filesDir, "artcache").apply { mkdirs() }

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Decode [bytes] downscaled so the larger side is ~[MAX_DIM] — covers are tiny on a
     *  card, and full-res JPEGs would blow up the bitmap cache. */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= MAX_DIM && h / (sample * 2) >= MAX_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }
}

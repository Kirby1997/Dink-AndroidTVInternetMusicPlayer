package com.example.dink_smb_player.data.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.example.dink_smb_player.data.index.LibraryGrouping
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.data.source.ReadResult
import com.example.dink_smb_player.data.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

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
 *    over the network on every visit / relaunch. Written ONLY when the file genuinely has
 *    no art ([ReadResult.Absent]) and expires after [NONE_TTL_MS]; a transient failure
 *    (NAS down, timeout) writes nothing and only backs off in memory for [ERROR_BACKOFF_MS].
 *
 * The disk cache lives in cacheDir (the system may reclaim it; excluded from Auto Backup's
 * quota) and is LRU-capped at [DISK_CAP_BYTES] — see [DiskArtCache].
 */
object AlbumArtCache {

    /** Decoded covers, bounded by BYTES (~1/8 of the heap) — each is at most
     *  [MAX_DIM]² ARGB (~1 MB), and only on-screen albums populate it. */
    private val mem = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8 / 1024).toInt().coerceAtLeast(4 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    /** One in-flight resolve per key — concurrent cards for the same album share the work.
     *  Entries are dropped once idle, so the map doesn't grow per album ever viewed. */
    private val locks = KeyedMutex()

    /** Keys whose last extraction failed transiently → wall-clock time to retry after.
     *  Keeps a down NAS from being hammered on every recomposition/scroll without writing a
     *  (wrong) persistent "no art" marker. Bounded; losing an entry only means an early retry. */
    private val errorUntil = LruCache<String, Long>(512)

    /** Caps concurrent NETWORK extractions so a fast scroll through thousands of distinct
     *  albums can't open hundreds of SMB reads at once. Memory/disk hits never reach it. */
    private val extractGate = Semaphore(4)

    private const val MAX_DIM = 512
    private const val DISK_CAP_BYTES = 50L * 1024 * 1024
    private const val NONE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val ERROR_BACKOFF_MS = 60_000L

    @Volatile private var disk: DiskArtCache? = null

    /** Cache key for [song]'s album. Delegates to [artKey] — the one place album identity for
     *  art is decided. */
    fun keyFor(song: Song): String =
        artKey(song.id, song.artistKey, song.albumKey, song.artist, song.albumTitle)

    /**
     * Album identity for art. A current album key is already `albumArtist|title` (LIB-6), so it
     * IS the art key: one cover per album, compilations included. A pre-LIB-6 title-only key (a
     * Song mapped before the one-time recompute) is combined with the track's artist key into
     * the same `artist|title` shape — which is also the format keys had before LIB-6, so covers
     * already on disk keep their keys. Rows without precomputed keys fall back to the raw names
     * lowercased, and to the track id when the album is unknown.
     */
    internal fun artKey(id: String, artistKey: String?, albumKey: String?, artist: String?, albumTitle: String?): String {
        albumKey?.takeIf { LibraryGrouping.albumArtistKeyOf(it) != null }?.let { return it }
        val album = albumKey?.takeIf { it.isNotBlank() }
            ?: albumTitle?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return id
        val by = artistKey?.takeIf { it.isNotBlank() } ?: artist?.trim()?.lowercase().orEmpty()
        return "$by|$album"
    }

    /** Main-thread-safe synchronous peek — memory only, never touches disk or network. */
    fun peek(key: String): Bitmap? = mem.get(key)

    /**
     * Return the album's cover, resolving through memory → disk → network extraction.
     * Suspends on IO work; safe to call concurrently for the same key (deduped). Returns
     * null when the album has no art (recording an expiring negative marker so it isn't
     * re-probed) or when the read failed transiently (nothing recorded; retried after a short
     * in-memory backoff). [sampleUri] is any track of the album to read the picture from.
     */
    suspend fun resolve(context: Context, key: String, sampleUri: String): Bitmap? {
        mem.get(key)?.let { return it }
        return locks.withLock(key) {
            // Re-check after acquiring: another coroutine may have just resolved it.
            mem.get(key)?.let { return@withLock it }
            val disk = disk(context)
            val hash = hash(key)
            if (disk.isKnownAbsent(hash)) return@withLock null
            disk.read(hash)?.let { bytes ->
                decodeScaled(bytes)?.let { mem.put(key, it); return@withLock it }
                // Corrupt cache file — fall through to re-extract.
                disk.delete(hash)
            }
            errorUntil.get(key)?.let { if (System.currentTimeMillis() < it) return@withLock null }
            // Gate only the network read — disk/mem hits above already returned. The
            // permit is held across the blocking extract; cancellation while waiting to
            // acquire (row scrolled off) is honoured at the suspending acquire. Embedded
            // picture first; if none, fall back to a sibling cover.jpg / folder.jpg.
            val result = extractGate.withPermit {
                when (val embedded = ArtExtractor.extract(context, sampleUri)) {
                    is ReadResult.Found -> embedded
                    else -> when (val folder = ArtExtractor.extractFolderImage(context, sampleUri)) {
                        is ReadResult.Found -> folder
                        // Either read failing transiently means "unknown", not "no art".
                        else -> if (embedded is ReadResult.Error) embedded else folder
                    }
                }
            }
            val bytes = when (result) {
                is ReadResult.Found -> result.value
                is ReadResult.Error -> {
                    errorUntil.put(key, System.currentTimeMillis() + ERROR_BACKOFF_MS)
                    return@withLock null
                }
                ReadResult.Absent -> {
                    disk.markAbsent(hash)
                    return@withLock null
                }
            }
            errorUntil.remove(key)
            val bmp = decodeScaled(bytes)
            if (bmp == null) {
                // Picture present but undecodable — a property of the file, not a transient.
                disk.markAbsent(hash)
                return@withLock null
            }
            // Persist the downscaled cover so it survives relaunch without re-reading the NAS.
            disk.write(hash) { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            mem.put(key, bmp)
            bmp
        }
    }

    /** Settings → "Clear art cache": drop every cached cover and "no art" marker, so each
     *  album is re-probed on its next view. */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        mem.evictAll()
        errorUntil.evictAll()
        disk(context).clear()
    }

    private fun disk(context: Context): DiskArtCache = disk ?: synchronized(this) {
        disk ?: run {
            val app = context.applicationContext
            // One-time migration: the cache used to live (unbounded) in filesDir, where it
            // counted against Auto Backup's quota. Drop it — covers re-extract lazily.
            runCatching { File(app.filesDir, "artcache").takeIf { it.exists() }?.deleteRecursively() }
            DiskArtCache(File(app.cacheDir, "artcache"), DISK_CAP_BYTES, NONE_TTL_MS).also { disk = it }
        }
    }

    /** On-disk file name for [key]: sha1 hex. Must stay stable — it names covers already cached. */
    internal fun hash(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8)).toHex()

    /** Decode [bytes] so the larger side is exactly [MAX_DIM] (never upscaled): a cheap
     *  power-of-two inSampleSize decode first, then one exact scale — covers are tiny on a
     *  card, and full-res JPEGs would blow up the bitmap cache. */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(w, h, MAX_DIM) }
        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
            ?: return null
        val (tw, th) = targetSize(decoded.width, decoded.height, MAX_DIM)
        if (tw == decoded.width && th == decoded.height) return decoded
        val scaled = runCatching { Bitmap.createScaledBitmap(decoded, tw, th, true) }.getOrNull() ?: return decoded
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** Largest power-of-two sample that keeps the larger side >= [maxDim] (the final exact
     *  scale then only ever shrinks, preserving quality). */
    internal fun sampleSizeFor(w: Int, h: Int, maxDim: Int): Int {
        val longest = maxOf(w, h)
        var sample = 1
        while (longest / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    /** [w]×[h] scaled so the larger side is [maxDim], aspect kept; unchanged if already within. */
    internal fun targetSize(w: Int, h: Int, maxDim: Int): Pair<Int, Int> {
        val longest = maxOf(w, h)
        if (longest <= maxDim) return w to h
        val scale = maxDim.toDouble() / longest
        return maxOf(1, Math.round(w * scale).toInt()) to maxOf(1, Math.round(h * scale).toInt())
    }
}

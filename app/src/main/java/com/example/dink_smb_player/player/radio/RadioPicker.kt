package com.example.dink_smb_player.player.radio

import com.example.dink_smb_player.data.index.GenreKeys
import com.example.dink_smb_player.data.index.LibraryGrouping
import com.example.dink_smb_player.data.model.Song
import kotlin.math.abs
import kotlin.random.Random

/** A song the station is built around, and how strongly it pulls (original seed 1.0;
 *  recently played tracks less — that's what makes the station drift). */
data class WeightedSeed(val song: Song, val weight: Double)

/**
 * Radio track selection. Pure (no Android, no I/O) — call off the main thread: it scores
 * the whole library against every seed.
 *
 * Score per seed: same artist +5, genre overlap +4, same album artist +3, year within ±3 / ±8
 * +2 / +1, same small parent / grandparent folder +2 / +1; summed with seed weights. The top
 * [POOL_SIZE] are sampled weighted by score², about a tenth of the slots are uniform
 * wildcards (they also fill any shortfall), and diversity rules keep one artist from taking
 * over: ≤ [MAX_PER_ARTIST] per batch, never the same artist or album twice in a row.
 */
object RadioPicker {

    internal const val POOL_SIZE = 200
    internal const val MAX_PER_ARTIST = 3
    /** Folder-signal caps: a parent holding more songs than this is a dump folder or share
     *  root, not an album/artist folder, so sharing it says nothing about similarity. */
    internal const val MAX_PARENT_FOLDER = 300
    internal const val MAX_GRANDPARENT_FOLDER = 1500

    private val GENERIC_ARTISTS = setOf("", "unknown", "unknownartist", "variousartists", "va")

    private class Features(
        val artist: String,
        val album: String?,
        val albumArtist: String?,
        val genres: Set<String>,
        val year: Int?,
        val parent: String?,
        val grandparent: String?,
    )

    private fun featuresOf(song: Song): Features {
        val artist = song.artistKey ?: LibraryGrouping.normKey(song.artist)
        val album = song.albumKey ?: song.albumTitle?.let { LibraryGrouping.fallbackAlbumKey(song.artist, it) }
        val albumArtist = album?.let { LibraryGrouping.albumArtistKeyOf(it) }?.takeUnless { it in GENERIC_ARTISTS }
        val dirs = song.sourcePath.replace('\\', '/').split('/').filter { it.isNotBlank() }.dropLast(1)
        return Features(
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genres = GenreKeys.keysOf(song.genre),
            year = song.year?.takeIf { it > 0 }, // ID3v1 "0000" is not a year
            parent = dirs.takeIf { it.isNotEmpty() }?.joinToString("/"),
            grandparent = dirs.takeIf { it.size >= 2 }?.dropLast(1)?.joinToString("/"),
        )
    }

    fun pick(
        seeds: List<WeightedSeed>,
        library: List<Song>,
        exclude: Set<String>,
        previous: Song?,
        count: Int,
        random: Random,
    ): List<Song> {
        if (count <= 0 || library.isEmpty()) return emptyList()
        val seedIds = seeds.mapTo(HashSet()) { it.song.id }
        val features = HashMap<String, Features>(library.size * 2)
        library.forEach { features[it.id] = featuresOf(it) }
        // Folder sizes over the whole library decide which folders are informative.
        val parentSize = HashMap<String, Int>()
        val grandSize = HashMap<String, Int>()
        features.values.forEach { f ->
            f.parent?.let { parentSize.merge(it, 1, Int::plus) }
            f.grandparent?.let { grandSize.merge(it, 1, Int::plus) }
        }
        val candidates = library.filter { it.id !in exclude && it.id !in seedIds }.distinctBy { it.id }
        if (candidates.isEmpty()) return emptyList()

        val seedFeatures = seeds.map { (featuresOf(it.song)) to it.weight }
        fun similarity(s: Features, c: Features): Double {
            var p = 0.0
            if (s.artist !in GENERIC_ARTISTS && s.artist == c.artist) p += 5
            if (s.genres.isNotEmpty() && c.genres.any { it in s.genres }) p += 4
            if (s.albumArtist != null && s.albumArtist == c.albumArtist) p += 3
            if (s.year != null && c.year != null) {
                val d = abs(s.year - c.year)
                if (d <= 3) p += 2 else if (d <= 8) p += 1
            }
            val parentOk = s.parent != null && s.parent == c.parent && (parentSize[s.parent] ?: 0) <= MAX_PARENT_FOLDER
            val grandOk = s.grandparent != null && s.grandparent == c.grandparent &&
                (grandSize[s.grandparent] ?: 0) <= MAX_GRANDPARENT_FOLDER
            if (parentOk) p += 2 else if (grandOk) p += 1
            return p
        }

        // Shuffled BEFORE scoring so the stable sort breaks score ties randomly; otherwise
        // thousands of equal scores always yield the first POOL_SIZE in library-index order.
        val wildOrder = candidates.shuffled(random)
        val pool = wildOrder
            .map { c -> val f = features.getValue(c.id); c to seedFeatures.sumOf { (s, w) -> w * similarity(s, f) } }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(POOL_SIZE)
            .toMutableList()

        val out = ArrayList<Song>(count)
        val used = HashSet<String>()
        val perArtist = HashMap<String, Int>()
        var prev: Features? = previous?.let { features[it.id] ?: featuresOf(it) }

        fun fits(f: Features): Boolean {
            if ((perArtist[f.artist] ?: 0) >= MAX_PER_ARTIST) return false
            val p = prev ?: return true
            return f.artist != p.artist && (f.album == null || f.album != p.album)
        }
        fun take(song: Song) {
            val f = features.getValue(song.id)
            out += song
            used += song.id
            perArtist.merge(f.artist, 1, Int::plus)
            prev = f
        }

        fun pickScored(): Song? {
            val eligible = pool.indices.filter { fits(features.getValue(pool[it].first.id)) }
            if (eligible.isEmpty()) return null
            val total = eligible.sumOf { pool[it].second * pool[it].second }
            var r = random.nextDouble() * total
            var chosen = eligible.last()
            for (i in eligible) {
                r -= pool[i].second * pool[i].second
                if (r <= 0.0) { chosen = i; break }
            }
            return pool.removeAt(chosen).first
        }

        fun pickWild(): Song? =
            wildOrder.firstOrNull { it.id !in used && fits(features.getValue(it.id)) }
                // Diversity is a preference: break it rather than end the station early.
                ?: wildOrder.firstOrNull { it.id !in used }

        val wildSlots = if (count >= 5) maxOf(1, count / 10) else 0
        val wildAt = (0 until count).shuffled(random).take(wildSlots).toHashSet()
        for (slot in 0 until count) {
            val song = (if (slot in wildAt) null else pickScored()) ?: pickWild() ?: break
            pool.removeAll { it.first.id == song.id }
            take(song)
        }
        return out
    }
}

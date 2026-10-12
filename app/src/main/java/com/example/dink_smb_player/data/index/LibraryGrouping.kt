package com.example.dink_smb_player.data.index

import java.text.Normalizer

/**
 * Artist/album grouping-key computation. Lives in the data layer (not the UI) because the keys are
 * precomputed once per import/retag and PERSISTED on each [TrackEntity] — so the Albums/Artists
 * views group with a plain `groupBy` on the stored key instead of re-running NFD + regex
 * normalization across the whole (25k-row) library every time a section is opened or the process
 * restarts. [computeGroupingKeys] is the single source of truth; the UI only reads the results
 * (and falls back to [normKey] for the rare row that predates precompute).
 *
 * Attribution rationale mirrors the old display-time logic exactly:
 *  - [normKey] collapses cosmetic spelling variants (case, leading "The", featured-artist suffix,
 *    ALL punctuation/spacing, and accents via NFD) so `AC/DC`/`AC-DC`/`ACDC` and
 *    `Motörhead`/`Motorhead` fold to one key; unicode letters survive (Cyrillic/CJK).
 *  - [primaryArtistKey] files a collaboration under its primary artist using LIBRARY-WIDE stats
 *    (who appears most as a solo act) — which is exactly why this must be a whole-library pass
 *    and can't be a pure per-row transform.
 *  - Album identity is album-artist + title (LIB-6): `"<albumArtistKey>|<titleKey>"`, so
 *    "Greatest Hits" by different artists (and folder-derived "CD1" albums) no longer merge.
 *    See [albumArtistKeys] for how the album artist is chosen, compilations included.
 */
object LibraryGrouping {

    // Compiled once — these run tens of thousands of times per computeGroupingKeys pass.
    // A featured-artist marker only counts when a name FOLLOWS it — so the band "Little Feat"
    // (feat at the very end) keeps its whole name instead of folding into "Little".
    private const val FEAT_NAME_START = "[\\p{L}\\p{Nd}\\p{Pi}\"']"
    private val FEAT_SUFFIX = Regex(
        "\\s*[\\(\\[]?\\b(?:feat|ft|featuring)\\b(?:\\.\\s*|\\s+)(?=$FEAT_NAME_START).*$",
        RegexOption.IGNORE_CASE,
    )
    private val NON_ALNUM = Regex("[^\\p{L}\\p{Nd}]+")
    // Collaboration separators between DISTINCT artists: slash/semicolon/comma, " & ", " feat ".
    // Not a hyphen — that lives inside names (AC-DC) and is folded away by normKey instead.
    private val ARTIST_DELIM = Regex(
        "\\s*[/;,]\\s*|\\s+&\\s+|\\s+(?:feat|ft|featuring)\\b(?:\\.\\s*|\\s+)(?=$FEAT_NAME_START)",
        RegexOption.IGNORE_CASE,
    )
    // Combining marks left after NFD decomposition — dropping them folds accents to the base letter.
    private val COMBINING = Regex("\\p{Mn}+")

    // Memoise: there are only a few thousand DISTINCT raw strings in a 25k library, so the regex
    // work is paid once per distinct string rather than per row across every pass.
    private val normKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val tokensCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val leadCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Collapse cosmetic spelling differences to a single grouping key. See class doc. */
    fun normKey(raw: String): String = normKeyCache.getOrPut(raw) {
        var s = raw.trim().lowercase()
        s = COMBINING.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
        s = FEAT_SUFFIX.replace(s, "")
        if (s.startsWith("the ")) s = s.removePrefix("the ").trim()
        s = NON_ALNUM.replace(s, "")
        // Symbol-only names fall back to the raw text; keep ALBUM_KEY_SEP out of it.
        s.ifBlank { raw.trim().lowercase().replace(ALBUM_KEY_SEP, '_') }
    }

    private fun artistTokens(raw: String): List<String> = tokensCache.getOrPut(raw) {
        raw.split(ARTIST_DELIM).map { normKey(it) }.filter { it.isNotEmpty() }
    }

    /** [raw] with any featured-artist suffix removed ("Lead feat. Guest" → "Lead"): the part of
     *  a credit that can decide its bucket. Featured guests never do. */
    private fun leadPart(raw: String): String = leadCache.getOrPut(raw) {
        FEAT_SUFFIX.replace(raw, "").trim().ifEmpty { raw }
    }

    /** Members of the lead part of [raw] (see [leadPart]) as normKeys. One element = a solo credit. */
    private fun leadTokens(raw: String): List<String> = artistTokens(leadPart(raw))

    /**
     * Grouping key for an artist string. Featured guests are dropped first ([leadPart]); then:
     *  - one name → that name ("Little Feat", "AC-DC" — [normKey] folds the cosmetics);
     *  - several names, at least one a KNOWN SOLO ACT (has tracks credited to it alone) → the
     *    member with the most solo tracks (ties → the earlier/lead one), so "Apocalyptica, Guest"
     *    and "Guest & Apocalyptica" both fold into Apocalyptica;
     *  - otherwise → the whole name ("AC/DC", "Simon & Garfunkel", "Earth, Wind & Fire", "A & B").
     *
     * Deliberately NOT a function of how many tracks carry the whole name: an earlier rule kept
     * the whole name only at >= 3 tracks and otherwise fell back to the first member, so a 2-track
     * "AC/DC" filed under "AC" and adding/removing one track re-keyed the whole artist.
     */
    private fun primaryArtistKey(raw: String, solo: Map<String, Int>): String {
        val toks = leadTokens(raw)
        if (toks.size <= 1) return normKey(raw)
        val best = toks.maxByOrNull { solo[it] ?: 0 }
        if (best != null && (solo[best] ?: 0) > 0) return best
        return normKey(leadPart(raw))
    }

    /** A clean, feat-free spelling of the part of [raw] that maps to bucket [key], or null. */
    private fun cleanedSpellingForKey(raw: String, key: String): String? {
        val whole = FEAT_SUFFIX.replace(raw, "").trim()
        if (whole.isNotEmpty() && normKey(whole) == key) return whole
        return raw.split(ARTIST_DELIM)
            .map { FEAT_SUFFIX.replace(it.trim(), "").trim() }
            .firstOrNull { it.isNotEmpty() && normKey(it) == key }
    }

    /** Album-artist key of a compilation: what an untagged multi-artist folder files under,
     *  and what a "Various Artists" album-artist tag normalizes to. */
    const val VARIOUS_ARTISTS_KEY = "variousartists"
    // Album-artist tag spellings that mean "compilation" (after normKey).
    private val VARIOUS_ALIASES = setOf(VARIOUS_ARTISTS_KEY, "variousartist", "various", "va", "compilation")

    /** Separates the album-artist and title parts of an album key. normKey strips punctuation,
     *  so it never occurs inside either part — a key without it predates LIB-6 (title only). */
    const val ALBUM_KEY_SEP = '|'

    /** Album key from an album-artist key and a raw album title. */
    fun albumKey(albumArtistKey: String, albumTitle: String?): String =
        albumArtistKey + ALBUM_KEY_SEP + normKey(albumTitle ?: "Unknown album")

    /** Album key for a row with no precomputed one (mock data, or a row added since the last
     *  recompute): its own artist as album artist — no library stats, no compilation folding. */
    fun fallbackAlbumKey(artist: String?, albumTitle: String?): String =
        albumKey(normKey(artist ?: "Unknown"), albumTitle)

    /** The album-artist part of an album key, or null for a pre-LIB-6 (title-only) key. */
    fun albumArtistKeyOf(albumKey: String): String? =
        albumKey.substringBefore(ALBUM_KEY_SEP, missingDelimiterValue = "").ifEmpty { null }

    /** Bump when the grouping rules change in a way [keysStale] can't see, so restore
     *  recomputes every row's keys once. 2: stable delimiter-name attribution (AC/DC). */
    const val SCHEME = 2

    /** True when [t]'s stored keys are missing or were written by an older grouping scheme,
     *  so a restore must recompute them once (see LibraryRepository.migrateGroupingKeys). */
    fun keysStale(t: TrackEntity): Boolean =
        t.artistKey == null || t.albumKey?.contains(ALBUM_KEY_SEP) != true

    /** Folder that holds [t] — the compilation-detection bucket (plus the album title). */
    private fun folderOf(t: TrackEntity): String =
        "${t.sourceType}|${t.sourceId}|${t.path.substringBeforeLast('/', "")}"

    /**
     * Album-artist key per row (parallel to [tracks]); [artistKeys] are the rows' primary
     * artist keys. Rows are bucketed by folder + album title:
     *  - An album-artist tag wins ([primaryArtistKey] of it; "Various Artists"/"VA" → compilation).
     *    Untagged rows in a bucket where other rows carry the tag adopt its most common value.
     *  - Otherwise, one primary artist across the bucket → that artist.
     *  - Several artists, one of whom has MORE than half the tracks → an artist album with guest
     *    tracks: rows by, or crediting, that artist file under them; unrelated rows keep their own
     *    artist (so a flat folder mixing two artists' "Greatest Hits" still splits).
     *  - No majority → a compilation: the whole bucket files under [VARIOUS_ARTISTS_KEY] — one
     *    album, not one per track. The key itself carries no folder, so a compilation split
     *    across CD1/CD2 folders (same title) still lands as one album.
     */
    private fun albumArtistKeys(
        tracks: List<TrackEntity>,
        artistKeys: List<String>,
        solo: Map<String, Int>,
    ): Array<String> {
        val out = Array(tracks.size) { artistKeys[it] }
        val tagKeys = arrayOfNulls<String>(tracks.size)
        val buckets = HashMap<String, MutableList<Int>>()
        for (i in tracks.indices) {
            val t = tracks[i]
            t.albumArtist?.trim()?.takeIf { it.isNotEmpty() }?.let { tag ->
                val k = primaryArtistKey(tag, solo)
                tagKeys[i] = if (k in VARIOUS_ALIASES) VARIOUS_ARTISTS_KEY else k
            }
            buckets.getOrPut(folderOf(t) + ALBUM_KEY_SEP + normKey(t.albumTitle ?: "Unknown album")) { ArrayList(4) } += i
        }
        for (rows in buckets.values) {
            val tagged = rows.mapNotNull { tagKeys[it] }
            if (tagged.isNotEmpty()) {
                val common = tagged.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
                for (i in rows) out[i] = tagKeys[i] ?: common
                continue
            }
            if (rows.size == 1) continue
            val counts = HashMap<String, Int>()
            for (i in rows) counts.merge(artistKeys[i], 1, Int::plus)
            if (counts.size == 1) continue
            val (top, topCount) = counts.maxByOrNull { it.value }!!
            if (topCount * 2 > rows.size) {
                for (i in rows) {
                    val a = tracks[i].artist ?: "Unknown"
                    if (top in artistTokens(a) || normKey(a) == top) out[i] = top
                }
            } else {
                for (i in rows) out[i] = VARIOUS_ARTISTS_KEY
            }
        }
        return out
    }

    /**
     * Fill [TrackEntity.artistKey]/[TrackEntity.albumKey]/[TrackEntity.artistLabel] for every row,
     * using library-wide collaboration stats. Returns copies in input order (unchanged rows are
     * returned as-is by data equality, so callers can upsert only what changed). Run this at
     * authoritative write boundaries (import / retag) and once as a migration for old snapshots.
     */
    fun computeGroupingKeys(tracks: List<TrackEntity>): List<TrackEntity> {
        if (tracks.isEmpty()) return tracks
        // Solo-act stats: a track credited to ONE name (featured guests don't count against it,
        // so "Lead feat. Guest" is a Lead solo track) makes that name a known standalone act.
        val solo = HashMap<String, Int>()
        for (t in tracks) {
            val toks = leadTokens(t.artist ?: "Unknown")
            if (toks.size == 1) solo.merge(toks[0], 1, Int::plus)
        }
        val artistKeys = tracks.map { primaryArtistKey(it.artist ?: "Unknown", solo) }
        val albumArtists = albumArtistKeys(tracks, artistKeys, solo)
        return tracks.mapIndexed { i, t ->
            val a = t.artist ?: "Unknown"
            val aKey = artistKeys[i]
            val label = cleanedSpellingForKey(a, aKey) ?: FEAT_SUFFIX.replace(a, "").trim().ifBlank { a }
            t.copy(
                artistKey = aKey,
                albumKey = albumKey(albumArtists[i], t.albumTitle),
                artistLabel = label,
            )
        }
    }
}

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
 */
object LibraryGrouping {

    // Compiled once — these run tens of thousands of times per computeGroupingKeys pass.
    private val FEAT_SUFFIX =
        Regex("\\s*[\\(\\[]?\\b(?:feat|ft|featuring)\\b\\.?.*$", RegexOption.IGNORE_CASE)
    private val NON_ALNUM = Regex("[^\\p{L}\\p{Nd}]+")
    // Collaboration separators between DISTINCT artists: slash/semicolon/comma, " & ", " feat ".
    // Not a hyphen — that lives inside names (AC-DC) and is folded away by normKey instead.
    private val ARTIST_DELIM =
        Regex("\\s*[/;,]\\s*|\\s+&\\s+|\\s+(?:feat|ft|featuring)\\b\\.?\\s*", RegexOption.IGNORE_CASE)
    // Combining marks left after NFD decomposition — dropping them folds accents to the base letter.
    private val COMBINING = Regex("\\p{Mn}+")

    // Memoise: there are only a few thousand DISTINCT raw strings in a 25k library, so the regex
    // work is paid once per distinct string rather than per row across every pass.
    private val normKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val tokensCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** Collapse cosmetic spelling differences to a single grouping key. See class doc. */
    fun normKey(raw: String): String = normKeyCache.getOrPut(raw) {
        var s = raw.trim().lowercase()
        s = COMBINING.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
        s = FEAT_SUFFIX.replace(s, "")
        if (s.startsWith("the ")) s = s.removePrefix("the ").trim()
        s = NON_ALNUM.replace(s, "")
        s.ifBlank { raw.trim().lowercase() }
    }

    private fun artistTokens(raw: String): List<String> = tokensCache.getOrPut(raw) {
        raw.split(ARTIST_DELIM).map { normKey(it) }.filter { it.isNotEmpty() }
    }

    /** Grouping key for an artist string: the whole name when it's a recognised act (so slashes
     *  in names survive), else the best-known member of the collaboration, else the LEAD artist. */
    private fun primaryArtistKey(raw: String, full: Map<String, Int>, solo: Map<String, Int>): String {
        val whole = normKey(raw)
        val toks = artistTokens(raw)
        if (toks.size <= 1) return whole
        // A known standalone act among the members wins FIRST — so "Apocalyptica, <guest>" folds
        // into Apocalyptica even though that exact collaboration recurs.
        val best = toks.maxByOrNull { solo[it] ?: 0 }
        if (best != null && (solo[best] ?: 0) > 0) return best
        if ((full[whole] ?: 0) >= 3) return whole   // e.g. "AC/DC" — a name, not a collab
        return toks.first()
    }

    /** A clean, feat-free spelling of the part of [raw] that maps to bucket [key], or null. */
    private fun cleanedSpellingForKey(raw: String, key: String): String? {
        val whole = FEAT_SUFFIX.replace(raw, "").trim()
        if (whole.isNotEmpty() && normKey(whole) == key) return whole
        return raw.split(ARTIST_DELIM)
            .map { FEAT_SUFFIX.replace(it.trim(), "").trim() }
            .firstOrNull { it.isNotEmpty() && normKey(it) == key }
    }

    /**
     * Fill [TrackEntity.artistKey]/[TrackEntity.albumKey]/[TrackEntity.artistLabel] for every row,
     * using library-wide collaboration stats. Returns copies in input order (unchanged rows are
     * returned as-is by data equality, so callers can upsert only what changed). Run this at
     * authoritative write boundaries (import / retag) and once as a migration for old snapshots.
     */
    fun computeGroupingKeys(tracks: List<TrackEntity>): List<TrackEntity> {
        if (tracks.isEmpty()) return tracks
        val full = HashMap<String, Int>()
        val solo = HashMap<String, Int>()
        for (t in tracks) {
            val a = t.artist ?: "Unknown"
            full.merge(normKey(a), 1, Int::plus)
            val toks = artistTokens(a)
            if (toks.size == 1) solo.merge(toks[0], 1, Int::plus)
        }
        return tracks.map { t ->
            val a = t.artist ?: "Unknown"
            val aKey = primaryArtistKey(a, full, solo)
            val label = cleanedSpellingForKey(a, aKey) ?: FEAT_SUFFIX.replace(a, "").trim().ifBlank { a }
            t.copy(
                artistKey = aKey,
                albumKey = normKey(t.albumTitle ?: "Unknown album"),
                artistLabel = label,
            )
        }
    }
}

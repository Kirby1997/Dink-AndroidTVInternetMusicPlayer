package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.Song
import java.text.Normalizer
import kotlin.math.abs

/**
 * Candidate validation for the search-based providers (LYR-5). Taking a search's first
 * hit let a different song's synced lyrics win (a cover, a same-titled track by another
 * artist, a live cut). A candidate is accepted only when its normalised title matches,
 * its artist matches (when both sides know one), and its duration is within
 * [DURATION_TOLERANCE_SEC] (when both sides know one). Providers scan the top [TOP_N]
 * hits, not only [0].
 */
internal object LyricMatch {

    const val TOP_N = 5
    const val DURATION_TOLERANCE_SEC = 3

    /** One search hit. [durationSec] null/≤0 = unknown. */
    data class Candidate<T>(
        val title: String?,
        val artist: String?,
        val durationSec: Int?,
        val payload: T,
    )

    /** First candidate among the top [topN] that [matches] the song, in the provider's order. */
    fun <T> pick(song: Song, candidates: List<Candidate<T>>, topN: Int = TOP_N): Candidate<T>? =
        candidates.take(topN).firstOrNull { matches(song, it) }

    /** Every candidate among the top [topN] that [matches], in order. */
    fun <T> valid(song: Song, candidates: List<Candidate<T>>, topN: Int = TOP_N): List<Candidate<T>> =
        candidates.take(topN).filter { matches(song, it) }

    fun matches(song: Song, c: Candidate<*>): Boolean =
        titleMatches(song.title, c.title) &&
            artistMatches(song.artist, c.artist) &&
            durationMatches(song.durationSec, c.durationSec)

    /** Normalised equality once version / remaster / feat. decorations are stripped. A
     *  candidate with no title can't be checked and is rejected. */
    fun titleMatches(wanted: String, got: String?): Boolean {
        if (got.isNullOrBlank()) return false
        val w = normTitle(wanted)
        if (w.isEmpty()) return false
        return w == normTitle(got)
    }

    /** True when either side's artist is unknown, the normalised names are equal, or the
     *  two credit lists share an artist ("A feat. B" vs "A", "A & B" vs "B"). */
    fun artistMatches(wanted: String, got: String?): Boolean {
        if (isUnknownArtist(wanted) || got.isNullOrBlank()) return true
        val w = norm(wanted)
        val g = norm(got)
        if (w.isEmpty() || g.isEmpty()) return true
        if (w == g || stripThe(w) == stripThe(g)) return true
        val wParts = artistParts(wanted)
        val gParts = artistParts(got)
        return wParts.any { it in gParts }
    }

    /** Within ±[DURATION_TOLERANCE_SEC] when both durations are known; otherwise true. */
    fun durationMatches(wantedSec: Int, gotSec: Int?): Boolean {
        if (wantedSec <= 0 || gotSec == null || gotSec <= 0) return true
        return abs(wantedSec - gotSec) <= DURATION_TOLERANCE_SEC
    }

    private val BRACKETED = Regex("""[(\[{（【][^)\]}）】]*[)\]}）】]""")
    private val DASH_SUFFIX = Regex(
        """\s+[-–—]\s+.*\b(remaster(ed)?|live|version|mix|edit|mono|stereo|demo|acoustic|bonus|explicit|instrumental)\b.*$""",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
    private val MARKS = Regex("""\p{Mn}+""")
    private val ARTIST_SEP = Regex(
        """\s*(?:,|;|/|&|\+|\s+x\s+|\s+and\s+|\s+vs\.?\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+)\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val UNKNOWN = setOf("unknown", "unknownartist", "variousartists", "va")

    /** Lowercase, diacritics folded, letters/digits only (Unicode-aware — CJK survives). */
    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase()
            .replace(NON_ALNUM, "")

    fun normTitle(s: String): String {
        val stripped = s.replace(BRACKETED, " ").replace(DASH_SUFFIX, "").replace(FEAT, "")
        // A title that is ALL brackets ("(Untitled)") would normalise to ""; keep the raw form.
        return norm(stripped).ifEmpty { norm(s) }
    }

    private fun stripThe(n: String) = n.removePrefix("the")

    private fun artistParts(s: String): Set<String> =
        s.split(ARTIST_SEP).map { stripThe(norm(it)) }.filter { it.isNotEmpty() }.toSet()

    private fun isUnknownArtist(s: String): Boolean {
        val n = norm(s)
        return n.isEmpty() || n in UNKNOWN
    }
}

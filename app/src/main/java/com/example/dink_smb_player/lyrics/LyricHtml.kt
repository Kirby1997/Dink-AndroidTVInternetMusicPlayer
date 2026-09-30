package com.example.dink_smb_player.lyrics

/**
 * Small HTML / URL-slug helpers shared by the scraping lyric providers
 * ([LyricScrapers]). These sites have no API — we fetch the page and pull the lyrics
 * out of a known element, mirroring foo_openlyrics' scrapers. Brittle by nature; each
 * provider is independently toggleable so a broken one can be switched off.
 */
internal object LyricHtml {

    /** Lowercase, ASCII-alphanumeric only — drops spaces and punctuation entirely.
     *  (AZLyrics, DarkLyrics, MetalArchives band/album/title slugs.) */
    fun slugAlnum(s: String): String = buildString {
        for (c in s) if (c.isAsciiAlnum()) append(c.lowercaseChar())
    }

    /** Lowercase alphanumeric, every other run collapsed to a single '-', trimmed.
     *  (Lyricsify, Letras, LyricFind, Bandcamp title slugs.) */
    fun slugDash(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c.isAsciiAlnum()) sb.append(c.lowercaseChar())
            else if (sb.isNotEmpty() && sb.last() != '-') sb.append('-')
        }
        return sb.toString().trim('-')
    }

    /** SongLyrics slug: alnum lowercase, space/'-'→'-', '&'→"and", '@'→"at". */
    fun slugSongLyrics(s: String): String = buildString {
        for (c in s) when {
            c.isAsciiAlnum() -> append(c.lowercaseChar())
            c == ' ' || c == '-' -> append('-')
            c == '&' -> append("and")
            c == '@' -> append("at")
        }
    }

    private fun Char.isAsciiAlnum() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private val BR = Regex("(?i)<br\\s*/?>")
    private val P_CLOSE = Regex("(?i)</p\\s*>")
    private val P_OPEN = Regex("(?i)<p[^>]*>")
    private val TAG = Regex("<[^>]+>")
    private val BLANK_RUN = Regex("\n{3,}")

    /** `&name;`, `&#NNN;` or `&#xHH;` — matched once, left to right, so a decoded `&`
     *  is never re-read as the start of another entity (`&amp;lt;` → `&lt;`). */
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,15});")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "rsquo" to "\u2019", "lsquo" to "\u2018", "rdquo" to "\u201D",
        "ldquo" to "\u201C", "hellip" to "\u2026", "ndash" to "\u2013", "mdash" to "\u2014",
    )

    /** Turn an HTML fragment into plain text: <br>/<p> → newlines, strip tags, decode
     *  entities, trim each line, collapse blank runs. */
    fun htmlToText(html: String): String {
        var s = html
        s = s.replace(BR, "\n")
        s = s.replace(P_CLOSE, "\n")
        s = s.replace(P_OPEN, "")
        s = s.replace(TAG, "")
        s = decodeEntities(s)
        return s.lineSequence().map { it.trim() }.joinToString("\n")
            .replace(BLANK_RUN, "\n\n").trim()
    }

    /** One-pass entity decode. Numeric references go through [Character.toChars] so
     *  supplementary code points (emoji, `&#128512;`) become a surrogate pair rather than
     *  a truncated char. Unknown names and invalid code points are left verbatim. */
    internal fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        return ENTITY.replace(s) { m ->
            val body = m.groupValues[1]
            val decoded = when {
                body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2).toIntOrNull(16))
                body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull())
                else -> NAMED[body]
            }
            decoded ?: m.value
        }
    }

    private fun codePoint(cp: Int?): String? =
        if (cp == null || cp == 0 || !Character.isValidCodePoint(cp) || cp in 0xD800..0xDFFF) null
        else String(Character.toChars(cp))

    /** Substring between the first [start] and the next [end] after it; null if absent. */
    fun between(html: String, start: String, end: String): String? {
        val i = html.indexOf(start, ignoreCase = true)
        if (i < 0) return null
        val from = i + start.length
        val j = html.indexOf(end, from, ignoreCase = true)
        return if (j < 0) html.substring(from) else html.substring(from, j)
    }
}

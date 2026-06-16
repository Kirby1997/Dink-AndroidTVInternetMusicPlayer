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

    /** Turn an HTML fragment into plain text: <br>/<p> → newlines, strip tags, decode
     *  entities, trim each line, collapse blank runs. */
    fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</p\\s*>"), "\n")
        s = s.replace(Regex("(?i)<p[^>]*>"), "")
        s = s.replace(Regex("<[^>]+>"), "")
        s = decodeEntities(s)
        return s.lineSequence().map { it.trim() }.joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun decodeEntities(s: String): String {
        var t = s
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
            .replace("&apos;", "'").replace("&nbsp;", " ")
        // Numeric entities &#NN; and &#xHH;
        t = Regex("&#x([0-9a-fA-F]+);").replace(t) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
        t = Regex("&#(\\d+);").replace(t) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        return t
    }

    /** Substring between the first [start] and the next [end] after it; null if absent. */
    fun between(html: String, start: String, end: String): String? {
        val i = html.indexOf(start, ignoreCase = true)
        if (i < 0) return null
        val from = i + start.length
        val j = html.indexOf(end, from, ignoreCase = true)
        return if (j < 0) html.substring(from) else html.substring(from, j)
    }
}

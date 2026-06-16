package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.Song
import org.json.JSONObject
import java.net.URLEncoder

/**
 * HTML-scraping lyric providers ported from foo_openlyrics. All plain-text except
 * Lyricsify/Letras (which can carry synced LRC). They build a site URL from the
 * artist/title (and album, for DarkLyrics) — so they depend on accurate tags
 * (Phase 8.7) and 404 on bad metadata. Brittle by nature: each is independently
 * toggleable in Settings, and the reliable / metal-focused ones are on by default
 * while the niche / captcha-prone ones are off.
 */

private const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private fun lrcOrPlain(text: String): OnlineLyrics {
    val parsed = runCatching { LrcParser.parse(text) }.getOrDefault(emptyList())
    return if (parsed.any { it.timeSec > 0f }) OnlineLyrics(synced = parsed)
    else OnlineLyrics(plain = plainToLines(text))
}

/** Lyricsify — synced LRC. lyricsify.com/lyrics/<artist>/<title>. */
object LyricsifyLyrics : OnlineLyricProvider {
    override val id = "lyricsify"
    override val label = "Lyricsify"
    override val defaultEnabled = true
    override fun fetch(song: Song): OnlineLyrics {
        if (song.title.isBlank() || song.artist.isBlank()) return OnlineLyrics()
        val url = "https://www.lyricsify.com/lyrics/${LyricHtml.slugDash(song.artist)}/${LyricHtml.slugDash(song.title)}"
        val html = LyricHttp.get(url, mapOf("User-Agent" to BROWSER_UA)) ?: return OnlineLyrics()
        val block = Regex("id=\"lyrics_\\d+_details\"[^>]*>([\\s\\S]*?)</div>")
            .find(html)?.groupValues?.get(1) ?: return OnlineLyrics()
        val text = LyricHtml.htmlToText(block)
        return if (text.isBlank()) OnlineLyrics() else lrcOrPlain(text)
    }
}

/** Letras — synced or plain. letras.com/<artist>/<title>/. */
object LetrasLyrics : OnlineLyricProvider {
    override val id = "letras"
    override val label = "Letras"
    override val defaultEnabled = false
    override fun fetch(song: Song): OnlineLyrics {
        if (song.title.isBlank() || song.artist.isBlank()) return OnlineLyrics()
        val url = "https://www.letras.com/${LyricHtml.slugDash(song.artist)}/${LyricHtml.slugDash(song.title)}/"
        val html = LyricHttp.get(url, mapOf("User-Agent" to BROWSER_UA)) ?: return OnlineLyrics()
        val block = Regex("class=\"lyric-original\"[^>]*>([\\s\\S]*?)</div>")
            .find(html)?.groupValues?.get(1) ?: return OnlineLyrics()
        val text = LyricHtml.htmlToText(block)
        return if (text.isBlank()) OnlineLyrics() else lrcOrPlain(text)
    }
}

/** DarkLyrics — plain, metal. Album page lists every track; we match by title. */
object DarkLyrics : OnlineLyricProvider {
    override val id = "darklyrics"
    override val label = "DarkLyrics"
    override val defaultEnabled = true
    override fun fetch(song: Song): OnlineLyrics {
        val album = song.albumTitle?.takeIf { it.isNotBlank() } ?: return OnlineLyrics()
        if (song.title.isBlank() || song.artist.isBlank()) return OnlineLyrics()
        val url = "http://www.darklyrics.com/lyrics/${LyricHtml.slugAlnum(song.artist)}/${LyricHtml.slugAlnum(album)}.html"
        val html = LyricHttp.get(url, mapOf("User-Agent" to BROWSER_UA)) ?: return OnlineLyrics()
        val body = LyricHtml.between(html, "class=\"lyrics\"", "<div class=\"thanks\"") ?: html
        // Each track section starts at <h3>...Title...</h3>; pick the one matching our
        // title and take the text up to the next <h3>.
        val wantedSlug = LyricHtml.slugAlnum(song.title)
        val sections = body.split(Regex("(?i)<h3"))
        for (sec in sections) {
            val headEnd = sec.indexOf("</h3>", ignoreCase = true)
            if (headEnd < 0) continue
            val heading = LyricHtml.htmlToText(sec.substring(0, headEnd))
            if (LyricHtml.slugAlnum(heading).contains(wantedSlug) && wantedSlug.isNotBlank()) {
                val text = LyricHtml.htmlToText(sec.substring(headEnd + 5))
                if (text.isNotBlank()) return OnlineLyrics(plain = plainToLines(text))
            }
        }
        return OnlineLyrics()
    }
}

/** Metal Archives — plain, metal. AJAX search → release id → lyrics fragment. */
object MetalArchivesLyrics : OnlineLyricProvider {
    override val id = "metalarchives"
    override val label = "Metal Archives"
    override val defaultEnabled = true
    private val headers = mapOf(
        "User-Agent" to BROWSER_UA,
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "https://www.metal-archives.com/",
    )
    override fun fetch(song: Song): OnlineLyrics {
        if (song.title.isBlank() || song.artist.isBlank()) return OnlineLyrics()
        val album = song.albumTitle.orEmpty()
        val searchUrl = "https://www.metal-archives.com/search/ajax-advanced/searching/songs" +
            "?bandName=${enc(song.artist)}&releaseTitle=${enc(album)}&songTitle=${enc(song.title)}"
        val searchBody = LyricHttp.get(searchUrl, headers) ?: return OnlineLyrics()
        val id = runCatching {
            val rows = JSONObject(searchBody).optJSONArray("aaData") ?: return OnlineLyrics()
            if (rows.length() == 0) return OnlineLyrics()
            val linkHtml = rows.getJSONArray(0).optString(4)
            Regex("lyricsLink_(\\d+)").find(linkHtml)?.groupValues?.get(1)
        }.getOrNull() ?: return OnlineLyrics()
        val lyricsHtml = LyricHttp.get("https://www.metal-archives.com/release/ajax-view-lyrics/id/$id", headers)
            ?: return OnlineLyrics()
        val text = LyricHtml.htmlToText(lyricsHtml)
        if (text.isBlank() || text.contains("lyrics not available", ignoreCase = true)) return OnlineLyrics()
        return OnlineLyrics(plain = plainToLines(text))
    }
}

/** AZLyrics — plain. Captcha-prone (off by default). */
object AZLyrics : OnlineLyricProvider {
    override val id = "azlyrics"
    override val label = "AZLyrics"
    override val defaultEnabled = false
    override fun fetch(song: Song): OnlineLyrics {
        if (song.title.isBlank() || song.artist.isBlank()) return OnlineLyrics()
        val url = "https://www.azlyrics.com/lyrics/${LyricHtml.slugAlnum(song.artist)}/${LyricHtml.slugAlnum(song.title)}.html"
        val html = LyricHttp.get(url, mapOf("User-Agent" to BROWSER_UA)) ?: return OnlineLyrics()
        // Lyrics sit in an unlabeled div right after this licensing comment.
        val block = LyricHtml.between(html, "Sorry about that. -->", "</div>") ?: return OnlineLyrics()
        val text = LyricHtml.htmlToText(block)
        return if (text.isBlank()) OnlineLyrics() else OnlineLyrics(plain = plainToLines(text))
    }
}

/** SongLyrics — plain. songlyrics.com/<artist>/<title>-lyrics/. */
object SongLyrics : OnlineLyricProvider {
    override val id = "songlyrics"
    override val label = "SongLyrics"
    override val defaultEnabled = false
    override fun fetch(song: Song): OnlineLyrics {
        if (song.title.isBlank() || song.artist.isBlank()) return OnlineLyrics()
        val url = "https://www.songlyrics.com/${LyricHtml.slugSongLyrics(song.artist)}/${LyricHtml.slugSongLyrics(song.title)}-lyrics/"
        val html = LyricHttp.get(url, mapOf("User-Agent" to BROWSER_UA)) ?: return OnlineLyrics()
        val block = Regex("id=\"songLyricsDiv\"[^>]*>([\\s\\S]*?)</p>")
            .find(html)?.groupValues?.get(1) ?: return OnlineLyrics()
        val text = LyricHtml.htmlToText(block)
        if (text.isBlank() || text.contains("We do not have the lyrics", ignoreCase = true)) return OnlineLyrics()
        return OnlineLyrics(plain = plainToLines(text))
    }
}

/** Bandcamp — plain. <artist>.bandcamp.com/track/<title>. */
object BandcampLyrics : OnlineLyricProvider {
    override val id = "bandcamp"
    override val label = "Bandcamp"
    override val defaultEnabled = false
    override fun fetch(song: Song): OnlineLyrics {
        if (song.title.isBlank() || song.artist.isBlank()) return OnlineLyrics()
        val url = "https://${LyricHtml.slugAlnum(song.artist)}.bandcamp.com/track/${LyricHtml.slugDash(song.title)}"
        val html = LyricHttp.get(url, mapOf("User-Agent" to BROWSER_UA)) ?: return OnlineLyrics()
        val block = Regex("class=\"[^\"]*lyricsText[^\"]*\"[^>]*>([\\s\\S]*?)</div>")
            .find(html)?.groupValues?.get(1) ?: return OnlineLyrics()
        val text = LyricHtml.htmlToText(block)
        return if (text.isBlank()) OnlineLyrics() else OnlineLyrics(plain = plainToLines(text))
    }
}

/** LyricFind — plain. Parses the page's __NEXT_DATA__ JSON. */
object LyricFindLyrics : OnlineLyricProvider {
    override val id = "lyricfind"
    override val label = "LyricFind"
    override val defaultEnabled = false
    override fun fetch(song: Song): OnlineLyrics {
        if (song.title.isBlank() || song.artist.isBlank()) return OnlineLyrics()
        val url = "https://lyrics.lyricfind.com/lyrics/${LyricHtml.slugDash(song.artist)}-${LyricHtml.slugDash(song.title)}"
        val html = LyricHttp.get(url, mapOf("User-Agent" to BROWSER_UA)) ?: return OnlineLyrics()
        val raw = LyricHtml.between(html, "id=\"__NEXT_DATA__\"", "</script>") ?: return OnlineLyrics()
        val json = raw.substring(raw.indexOf('{').coerceAtLeast(0))
        val lyrics = runCatching {
            JSONObject(json).getJSONObject("props").getJSONObject("pageProps")
                .getJSONObject("songData").getJSONObject("track").optString("lyrics")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return OnlineLyrics()
        return OnlineLyrics(plain = plainToLines(lyrics))
    }
}

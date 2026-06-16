package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.Song
import org.json.JSONObject
import java.net.URLEncoder

/**
 * QQ Music (y.qq.com) lyric provider — public web endpoints used by foo_openlyrics'
 * QQ source. No auth; needs a Referer header. `nobase64=1` returns the LRC inline
 * (otherwise it's base64). Best coverage for CJK tracks.
 */
object QqLyrics : OnlineLyricProvider {
    override val id = "qq"
    override val label = "QQ Music"
    override val defaultEnabled = true

    private val headers = mapOf(
        "Referer" to "https://y.qq.com",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    )

    override fun fetch(song: Song): OnlineLyrics {
        if (song.title.isBlank()) return OnlineLyrics()
        val mid = searchSongMid(song) ?: return OnlineLyrics()
        val body = LyricHttp.get(
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid&format=json&nobase64=1&g_tk=5381",
            headers,
        ) ?: return OnlineLyrics()
        // Response is JSON ({"lyric":"<lrc>", ...}); occasionally wrapped — strip a callback if present.
        val jsonText = body.substringAfter('(', body).substringBeforeLast(')', body)
        val lrc = runCatching { JSONObject(jsonText).optString("lyric") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return OnlineLyrics()
        val parsed = runCatching { LrcParser.parse(lrc) }.getOrDefault(emptyList())
        return if (parsed.any { it.timeSec > 0f }) OnlineLyrics(synced = parsed)
        else OnlineLyrics(plain = plainToLines(lrc))
    }

    private fun searchSongMid(song: Song): String? {
        val q = enc("${song.title} ${song.artist}".trim())
        val body = LyricHttp.get(
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cgi?format=json&p=1&n=5&w=$q",
            headers,
        ) ?: return null
        return runCatching {
            val list = JSONObject(body)
                .optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return null
            if (list.length() == 0) return null
            val first = list.getJSONObject(0)
            first.optString("songmid").ifBlank { first.optString("mid") }.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

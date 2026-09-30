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
    override val syncedCapable = true

    private val headers = mapOf(
        "Referer" to "https://y.qq.com",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    )

    override suspend fun fetch(song: Song): OnlineLyrics {
        if (song.title.isBlank()) return OnlineLyrics()
        // Only hits that validate against title / artist / duration (LYR-5).
        for (mid in searchSongMids(song).take(2)) {
            val body = LyricHttp.get(
                "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid&format=json&nobase64=1&g_tk=5381",
                headers,
            ) ?: continue
            val jsonText = unwrapJsonp(body)
            val lrc = runCatching { JSONObject(jsonText).optString("lyric") }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val parsed = runCatching { LrcParser.parse(lrc) }.getOrDefault(emptyList())
            return if (parsed.any { it.timeSec > 0f }) OnlineLyrics(synced = parsed)
            else OnlineLyrics(plain = plainToLines(lrc))
        }
        return OnlineLyrics()
    }

    private suspend fun searchSongMids(song: Song): List<String> {
        val q = enc("${song.title} ${song.artist}".trim())
        val body = LyricHttp.get(
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cgi?format=json&p=1&n=${LyricMatch.TOP_N}&w=$q",
            headers,
        ) ?: return emptyList()
        return LyricMatch.valid(song, parseSearch(unwrapJsonp(body))).map { it.payload }
    }

    /** `data.song.list[]`: `{songmid|mid, songname, singer[{name}], interval(s)}` → candidates. */
    internal fun parseSearch(body: String): List<LyricMatch.Candidate<String>> = runCatching {
        val list = JSONObject(body)
            .optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return emptyList()
        (0 until list.length()).mapNotNull { i ->
            val o = list.getJSONObject(i)
            val mid = o.optString("songmid").ifBlank { o.optString("mid") }.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val singers = o.optJSONArray("singer")
            val artist = if (singers == null) null else
                (0 until singers.length()).joinToString(", ") { singers.getJSONObject(it).optString("name") }
            LyricMatch.Candidate(
                title = o.optString("songname").ifBlank { o.optString("name") },
                artist = artist,
                durationSec = o.optInt("interval").takeIf { it > 0 },
                payload = mid,
            )
        }
    }.getOrDefault(emptyList())

    /** Response is JSON (`{"lyric":"<lrc>", ...}`), occasionally JSONP-wrapped
     *  (`MusicJsonCallback({...})`). Only strip a callback when the body isn't
     *  already an object — the LRC itself is full of `(` / `)`. */
    internal fun unwrapJsonp(body: String): String {
        val t = body.trim()
        if (t.startsWith("{")) return t
        return t.substringAfter('(', t).substringBeforeLast(')', t)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

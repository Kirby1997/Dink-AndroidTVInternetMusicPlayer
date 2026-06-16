package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.Song
import org.json.JSONObject
import java.net.URLEncoder

/**
 * NetEase Cloud Music (music.163.com) lyric provider — the same public web
 * endpoints foo_openlyrics' NetEase source uses. No auth; needs a Referer header.
 * Strong coverage for synced LRC, including a lot of Western tracks.
 */
object NeteaseLyrics : OnlineLyricProvider {
    override val id = "netease"
    override val label = "NetEase"
    override val defaultEnabled = true

    private val headers = mapOf(
        "Referer" to "https://music.163.com",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    )

    override fun fetch(song: Song): OnlineLyrics {
        if (song.title.isBlank()) return OnlineLyrics()
        val id = searchSongId(song) ?: return OnlineLyrics()
        val body = LyricHttp.get(
            "https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1",
            headers,
        ) ?: return OnlineLyrics()
        val lrc = runCatching { JSONObject(body).optJSONObject("lrc")?.optString("lyric") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return OnlineLyrics()
        val parsed = runCatching { LrcParser.parse(lrc) }.getOrDefault(emptyList())
        return if (parsed.any { it.timeSec > 0f }) OnlineLyrics(synced = parsed)
        else OnlineLyrics(plain = plainToLines(lrc))
    }

    private fun searchSongId(song: Song): Long? {
        val q = enc("${song.title} ${song.artist}".trim())
        val body = LyricHttp.get(
            "https://music.163.com/api/search/get/web?s=$q&type=1&offset=0&limit=5",
            headers,
        ) ?: return null
        return runCatching {
            val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return null
            // First result is NetEase's best match; good enough for the chain.
            if (songs.length() == 0) null else songs.getJSONObject(0).optLong("id").takeIf { it != 0L }
        }.getOrNull()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

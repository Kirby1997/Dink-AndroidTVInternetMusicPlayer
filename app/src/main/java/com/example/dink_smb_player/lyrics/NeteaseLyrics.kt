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
    override val syncedCapable = true

    private val headers = mapOf(
        "Referer" to "https://music.163.com",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    )

    override suspend fun fetch(song: Song): OnlineLyrics {
        if (song.title.isBlank()) return OnlineLyrics()
        // Only hits that validate against the song's title / artist / duration (LYR-5);
        // try the lyric of the first two, since a matching hit can still lack an LRC.
        for (id in searchSongIds(song).take(2)) {
            val body = LyricHttp.get(
                "https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1",
                headers,
            ) ?: continue
            val lrc = runCatching { JSONObject(body).optJSONObject("lrc")?.optString("lyric") }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val parsed = runCatching { LrcParser.parse(lrc) }.getOrDefault(emptyList())
            return if (parsed.any { it.timeSec > 0f }) OnlineLyrics(synced = parsed)
            else OnlineLyrics(plain = plainToLines(lrc))
        }
        return OnlineLyrics()
    }

    private suspend fun searchSongIds(song: Song): List<Long> {
        val q = enc("${song.title} ${song.artist}".trim())
        val body = LyricHttp.get(
            "https://music.163.com/api/search/get/web?s=$q&type=1&offset=0&limit=${LyricMatch.TOP_N}",
            headers,
        ) ?: return emptyList()
        return LyricMatch.valid(song, parseSearch(body)).map { it.payload }
    }

    /** `result.songs[]`: `{id, name, artists[{name}], duration(ms)}` → candidates. */
    internal fun parseSearch(body: String): List<LyricMatch.Candidate<Long>> = runCatching {
        val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs")
            ?: return emptyList()
        (0 until songs.length()).mapNotNull { i ->
            val o = songs.getJSONObject(i)
            val id = o.optLong("id").takeIf { it != 0L } ?: return@mapNotNull null
            val artists = o.optJSONArray("artists")
            val artist = if (artists == null) null else
                (0 until artists.length()).joinToString(", ") { artists.getJSONObject(it).optString("name") }
            val ms = o.optLong("duration")
            LyricMatch.Candidate(
                title = o.optString("name"),
                artist = artist,
                durationSec = if (ms > 0) (ms / 1000).toInt() else null,
                payload = id,
            )
        }
    }.getOrDefault(emptyList())

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

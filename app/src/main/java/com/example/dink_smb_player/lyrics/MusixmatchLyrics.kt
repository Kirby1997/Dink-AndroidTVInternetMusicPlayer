package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.Song
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Musixmatch lyric provider via the public desktop-app API (the same token + macro
 * endpoints foo_openlyrics uses). It scrapes a usertoken from `token.get` — this is
 * unofficial and brittle (Musixmatch can change/expire it), so the provider is
 * **default-OFF** and used only as a last online fallback. Synced (richsynched) LRC
 * when available, else plain.
 */
object MusixmatchLyrics : OnlineLyricProvider {
    override val id = "musixmatch"
    override val label = "Musixmatch"
    override val defaultEnabled = false

    private const val APP_ID = "web-desktop-app-v1.0"
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Cookie" to "x-mxm-token-guid=",
    )

    @Volatile private var cachedToken: String? = null

    override fun fetch(song: Song): OnlineLyrics {
        if (song.title.isBlank() || song.artist.isBlank()) return OnlineLyrics()
        val token = token() ?: return OnlineLyrics()
        val url = buildString {
            append("https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get")
            append("?format=json&namespace=lyrics_richsynched&subtitle_format=lrc&app_id=$APP_ID")
            append("&usertoken=").append(token)
            append("&q_track=").append(enc(song.title))
            append("&q_artist=").append(enc(song.artist))
            if (song.durationSec > 0) append("&q_duration=").append(song.durationSec)
        }
        val body = LyricHttp.get(url, headers) ?: return OnlineLyrics()
        val lrc = extractSubtitle(body) ?: return OnlineLyrics()
        val parsed = runCatching { LrcParser.parse(lrc) }.getOrDefault(emptyList())
        return if (parsed.any { it.timeSec > 0f }) OnlineLyrics(synced = parsed)
        else OnlineLyrics(plain = plainToLines(lrc))
    }

    private fun token(): String? {
        cachedToken?.let { return it }
        val body = LyricHttp.get(
            "https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=$APP_ID&format=json",
            headers,
        ) ?: return null
        val t = runCatching {
            JSONObject(body).optJSONObject("message")?.optJSONObject("body")?.optString("user_token")
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "UpgradeOnlyUpgradeOnly" }
        cachedToken = t
        return t
    }

    /** Dig the LRC body out of the nested macro response. */
    private fun extractSubtitle(body: String): String? = runCatching {
        val macroBody = JSONObject(body)
            .getJSONObject("message").getJSONObject("body")
            .getJSONObject("macro_calls")
        val subtitleMsg = macroBody.getJSONObject("track.subtitles.get").getJSONObject("message")
        val list = subtitleMsg.getJSONObject("body").optJSONArray("subtitle_list") ?: return null
        if (list.length() == 0) return null
        list.getJSONObject(0).getJSONObject("subtitle").optString("subtitle_body").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.Song
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Genius lyric provider (genius.com) — the same approach foo_openlyrics' genius
 * source uses: a real search API plus a plain-text lyrics endpoint, so no HTML
 * scraping. Uses the public desktop-app API token foo_openlyrics ships with.
 *
 * Plain lyrics only (Genius has no timing). Lands in the unsynced fallback tier of
 * [LyricChain]; reliable because it's a fuzzy /search, not a URL-slug lookup.
 */
object GeniusLyrics : OnlineLyricProvider {
    override val id = "genius"
    override val label = "Genius"
    override val defaultEnabled = true

    // Public token from foo_openlyrics — Genius search/song API requires a bearer.
    private const val TOKEN = "ZTejoT_ojOEasIkT9WrMBhBQOz6eYKK5QULCMECmOhvwqjRZ6WbpamFe3geHnvp3"
    private val headers = mapOf(
        "Authorization" to "Bearer $TOKEN",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    )

    override fun fetch(song: Song): OnlineLyrics {
        if (song.title.isBlank()) return OnlineLyrics()
        val apiPath = searchApiPath(song) ?: return OnlineLyrics()
        val body = LyricHttp.get("https://api.genius.com$apiPath?text_format=plain", headers)
            ?: return OnlineLyrics()
        val plain = runCatching {
            JSONObject(body).getJSONObject("response").getJSONObject("song")
                .getJSONObject("lyrics").optString("plain")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return OnlineLyrics()
        return OnlineLyrics(plain = plainToLines(plain))
    }

    private fun searchApiPath(song: Song): String? {
        val q = enc("${song.artist} ${song.title}".trim())
        val body = LyricHttp.get("https://api.genius.com/search?q=$q", headers) ?: return null
        return runCatching {
            val hits = JSONObject(body).getJSONObject("response").optJSONArray("hits") ?: return null
            if (hits.length() == 0) return null
            hits.getJSONObject(0).getJSONObject("result").optString("api_path").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

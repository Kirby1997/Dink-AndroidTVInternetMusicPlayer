package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * LRCLIB lookup — https://lrclib.net/docs
 *
 * Free, no-auth public API used by foo_openlyrics. Returns both `syncedLyrics`
 * (an LRC string with `[mm:ss.xx]` timestamps) and `plainLyrics` (unsynced).
 *
 * Both are surfaced so callers can prefer the synced version and fall back to
 * the plain text only if no other synced source resolved.
 */
object LrcLibLookup {

    /** Combined result. Either field may be empty when the track is unknown. */
    data class Result(
        val synced: List<LyricLine> = emptyList(),
        val plain: List<LyricLine> = emptyList(),
        val instrumental: Boolean = false,
    )

    fun fetch(song: Song): Result {
        if (song.title.isBlank() || song.artist.isBlank()) return Result()

        // Try the exact /api/get first — fastest path when metadata + duration
        // match. LRCLIB's /api/get requires duration within 2s tolerance so
        // any drift (MediaStore vs LRCLIB-indexed duration) returns 404.
        httpGetOrNull(buildGetUrl(song))?.let { body ->
            val parsed = parseGetResponse(body)
            if (parsed.synced.isNotEmpty() || parsed.plain.isNotEmpty()) return parsed
        }

        // /api/search is fuzzy and returns a sorted JSON array. Pick first.
        httpGetOrNull(buildSearchUrl(song))?.let { body ->
            val parsed = parseSearchResponse(body)
            if (parsed.synced.isNotEmpty() || parsed.plain.isNotEmpty()) return parsed
        }

        return Result()
    }

    private fun buildGetUrl(song: Song): String {
        val pairs = mutableListOf(
            "track_name" to song.title,
            "artist_name" to song.artist,
        )
        if (!song.albumTitle.isNullOrBlank()) pairs += "album_name" to song.albumTitle
        if (song.durationSec > 0) pairs += "duration" to song.durationSec.toString()
        return "https://lrclib.net/api/get?" + encode(pairs)
    }

    private fun buildSearchUrl(song: Song): String {
        val pairs = mutableListOf(
            "track_name" to song.title,
            "artist_name" to song.artist,
        )
        return "https://lrclib.net/api/search?" + encode(pairs)
    }

    private fun encode(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

    private fun httpGetOrNull(url: String): String? {
        val conn = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        conn.setRequestProperty(
            "User-Agent",
            "Dink/1.0 (https://github.com/jjwilkinson/Dink-AndroidTVInternetMusicPlayer)",
        )
        return try {
            val code = conn.responseCode
            if (code !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseGetResponse(body: String): Result {
        val parsed = runCatching {
            json.decodeFromString(LrcLibResponse.serializer(), body)
        }.getOrNull() ?: return Result()
        return parsed.toResult()
    }

    private fun parseSearchResponse(body: String): Result {
        val list = runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(LrcLibResponse.serializer()), body)
        }.getOrNull().orEmpty()
        return list.firstOrNull { (it.syncedLyrics?.isNotBlank() == true || it.plainLyrics?.isNotBlank() == true) }
            ?.toResult()
            ?: Result()
    }

    private fun LrcLibResponse.toResult(): Result {
        val synced = syncedLyrics
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LrcParser.parse(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
        val plain = plainLyrics
            ?.takeIf { it.isNotBlank() }
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { LyricLine(timeSec = 0f, text = it) }
            ?.toList()
            ?: emptyList()
        return Result(synced = synced, plain = plain, instrumental = instrumental ?: false)
    }

    @Serializable
    private data class LrcLibResponse(
        val syncedLyrics: String? = null,
        val plainLyrics: String? = null,
        val instrumental: Boolean? = null,
    )
}

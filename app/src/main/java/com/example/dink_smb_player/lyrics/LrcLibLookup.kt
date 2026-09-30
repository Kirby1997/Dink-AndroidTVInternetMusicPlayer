package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * LRCLIB lookup — https://lrclib.net/docs
 *
 * Free, no-auth public API used by foo_openlyrics. Returns both `syncedLyrics`
 * (an LRC string with `[mm:ss.xx]` timestamps) and `plainLyrics` (unsynced).
 *
 * Both are surfaced so callers can prefer the synced version and fall back to
 * the plain text only if no other synced source resolved. `instrumental: true` records
 * are surfaced as [Result.instrumental] so the chain can stop instead of scraping plain
 * sites for lyrics that don't exist (LYR-10).
 */
object LrcLibLookup {

    /** Combined result. Either field may be empty when the track is unknown. */
    data class Result(
        val synced: List<LyricLine> = emptyList(),
        val plain: List<LyricLine> = emptyList(),
        val instrumental: Boolean = false,
    )

    suspend fun fetch(song: Song): Result {
        if (song.title.isBlank() || song.artist.isBlank()) return Result()

        // Try the exact /api/get first — fastest path when metadata + duration
        // match. LRCLIB's /api/get requires duration within 2s tolerance so
        // any drift (MediaStore vs LRCLIB-indexed duration) returns 404.
        LyricHttp.get(buildGetUrl(song), HEADERS)?.let { body ->
            val parsed = parseGetResponse(song, body)
            if (parsed.hasAnswer) return parsed
        }

        // /api/search is fuzzy and returns a sorted JSON array — validate the top hits
        // instead of trusting [0] (LYR-5).
        LyricHttp.get(buildSearchUrl(song), HEADERS)?.let { body ->
            val parsed = parseSearchResponse(song, body)
            if (parsed.hasAnswer) return parsed
        }

        return Result()
    }

    private val Result.hasAnswer get() = synced.isNotEmpty() || plain.isNotEmpty() || instrumental

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

    private val HEADERS = mapOf(
        "User-Agent" to "Dink/1.0 (https://github.com/jjwilkinson/Dink-AndroidTVInternetMusicPlayer)",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** /api/get returns one record; still validate it (the name match is fuzzy). */
    internal fun parseGetResponse(song: Song, body: String): Result {
        val parsed = runCatching {
            json.decodeFromString(LrcLibResponse.serializer(), body)
        }.getOrNull() ?: return Result()
        if (!LyricMatch.matches(song, parsed.candidate())) return Result()
        return parsed.toResult()
    }

    /**
     * Among the top [LyricMatch.TOP_N] hits that validate, prefer one with synced lyrics,
     * then plain, then an `instrumental` record (LYR-10) — a conflicting instrumental
     * entry shouldn't hide real lyrics filed for the same track.
     */
    internal fun parseSearchResponse(song: Song, body: String): Result {
        val list = runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(LrcLibResponse.serializer()), body)
        }.getOrNull().orEmpty()
        val valid = LyricMatch.valid(song, list.map { it.candidate() }).map { it.payload }
        val hit = valid.firstOrNull { it.syncedLyrics?.isNotBlank() == true }
            ?: valid.firstOrNull { it.plainLyrics?.isNotBlank() == true }
            ?: valid.firstOrNull { it.instrumental == true }
        return hit?.toResult() ?: Result()
    }

    private fun LrcLibResponse.candidate() = LyricMatch.Candidate(
        title = trackName,
        artist = artistName,
        durationSec = duration?.let { Math.round(it).toInt() },
        payload = this,
    )

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
        // Instrumental records carry no lyrics; only trust the flag when that's so.
        val isInstrumental = instrumental == true && synced.isEmpty() && plain.isEmpty()
        return Result(synced = synced, plain = plain, instrumental = isInstrumental)
    }

    @Serializable
    internal data class LrcLibResponse(
        val trackName: String? = null,
        val artistName: String? = null,
        val duration: Double? = null,
        val syncedLyrics: String? = null,
        val plainLyrics: String? = null,
        val instrumental: Boolean? = null,
    )
}

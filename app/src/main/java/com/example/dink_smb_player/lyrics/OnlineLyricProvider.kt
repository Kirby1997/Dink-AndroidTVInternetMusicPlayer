package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Result of an online lyric lookup. Either list may be empty. */
data class OnlineLyrics(
    val synced: List<LyricLine> = emptyList(),
    val plain: List<LyricLine> = emptyList(),
)

/**
 * One online lyric source in the foo_openlyrics-style chain. Each provider is
 * independently toggleable (see [LyricSettings]) and ordered in [OnlineLyricProviders].
 * [fetch] blocks on the network — only called from [LyricChain] off the main thread.
 */
interface OnlineLyricProvider {
    /** Stable id — also the [LyricSettings] / DataStore key. */
    val id: String
    val label: String
    /** Whether the provider is on by default before the user touches Settings. */
    val defaultEnabled: Boolean
    fun fetch(song: Song): OnlineLyrics
}

/**
 * Provider chain order. Synced-capable sources first (the chain returns the first
 * synced hit), then plain-text sources as the unsynced fallback tier. Full
 * foo_openlyrics set:
 *   synced: LRCLIB → NetEase → QQ → Lyricsify → Letras → Musixmatch
 *   plain:  Genius → DarkLyrics → MetalArchives → AZLyrics → SongLyrics → Bandcamp → LyricFind
 *
 * Each is independently toggleable ([LyricSettings]); the scrapers depend on accurate
 * tags (Phase 8.7). Reliable / metal-focused ones default ON; niche, captcha-prone, or
 * token-fragile ones default OFF (so the default fan-out on a no-lyrics track stays
 * small). The chain stops at the first synced hit, so plain scrapers only run when no
 * synced source matched.
 */
object OnlineLyricProviders {
    val all: List<OnlineLyricProvider> = listOf(
        // synced tier
        LrcLibProvider,
        NeteaseLyrics,
        QqLyrics,
        LyricsifyLyrics,
        LetrasLyrics,
        MusixmatchLyrics,
        // plain tier
        GeniusLyrics,
        DarkLyrics,
        MetalArchivesLyrics,
        AZLyrics,
        SongLyrics,
        BandcampLyrics,
        LyricFindLyrics,
    )
}

/** Shared HTTP for the lyric providers. Short timeouts — a slow lyric host should
 *  never stall playback start; the chain just falls through to the next source. */
internal object LyricHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** GET [url] with optional [headers]; returns body text or null on any failure. */
    fun get(url: String, headers: Map<String, String> = emptyMap()): String? {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return runCatching {
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()
    }
}

/** Convert a plain (unsynced) lyric blob to [LyricLine]s at t=0 (LyricChain spreads them). */
internal fun plainToLines(text: String): List<LyricLine> =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { LyricLine(timeSec = 0f, text = it) }
        .toList()

/** Adapts the existing [LrcLibLookup] to the provider interface. */
object LrcLibProvider : OnlineLyricProvider {
    override val id = "lrclib"
    override val label = "LRCLIB"
    override val defaultEnabled = true
    override fun fetch(song: Song): OnlineLyrics {
        val r = LrcLibLookup.fetch(song)
        return OnlineLyrics(synced = r.synced, plain = r.plain)
    }
}

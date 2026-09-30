package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Result of an online lyric lookup. Either list may be empty. [instrumental] = the
 *  provider positively identified the track as having no lyrics (LRCLIB, LYR-10). */
data class OnlineLyrics(
    val synced: List<LyricLine> = emptyList(),
    val plain: List<LyricLine> = emptyList(),
    val instrumental: Boolean = false,
)

/**
 * One online lyric source in the foo_openlyrics-style chain. Each provider is
 * independently toggleable (see [LyricSettings]) and ordered in [OnlineLyricProviders].
 * [fetch] suspends on the network and is cancellable: cancelling the caller cancels the
 * in-flight OkHttp call ([LyricHttp.get]). A network failure THROWS (IOException) so the
 * chain can tell "couldn't reach it" from "not found" and not cache a transient miss.
 */
interface OnlineLyricProvider {
    /** Stable id — also the [LyricSettings] / DataStore key. */
    val id: String
    val label: String
    /** Whether the provider is on by default before the user touches Settings. */
    val defaultEnabled: Boolean
    /** Can return synced LRC. Synced-capable providers are queried in parallel as the
     *  first tier; plain-only ones run afterwards, only when nothing plain exists yet. */
    val syncedCapable: Boolean get() = false
    suspend fun fetch(song: Song): OnlineLyrics
}

/**
 * Provider chain order. Synced-capable sources first (the chain returns the first
 * synced hit), then plain-text sources as the unsynced fallback tier. The
 * foo_openlyrics set minus Genius (needed a borrowed API token) and Musixmatch
 * (needed desktop-client impersonation) — both removed for store compliance:
 *   synced: LRCLIB → NetEase → QQ → Lyricsify → Letras
 *   plain:  DarkLyrics → MetalArchives → AZLyrics → SongLyrics → Bandcamp → LyricFind
 *
 * None run unless the master "Online lyrics" switch is on ([LyricSettings.online],
 * default OFF). Each is then independently toggleable; the scrapers depend on accurate
 * tags (Phase 8.7). Reliable / metal-focused ones default ON; niche, captcha-prone, or
 * token-fragile ones default OFF (so the default fan-out on a no-lyrics track stays
 * small). The synced tier runs in parallel and the best-priority synced hit wins; plain
 * scrapers only run when no synced source matched and no plain text exists yet.
 */
object OnlineLyricProviders {
    val all: List<OnlineLyricProvider> = listOf(
        // synced tier
        LrcLibProvider,
        NeteaseLyrics,
        QqLyrics,
        LyricsifyLyrics,
        LetrasLyrics,
        // plain tier
        DarkLyrics,
        MetalArchivesLyrics,
        AZLyrics,
        SongLyrics,
        BandcampLyrics,
        LyricFindLyrics,
    )
}

/** Shared HTTP for the lyric providers. Short timeouts — a slow lyric host should
 *  never stall playback start; the chain just falls through to the next source.
 *  [callTimeout] bounds the WHOLE request (connect + redirects + body), so one hung host
 *  can't hold a provider past it (LYR-6). */
internal object LyricHttp {
    const val CALL_TIMEOUT_SEC = 6L

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    /**
     * GET [url] with optional [headers]. Returns the body, or null when the host answered
     * but has nothing (404 and other 4xx — a definitive miss). Throws IOException when the
     * host couldn't be reached / timed out / answered 5xx or 429 (transient). Cancelling the
     * calling coroutine cancels the OkHttp call (LYR-8).
     */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String? {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // Ignored by the continuation if it was already cancelled.
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use { resp ->
                            when {
                                resp.isSuccessful -> resp.body?.string()
                                resp.code == 429 || resp.code >= 500 -> throw IOException("HTTP ${resp.code}")
                                else -> null
                            }
                        }
                    }
                    result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
            })
        }
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
    override val syncedCapable = true
    override suspend fun fetch(song: Song): OnlineLyrics {
        val r = LrcLibLookup.fetch(song)
        return OnlineLyrics(synced = r.synced, plain = r.plain, instrumental = r.instrumental)
    }
}

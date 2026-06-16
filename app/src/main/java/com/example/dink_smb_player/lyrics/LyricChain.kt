package com.example.dink_smb_player.lyrics

import android.content.Context
import android.util.Log
import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song

/**
 * foo_openlyrics-style provider chain. Resolution order prefers *synced* lyrics
 * (real `[mm:ss.xx]` timestamps) over plain text, regardless of source:
 *
 *   1. Sidecar `.lrc`         — user-curated, synced
 *   2. Online synced          — LRCLIB → NetEase → QQ → Musixmatch (first synced wins)
 *   3. ID3 USLT               — embedded, usually plain
 *   4. Online plain           — first plain from the same online chain
 *   5. Sidecar `.txt`         — user-curated, plain
 *
 * Online sources are each independently toggleable in Settings ([LyricSettings] /
 * [LyricPrefs]); disabled ones are skipped, and the loop stops at the first synced
 * hit so later (slower / more fragile) providers aren't queried unnecessarily.
 *
 * If no synced source resolves, the first non-empty plain source is returned,
 * distributed evenly across the song duration so karaoke still auto-scrolls.
 *
 * Runs on the calling thread — `DinkApp` wraps this in `withContext(IO)` since the
 * online lookups block on network round-trips.
 */
object LyricChain {

    private const val TAG = "LyricChain"

    fun resolve(context: Context, song: Song): List<LyricLine> {
        Log.i(TAG, "resolve start: title='${song.title}' artist='${song.artist}' dur=${song.durationSec}s path='${song.sourcePath}'")

        SidecarLyrics.load(context, song)
            .takeIf { it.isNotEmpty() }
            ?.let {
                Log.i(TAG, "sidecar .lrc hit (${it.size} lines)")
                return it
            }
        Log.i(TAG, "sidecar .lrc miss")

        // Online chain: query providers in order with the song's metadata as-is.
        // (Accurate title/artist will come from a later tag-reading phase; we don't
        // pre-clean filenames here.) Stop at the first synced result; remember the
        // first plain as a fallback for after ID3.
        val online = queryOnlineChain(song)
        if (online.synced.isNotEmpty()) return online.synced
        val firstPlain: List<LyricLine>? = online.plain.ifEmpty { null }

        Id3Lyrics.load(song)
            .takeIf { it.isNotEmpty() }
            ?.let {
                Log.i(TAG, "ID3 USLT hit (${it.size} lines)")
                return distributeIfFlat(it, song.durationSec)
            }
        Log.i(TAG, "ID3 USLT miss")

        if (firstPlain != null) return distributeIfFlat(firstPlain, song.durationSec)

        TxtLyrics.load(context, song)
            .takeIf { it.isNotEmpty() }
            ?.let {
                Log.i(TAG, "sidecar .txt hit (${it.size} lines)")
                return distributeIfFlat(it, song.durationSec)
            }
        Log.i(TAG, "sidecar .txt miss — no lyrics resolved")

        return emptyList()
    }

    /** Run the enabled online providers in order for one query; return the first
     *  synced result (else empty) plus the first plain result seen. */
    private fun queryOnlineChain(query: Song): OnlineLyrics {
        var firstPlain: List<LyricLine>? = null
        for (provider in OnlineLyricProviders.all) {
            if (!LyricSettings.isEnabled(provider.id)) {
                Log.i(TAG, "${provider.id} disabled — skip")
                continue
            }
            val r = runCatching { provider.fetch(query) }
                .onFailure { Log.w(TAG, "${provider.id} threw: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrDefault(OnlineLyrics())
            Log.i(TAG, "${provider.id}('${query.title}'): synced=${r.synced.size} plain=${r.plain.size}")
            if (r.synced.isNotEmpty()) return OnlineLyrics(synced = r.synced)
            if (firstPlain == null && r.plain.isNotEmpty()) firstPlain = r.plain
        }
        return OnlineLyrics(plain = firstPlain ?: emptyList())
    }

    /**
     * If [raw] carries no timing (all lines at 0, or a single multi-line blob),
     * spread the lines evenly across [durationSec] so karaoke / lyrics pane can
     * auto-scroll. Lines already carrying real timestamps are returned as-is.
     */
    private fun distributeIfFlat(raw: List<LyricLine>, durationSec: Int): List<LyricLine> {
        if (raw.isEmpty() || durationSec <= 0) return raw
        val hasTiming = raw.any { it.timeSec > 0f }
        if (hasTiming) return raw

        val lines: List<String> = if (raw.size == 1) {
            raw[0].text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            raw.map { it.text }
        }
        if (lines.isEmpty()) return raw

        val step = durationSec.toFloat() / lines.size
        return lines.mapIndexed { i, t -> LyricLine(timeSec = i * step, text = t) }
    }
}

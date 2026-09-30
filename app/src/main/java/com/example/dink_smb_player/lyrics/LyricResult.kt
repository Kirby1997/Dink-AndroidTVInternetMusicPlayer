package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.LyricLine

/**
 * What [LyricChain] resolved for a track. Plain lines are kept flat (t=0) — timings are
 * spread over the duration only when displayed ([toDisplayLines]) — so a cached result
 * stays valid when the track's duration becomes known later.
 */
sealed interface LyricResult {
    data class Synced(val lines: List<LyricLine>) : LyricResult
    data class Plain(val lines: List<LyricLine>) : LyricResult
    /** A provider positively identified the track as instrumental (LRCLIB, LYR-10). */
    data object Instrumental : LyricResult
    data object None : LyricResult

    companion object {
        const val INSTRUMENTAL_TEXT = "♪ Instrumental ♪"
    }
}

/**
 * Lines for the lyrics pane. Plain text is spread evenly across [durationSec] so it still
 * auto-scrolls; with an unknown duration it stays flat and the pane shows it without a
 * highlight (LYR-13). Instrumental is a single untimed line.
 */
fun LyricResult.toDisplayLines(durationSec: Int): List<LyricLine> = when (this) {
    is LyricResult.Synced -> lines
    is LyricResult.Plain -> distributeIfFlat(lines, durationSec)
    LyricResult.Instrumental -> listOf(LyricLine(0f, LyricResult.INSTRUMENTAL_TEXT))
    LyricResult.None -> emptyList()
}

/**
 * If [raw] carries no timing (all lines at 0, or a single multi-line blob),
 * spread the lines evenly across [durationSec] so karaoke / lyrics pane can
 * auto-scroll. Lines already carrying real timestamps are returned as-is.
 */
internal fun distributeIfFlat(raw: List<LyricLine>, durationSec: Int): List<LyricLine> {
    if (raw.isEmpty()) return raw
    val hasTiming = raw.any { it.timeSec > 0f }
    if (hasTiming) return raw

    val lines: List<String> = if (raw.size == 1) {
        raw[0].text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    } else {
        raw.map { it.text }
    }
    if (lines.isEmpty()) return raw
    if (durationSec <= 0) return lines.map { LyricLine(timeSec = 0f, text = it) }

    val step = durationSec.toFloat() / lines.size
    return lines.mapIndexed { i, t -> LyricLine(timeSec = i * step, text = t) }
}

package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.WordTiming

/**
 * Parser for the LRC lyric format used by foobar2000, MiniLyrics, and the
 * sidecar files Phase 6 reads next to media files.
 *
 * Supports:
 *  - Multiple leading timestamps per line — `[00:12.34][00:45.67]text` expands into two LyricLine entries.
 *  - Centisecond OR millisecond fractions — `[mm:ss.xx]` and `[mm:ss.xxx]`.
 *  - A2-enhanced inline word timings — `<mm:ss.xx>word`.
 *  - Metadata tags (`[ti:`, `[ar:`, `[al:`, `[by:`, `[offset:`, `[length:`, `[re:`, `[ve:`) are skipped.
 *
 * Offset tag (`[offset:+250]`, in milliseconds) is honoured globally. Per the LRC
 * convention a positive offset makes lyrics appear *sooner* (`t - offset`).
 *
 * Sidecar files arrive in whatever encoding the tagger wrote — read them through
 * [decode] (BOM / UTF-16 sniffing) rather than `readText()`, and [parse] drops any
 * stray U+FEFF so the first timestamp still matches.
 */
object LrcParser {

    private val LINE_STAMP = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_STAMP = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val META_TAG = Regex("""^\[(ti|ar|al|by|offset|length|re|ve|au|id):.*]$""", RegexOption.IGNORE_CASE)
    private val OFFSET_TAG = Regex("""^\[offset:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)

    private const val BOM = '\uFEFF'

    /**
     * Decode raw lyric-file bytes. Honours a UTF-8 / UTF-16 BOM, sniffs BOM-less
     * UTF-16 (ASCII-heavy LRC leaves every other byte zero), else UTF-8.
     */
    fun decode(bytes: ByteArray): String {
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        if (bytes.size >= 3 && b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && b(0) == 0xFF && b(1) == 0xFE) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && b(0) == 0xFE && b(1) == 0xFF) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val n = minOf(bytes.size, 256) and 1.inv()
        if (n >= 4) {
            var evenZeros = 0
            var oddZeros = 0
            for (i in 0 until n) if (bytes[i].toInt() == 0) { if (i % 2 == 0) evenZeros++ else oddZeros++ }
            val half = n / 2
            if (oddZeros * 10 >= half * 7 && evenZeros * 10 < half) return String(bytes, Charsets.UTF_16LE).trimStart(BOM)
            if (evenZeros * 10 >= half * 7 && oddZeros * 10 < half) return String(bytes, Charsets.UTF_16BE).trimStart(BOM)
        }
        return String(bytes, Charsets.UTF_8).trimStart(BOM)
    }

    fun parse(text: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        var offsetSec = 0f

        text.lineSequence().forEach { raw ->
            val line = raw.trim().trimStart(BOM).trim()
            if (line.isEmpty()) return@forEach

            OFFSET_TAG.matchEntire(line)?.let {
                offsetSec = (it.groupValues[1].toIntOrNull() ?: 0) / 1000f
                return@forEach
            }
            if (META_TAG.matchEntire(line) != null) return@forEach

            val stamps = mutableListOf<Float>()
            var rest = line
            while (true) {
                val m = LINE_STAMP.find(rest) ?: break
                stamps += stampToSeconds(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                rest = rest.substring(m.range.last + 1)
            }
            if (stamps.isEmpty()) return@forEach

            val payload = rest
            val words = extractWords(payload)
            val cleanText = payload.replace(WORD_STAMP, "").trim()
            if (cleanText.isEmpty() && words.isEmpty()) {
                stamps.forEach { out += LyricLine(it, "") }
                return@forEach
            }
            stamps.forEach { stamp ->
                out += LyricLine(timeSec = stamp, text = cleanText, words = words)
            }
        }

        val shifted = if (offsetSec != 0f) {
            out.map { it.copy(timeSec = (it.timeSec - offsetSec).coerceAtLeast(0f)) }
        } else {
            out
        }
        return shifted.sortedBy { it.timeSec }
    }

    private fun stampToSeconds(mm: String, ss: String, frac: String): Float {
        val minutes = mm.toInt()
        val seconds = ss.toInt()
        val fractional = if (frac.isEmpty()) 0f else when (frac.length) {
            1 -> frac.toInt() / 10f
            2 -> frac.toInt() / 100f
            3 -> frac.toInt() / 1000f
            else -> frac.toInt() / 1000f
        }
        return minutes * 60f + seconds + fractional
    }

    private fun extractWords(payload: String): List<WordTiming> {
        val matches = WORD_STAMP.findAll(payload).toList()
        if (matches.isEmpty()) return emptyList()
        val firstStamp = stampToSeconds(matches[0].groupValues[1], matches[0].groupValues[2], matches[0].groupValues[3])
        val words = mutableListOf<WordTiming>()
        for (i in matches.indices) {
            val m = matches[i]
            val start = stampToSeconds(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            val wordEnd = matches.getOrNull(i + 1)?.range?.first ?: payload.length
            val wordText = payload.substring(m.range.last + 1, wordEnd)
            if (wordText.isNotEmpty()) {
                words += WordTiming(offsetSec = (start - firstStamp).coerceAtLeast(0f), text = wordText)
            }
        }
        return words
    }
}

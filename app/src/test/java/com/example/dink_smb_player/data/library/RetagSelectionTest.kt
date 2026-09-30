package com.example.dink_smb_player.data.library

import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Retag's row-selection heuristics: bogus (truncated-probe) durations and mojibake names. */
class RetagSelectionTest {

    private fun row(sizeBytes: Long, durationMs: Long) = TrackEntity(
        id = "t", title = "t", durationMs = durationMs, sourceType = SourceType.Smb, sourceId = "s",
        path = "/m/t.mp3", uri = "smb://nas/m/t.mp3", sizeBytes = sizeBytes, addedAtMs = 0,
    )

    @Test
    fun `duration is bogus only when the implied bitrate exceeds 700 kbps`() {
        data class Case(val size: Long, val durationMs: Long, val bogus: Boolean)
        val cases = listOf(
            Case(8_000_000, 200_000, false),   // 8 MB over 3:20 = 320 kbps MP3
            Case(40_000_000, 240_000, true),   // ~1333 kbps (CD FLAC) also re-probes — harmless, idempotent
            Case(8_000_000, 11_000, true),     // the 0:11 truncated-probe artifact (~5800 kbps)
            Case(7_000_000, 80_000, false),    // exactly 700 kbps: not above the ceiling
            Case(7_000_001, 80_000, true),
            Case(0, 11_000, false),            // unknown size → can't judge
            Case(8_000_000, 0, false),         // no duration → the missing-duration rule handles it
        )
        for (c in cases) {
            assertEquals("size=${c.size} dur=${c.durationMs}", c.bogus, LibraryRepository.durationLooksBogus(row(c.size, c.durationMs)))
        }
    }

    @Test
    fun `mojibake is UTF-8 decoded as Latin-1, or a replacement char`() {
        val cases = listOf(
            "CafÃ©" to true,        // é → Ã©
            "BeyoncÃ©" to true,
            "MotÃ¶rhead" to true,     // ö → Ã¶
            "bad \uFFFD char" to true,
            "Motörhead" to false,
            "Sigur Rós" to false,
            "Ã" to false,           // lone lead byte at the end: no continuation
            "Plain ASCII" to false,
            null to false,
        )
        for ((s, expected) in cases) assertEquals("looksMojibake($s)", expected, LibraryRepository.looksMojibake(s))
    }
}

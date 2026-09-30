package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricDisplayTest {

    private val plain = LyricResult.Plain(listOf(LyricLine(0f, "a"), LyricLine(0f, "b")))

    @Test
    fun `plain lyrics spread over a known duration`() {
        assertEquals(listOf(0f, 50f), plain.toDisplayLines(100).map { it.timeSec })
    }

    @Test
    fun `unknown duration leaves plain lyrics untimed`() {
        assertTrue(plain.toDisplayLines(0).all { it.timeSec == 0f })
        val blob = LyricResult.Plain(listOf(LyricLine(0f, "x\ny")))
        assertEquals(listOf("x", "y"), blob.toDisplayLines(0).map { it.text })
    }

    @Test
    fun `instrumental shows a single untimed marker`() {
        val lines = LyricResult.Instrumental.toDisplayLines(200)
        assertEquals(listOf(LyricLine(0f, LyricResult.INSTRUMENTAL_TEXT)), lines)
        assertTrue(LyricResult.None.toDisplayLines(200).isEmpty())
    }
}

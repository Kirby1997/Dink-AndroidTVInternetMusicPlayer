package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-N #10: spreading untimed (plain) lyric lines across the track for auto-scroll. */
class DistributeIfFlatTest {

    @Test
    fun `timed lines and empty input pass through untouched`() {
        val timed = listOf(LyricLine(0f, "a"), LyricLine(12.5f, "b"))
        assertSame(timed, distributeIfFlat(timed, 200))
        val empty = emptyList<LyricLine>()
        assertSame(empty, distributeIfFlat(empty, 200))
    }

    @Test
    fun `flat lines are spread evenly over the duration`() {
        val out = distributeIfFlat(listOf(LyricLine(0f, "a"), LyricLine(0f, "b"), LyricLine(0f, "c"), LyricLine(0f, "d")), 100)
        assertEquals(listOf(0f, 25f, 50f, 75f), out.map { it.timeSec })
        assertEquals(listOf("a", "b", "c", "d"), out.map { it.text })
    }

    @Test
    fun `a single multi-line blob is split, trimmed and blank lines dropped`() {
        val out = distributeIfFlat(listOf(LyricLine(0f, "  one \n\n two\n   \nthree  ")), 90)
        assertEquals(listOf("one", "two", "three"), out.map { it.text })
        assertEquals(listOf(0f, 30f, 60f), out.map { it.timeSec })
    }

    @Test
    fun `unknown duration keeps lines flat, an all-blank blob is returned as is`() {
        val out = distributeIfFlat(listOf(LyricLine(0f, "x\ny")), 0)
        assertEquals(listOf("x", "y"), out.map { it.text })
        assertTrue(out.all { it.timeSec == 0f })
        val blank = listOf(LyricLine(0f, " \n "))
        assertSame(blank, distributeIfFlat(blank, 100))
    }
}

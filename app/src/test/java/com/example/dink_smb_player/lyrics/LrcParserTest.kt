package com.example.dink_smb_player.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    private fun times(text: String) = LrcParser.parse(text).map { it.timeSec }

    @Test
    fun `positive offset makes lyrics appear sooner`() {
        val t = times("[offset:+500]\n[00:10.00]a\n[00:20.00]b")
        assertEquals(listOf(9.5f, 19.5f), t)
    }

    @Test
    fun `negative offset delays lyrics`() {
        assertEquals(listOf(10.25f), times("[offset:-250]\n[00:10.00]a"))
    }

    @Test
    fun `offset never pushes a line below zero`() {
        assertEquals(listOf(0f), times("[offset:5000]\n[00:01.00]a"))
    }

    @Test
    fun `overflowing offset is ignored instead of throwing`() {
        assertEquals(listOf(10f), times("[offset:99999999999]\n[00:10.00]a"))
    }

    @Test
    fun `leading BOM does not swallow the first line`() {
        val lines = LrcParser.parse("\uFEFF[00:01.00]first\n[00:02.00]second")
        assertEquals(listOf("first", "second"), lines.map { it.text })
    }

    @Test
    fun `BOM before a meta tag is still treated as meta`() {
        val lines = LrcParser.parse("\uFEFF[ti:Song]\n[00:01.00]x")
        assertEquals(listOf("x"), lines.map { it.text })
    }

    @Test
    fun `multiple stamps expand into sorted lines`() {
        val lines = LrcParser.parse("[00:30.00][00:10.50]chorus\n[00:20.000]verse")
        assertEquals(listOf(10.5f, 20f, 30f), lines.map { it.timeSec })
        assertEquals(listOf("chorus", "verse", "chorus"), lines.map { it.text })
    }

    @Test
    fun `decode strips UTF-8 BOM`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "[00:01.00]é".toByteArray(Charsets.UTF_8)
        assertEquals("[00:01.00]é", LrcParser.decode(bytes))
    }

    @Test
    fun `decode honours UTF-16 BOMs`() {
        val text = "[00:01.00]héllo 世界"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        assertEquals(text, LrcParser.decode(le))
        assertEquals(text, LrcParser.decode(be))
    }

    @Test
    fun `decode sniffs BOM-less UTF-16`() {
        val text = "[ti:Song]\n[00:01.00]hello\n[00:02.00]world"
        assertEquals(text, LrcParser.decode(text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, LrcParser.decode(text.toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun `decode leaves plain UTF-8 alone`() {
        val text = "[00:01.00]naïve 日本語"
        assertEquals(text, LrcParser.decode(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `UTF-16 file parses end to end`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "[00:01.00]one\r\n[00:02.00]two".toByteArray(Charsets.UTF_16LE)
        val lines = LrcParser.parse(LrcParser.decode(bytes))
        assertEquals(listOf("one", "two"), lines.map { it.text })
        assertTrue(lines.all { it.timeSec > 0f })
    }
}

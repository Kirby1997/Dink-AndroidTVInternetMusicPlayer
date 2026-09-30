package com.example.dink_smb_player.lyrics

import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.example.dink_smb_player.data.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class EmbeddedLyricsTest {

    private fun uslt(enc: Int, desc: String, text: String): ByteArray {
        val cs = charsetOf(enc)
        val out = ByteArrayOutputStream()
        out.write(enc); out.write("eng".toByteArray())
        out.write(desc.toByteArray(cs)); out.write(term(enc))
        out.write(text.toByteArray(cs))
        return out.toByteArray()
    }

    private fun sylt(enc: Int, format: Int, entries: List<Pair<String, Int>>): ByteArray {
        val cs = charsetOf(enc)
        val out = ByteArrayOutputStream()
        out.write(enc); out.write("eng".toByteArray()); out.write(format); out.write(1)
        out.write(term(enc)) // empty descriptor
        for ((t, ms) in entries) {
            out.write(t.toByteArray(cs)); out.write(term(enc))
            out.write(ms ushr 24); out.write(ms ushr 16); out.write(ms ushr 8); out.write(ms)
        }
        return out.toByteArray()
    }

    private fun charsetOf(enc: Int) = when (enc) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }

    private fun term(enc: Int) = if (enc == 1 || enc == 2) byteArrayOf(0, 0) else byteArrayOf(0)

    @Test
    fun `USLT text is read in every ID3 encoding`() {
        assertEquals("Café lyrics", EmbeddedLyrics.parseUslt(uslt(0, "d", "Café lyrics")))
        assertEquals("晴天 lyrics", EmbeddedLyrics.parseUslt(uslt(3, "desc", "晴天 lyrics")))
        assertEquals("晴天 lyrics", EmbeddedLyrics.parseUslt(uslt(1, "desc", "晴天 lyrics")))
        assertEquals("abc", EmbeddedLyrics.parseUslt(uslt(2, "", "abc")))
    }

    @Test
    fun `SYLT millisecond stamps become synced lines`() {
        val lines = EmbeddedLyrics.parseSylt(sylt(3, 2, listOf("First" to 1500, "Second" to 4000)))!!
        assertEquals(listOf(LyricLine(1.5f, "First"), LyricLine(4f, "Second")), lines)
    }

    @Test
    fun `word-level SYLT is joined into lines at newline markers`() {
        val lines = EmbeddedLyrics.parseSylt(
            sylt(1, 2, listOf("Hel" to 1000, "lo" to 1200, "\nWorld" to 3000, " again" to 3500)),
        )!!
        assertEquals(listOf(LyricLine(1f, "Hello"), LyricLine(3f, "World again")), lines)
    }

    @Test
    fun `SYLT with MPEG frame stamps is not usable`() {
        assertNull(EmbeddedLyrics.parseSylt(sylt(3, 1, listOf("x" to 100))))
    }

    @Test
    fun `entries prefer SYLT then LRC text then plain USLT`() {
        val plain = BinaryFrame("USLT", uslt(3, "", "just words\nmore words"))
        val syl = BinaryFrame("SYLT", sylt(3, 2, listOf("timed" to 2000)))
        assertEquals(LyricResult.Synced(listOf(LyricLine(2f, "timed"))), EmbeddedLyrics.fromEntries(listOf(plain, syl)))

        val lrcInTag = VorbisComment("LYRICS", "[00:03.00]from flac")
        val r = EmbeddedLyrics.fromEntries(listOf(plain, lrcInTag))
        assertEquals("from flac", (r as LyricResult.Synced).lines.single().text)

        val p = EmbeddedLyrics.fromEntries(listOf(plain)) as LyricResult.Plain
        assertEquals(listOf("just words", "more words"), p.lines.map { it.text })
    }

    @Test
    fun `mp4 lyric atom and TXXX lyrics are recognised`() {
        val mp4 = TextInformationFrame("USLT", null, listOf("mp4 words"))
        assertTrue(EmbeddedLyrics.fromEntries(listOf(mp4)) is LyricResult.Plain)
        val txxx = TextInformationFrame("TXXX", "LYRICS", listOf("txxx words"))
        assertTrue(EmbeddedLyrics.fromEntries(listOf(txxx)) is LyricResult.Plain)
        val other = TextInformationFrame("TIT2", null, listOf("a title"))
        assertEquals(LyricResult.None, EmbeddedLyrics.fromEntries(listOf(other)))
    }
}

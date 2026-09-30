package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricMatchTest {

    private fun song(title: String, artist: String, dur: Int = 0) =
        Song("id", title, artist, null, null, dur, 0, "", "MP3")

    private fun c(title: String?, artist: String?, dur: Int? = null, tag: String = title ?: "") =
        LyricMatch.Candidate(title, artist, dur, tag)

    @Test
    fun `title ignores case punctuation and version decorations`() {
        assertTrue(LyricMatch.titleMatches("Wait and Bleed", "Wait And Bleed (Remastered 2011)"))
        assertTrue(LyricMatch.titleMatches("Paranoid - 2012 Remaster", "Paranoid"))
        assertTrue(LyricMatch.titleMatches("Crazy [Live]", "crazy"))
        assertTrue(LyricMatch.titleMatches("Café del Mar", "Cafe Del Mar"))
        assertTrue(LyricMatch.titleMatches("Song feat. Someone", "Song"))
        assertTrue(LyricMatch.titleMatches("晴天", "晴天"))
    }

    @Test
    fun `title rejects a different song that merely contains it`() {
        assertFalse(LyricMatch.titleMatches("One", "Alone"))
        assertFalse(LyricMatch.titleMatches("One", "One More Time"))
        assertFalse(LyricMatch.titleMatches("One", null))
        assertFalse(LyricMatch.titleMatches("One", ""))
    }

    @Test
    fun `artist matches credits collabs and the article`() {
        assertTrue(LyricMatch.artistMatches("Slipknot", "slipknot"))
        assertTrue(LyricMatch.artistMatches("The Beatles", "Beatles"))
        assertTrue(LyricMatch.artistMatches("Daft Punk feat. Pharrell Williams", "Daft Punk"))
        assertTrue(LyricMatch.artistMatches("Jay-Z", "Jay-Z, Kanye West"))
        assertTrue(LyricMatch.artistMatches("Simon & Garfunkel", "Simon & Garfunkel"))
        assertFalse(LyricMatch.artistMatches("Metallica", "Apocalyptica"))
    }

    @Test
    fun `unknown artist on either side does not block a match`() {
        assertTrue(LyricMatch.artistMatches("Unknown", "Metallica"))
        assertTrue(LyricMatch.artistMatches("", "Metallica"))
        assertTrue(LyricMatch.artistMatches("Metallica", null))
    }

    @Test
    fun `duration must be within three seconds when both are known`() {
        assertTrue(LyricMatch.durationMatches(200, 203))
        assertTrue(LyricMatch.durationMatches(200, 197))
        assertFalse(LyricMatch.durationMatches(200, 204))
        assertFalse(LyricMatch.durationMatches(200, 150))
        assertTrue(LyricMatch.durationMatches(0, 150))
        assertTrue(LyricMatch.durationMatches(200, null))
        assertTrue(LyricMatch.durationMatches(200, 0))
    }

    @Test
    fun `pick skips invalid leading hits and returns the first valid one`() {
        val s = song("One", "Metallica", 446)
        val hits = listOf(
            c("Alone", "Metallica", 446, "wrong-title"),
            c("One", "U2", 276, "wrong-artist"),
            c("One", "Metallica", 460, "live-cut-too-long"),
            c("One (Remastered)", "Metallica", 447, "good"),
            c("One", "Metallica", 446, "also-good"),
        )
        assertEquals("good", LyricMatch.pick(s, hits)?.payload)
        assertEquals(listOf("good", "also-good"), LyricMatch.valid(s, hits).map { it.payload })
    }

    @Test
    fun `pick only scans the top N hits`() {
        val s = song("One", "Metallica")
        val hits = List(LyricMatch.TOP_N) { c("Other $it", "Metallica") } + c("One", "Metallica")
        assertNull(LyricMatch.pick(s, hits))
        assertEquals("One", LyricMatch.pick(s, hits, topN = hits.size)?.payload)
    }
}

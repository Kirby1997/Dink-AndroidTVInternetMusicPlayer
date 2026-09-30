package com.example.dink_smb_player.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarNameMatchTest {

    private fun m(file: String, title: String, artist: String = "") =
        SidecarLyrics.nameMatches(file, title, artist)

    @Test
    fun `title that is a substring of another name does not match`() {
        assertFalse(m("Alone", "One"))
        assertFalse(m("Metallica - Alone Again", "One", "Metallica"))
        assertFalse(m("One More Time", "One"))
    }

    @Test
    fun `same name ignoring case and punctuation matches`() {
        assertTrue(m("wait and bleed", "Wait And Bleed"))
        assertTrue(m("WaitAndBleed", "Wait And Bleed"))
    }

    @Test
    fun `artist dash title matches`() {
        assertTrue(m("Slipknot - Wait And Bleed", "Wait And Bleed", "Slipknot"))
        assertTrue(m("Starbenders_Cold Silver", "Cold Silver", "Starbenders"))
    }

    @Test
    fun `leading track number is ignored`() {
        assertTrue(m("01 - Wait And Bleed", "Wait And Bleed"))
        assertTrue(m("07. Slipknot - Wait And Bleed", "Wait And Bleed", "Slipknot"))
        assertFalse(m("01 Alone", "One"))
    }

    @Test
    fun `non-latin titles match by equality`() {
        assertTrue(m("周杰伦 - 晴天", "晴天", "周杰伦"))
        assertFalse(m("晴天", "雨天"))
    }

    @Test
    fun `blank title never matches`() {
        assertFalse(m("anything", ""))
        assertFalse(m("", "!!!"))
    }
}

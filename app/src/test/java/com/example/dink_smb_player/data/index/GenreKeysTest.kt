package com.example.dink_smb_player.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreKeysTest {

    @Test
    fun `id3v1 table has 192 entries with known anchors`() {
        assertEquals(192, GenreKeys.ID3V1.size)
        assertEquals("Blues", GenreKeys.id3v1Name(0))
        assertEquals("Rock", GenreKeys.id3v1Name(17))
        assertEquals("Hard Rock", GenreKeys.id3v1Name(79))
        assertEquals("Psybient", GenreKeys.id3v1Name(191))
        assertNull(GenreKeys.id3v1Name(255))
        assertNull(GenreKeys.id3v1Name(-1))
    }

    @Test
    fun `numeric ID3 forms resolve, text part wins`() {
        assertEquals("Rock", GenreKeys.resolveDisplay("(17)"))
        assertEquals("Rock", GenreKeys.resolveDisplay("17"))
        assertEquals("Indie", GenreKeys.resolveDisplay("(17)Indie"))
        assertEquals("Remix", GenreKeys.resolveDisplay("(RX)"))
        assertEquals("Cover", GenreKeys.resolveDisplay("(CR)"))
        assertEquals("Shoegaze", GenreKeys.resolveDisplay("  Shoegaze "))
        assertNull(GenreKeys.resolveDisplay("(255)"))
        assertNull(GenreKeys.resolveDisplay("   "))
        assertNull(GenreKeys.resolveDisplay(null))
        assertEquals("1990s", GenreKeys.resolveDisplay("1990s"))
    }

    @Test
    fun `keys fold case, spacing and punctuation`() {
        assertEquals(setOf("hiphop"), GenreKeys.keysOf("Hip-Hop"))
        assertEquals(setOf("hiphop"), GenreKeys.keysOf("hip hop"))
        assertEquals(setOf("hiphop"), GenreKeys.keysOf("HipHop"))
        assertEquals(setOf("rb"), GenreKeys.keysOf("R&B"))
    }

    @Test
    fun `keys split multi-genre values`() {
        assertEquals(setOf("rock", "indie", "postpunk", "shoegaze"), GenreKeys.keysOf("Rock; Indie / Post-Punk, Shoegaze"))
        assertEquals(setOf("rock", "pop"), GenreKeys.keysOf("Rock\u0000Pop"))
        assertEquals(setOf("rock"), GenreKeys.keysOf("(17)"))
    }

    @Test
    fun `keys split pipe and backslash separators, not ampersand`() {
        assertEquals(setOf("heavymetal", "hardrock", "nwobhm"), GenreKeys.keysOf("Heavy Metal | Hard Rock | NWOBHM"))
        assertEquals(setOf("rock", "pop"), GenreKeys.keysOf("Rock\\Pop"))
        assertEquals(setOf("rb"), GenreKeys.keysOf("R&B"))
        assertEquals(setOf("drumbass"), GenreKeys.keysOf("Drum & Bass"))
    }

    @Test
    fun `placeholders and blanks give no keys`() {
        assertTrue(GenreKeys.keysOf(null).isEmpty())
        assertTrue(GenreKeys.keysOf("").isEmpty())
        assertTrue(GenreKeys.keysOf("Other").isEmpty())
        assertTrue(GenreKeys.keysOf("Unknown; Genre").isEmpty())
        assertEquals(setOf("jazz"), GenreKeys.keysOf("misc; Jazz"))
    }

    @Test
    fun `remix and cover codes are not genre keys`() {
        assertEquals(emptySet<String>(), GenreKeys.keysOf("(RX)"))
        assertEquals(emptySet<String>(), GenreKeys.keysOf("(CR)"))
        assertEquals("Remix", GenreKeys.resolveDisplay("(RX)"))
        assertEquals("Cover", GenreKeys.resolveDisplay("(CR)"))
    }
}

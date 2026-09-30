package com.example.dink_smb_player.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SidecarLookupTest {

    @Test
    fun `same basename wins case-insensitively over a title match`() {
        val names = listOf("Metallica - One.lrc", "01 - one.LRC", "01 - One.flac")
        val r = SidecarLyrics.locate(names, "01 - One.flac", "One", "Metallica")
        assertEquals("01 - one.LRC", r.lrc)
        assertNull(r.txt)
    }

    @Test
    fun `title and artist-title names match but substrings do not`() {
        val names = listOf("Alone.lrc", "Metallica - One.lrc", "One.txt")
        val r = SidecarLyrics.locate(names, "track01.mp3", "One", "Metallica")
        assertEquals("Metallica - One.lrc", r.lrc)
        assertEquals("One.txt", r.txt)
    }

    @Test
    fun `no sidecar in the listing`() {
        val r = SidecarLyrics.locate(listOf("a.mp3", "cover.jpg", ".lrc"), "a2.mp3", "A", "B")
        assertNull(r.lrc)
        assertNull(r.txt)
    }

    @Test
    fun `smb track uri parses into share directory and file`() {
        val d = SmbSidecarDir.parse("smb://nas:445/Music/Metallica/1988%20-%20Justice/01%20-%20One.mp3?sid=s%201")!!
        assertEquals("Metallica\\1988 - Justice", d.dirPath)
        assertEquals("01 - One.mp3", d.audioName)
        assertEquals(
            "smb://nas:445/Music/Metallica/1988%20-%20Justice/01%20-%20One%2B.lrc?sid=s%201",
            d.siblingUri("01 - One+.lrc"),
        )
    }

    @Test
    fun `smb file at the share root has an empty directory path`() {
        val d = SmbSidecarDir.parse("smb://nas:445/Music/song.mp3?sid=x")!!
        assertEquals("", d.dirPath)
        assertEquals("song.mp3", d.audioName)
    }

    @Test
    fun `malformed smb uris are rejected`() {
        assertNull(SmbSidecarDir.parse("smb://nas:445/Music/song.mp3"))  // no sid
        assertNull(SmbSidecarDir.parse("smb://nas:445/song.mp3?sid=x"))  // no share
        assertNull(SmbSidecarDir.parse("file:///sdcard/song.mp3"))
    }
}

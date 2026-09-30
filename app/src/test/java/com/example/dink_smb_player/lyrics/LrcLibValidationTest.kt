package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcLibValidationTest {

    private val song = Song("id", "One", "Metallica", null, "...And Justice for All", 446, 0, "", "MP3")

    private fun rec(
        track: String,
        artist: String,
        dur: Double?,
        synced: String? = null,
        plain: String? = null,
        instrumental: Boolean = false,
    ): String {
        fun q(s: String?) = if (s == null) "null" else "\"" + s.replace("\n", "\\n") + "\""
        return """{"trackName":${q(track)},"artistName":${q(artist)},"duration":${dur ?: "null"},""" +
            """"syncedLyrics":${q(synced)},"plainLyrics":${q(plain)},"instrumental":$instrumental}"""
    }

    @Test
    fun `search skips a wrong first hit and takes the validated synced one`() {
        val body = "[" + listOf(
            rec("Alone", "Metallica", 446.0, synced = "[00:01.00]wrong song"),
            rec("One", "Metallica", 446.2, synced = "[00:05.00]I can't remember anything"),
        ).joinToString(",") + "]"
        val r = LrcLibLookup.parseSearchResponse(song, body)
        assertEquals("I can't remember anything", r.synced.single().text)
    }

    @Test
    fun `search prefers a valid synced hit over an earlier valid plain one`() {
        val body = "[" + listOf(
            rec("One", "Metallica", 446.0, plain = "plain words"),
            rec("One", "Metallica", 447.0, synced = "[00:05.00]synced words"),
        ).joinToString(",") + "]"
        assertEquals("synced words", LrcLibLookup.parseSearchResponse(song, body).synced.single().text)
    }

    @Test
    fun `search rejects hits whose duration is off by more than three seconds`() {
        val body = "[" + rec("One", "Metallica", 600.0, synced = "[00:05.00]live version") + "]"
        val r = LrcLibLookup.parseSearchResponse(song, body)
        assertTrue(r.synced.isEmpty() && r.plain.isEmpty() && !r.instrumental)
    }

    @Test
    fun `instrumental record is reported as instrumental`() {
        val body = rec("One", "Metallica", 446.0, instrumental = true)
        val r = LrcLibLookup.parseGetResponse(song, body)
        assertTrue(r.instrumental)
        assertTrue(r.synced.isEmpty())
    }

    @Test
    fun `instrumental flag with actual lyrics is not treated as instrumental`() {
        val body = rec("One", "Metallica", 446.0, synced = "[00:05.00]words", instrumental = true)
        val r = LrcLibLookup.parseGetResponse(song, body)
        assertFalse(r.instrumental)
        assertEquals(1, r.synced.size)
    }

    @Test
    fun `get response for another artist is rejected`() {
        val body = rec("One", "U2", 446.0, synced = "[00:05.00]one love")
        assertTrue(LrcLibLookup.parseGetResponse(song, body).synced.isEmpty())
    }
}

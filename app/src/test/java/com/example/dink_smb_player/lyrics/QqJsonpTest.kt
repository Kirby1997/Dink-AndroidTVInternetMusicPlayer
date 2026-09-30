package com.example.dink_smb_player.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class QqJsonpTest {

    @Test
    fun `plain JSON containing parentheses is left intact`() {
        val body = """{"retcode":0,"lyric":"[00:01.00]Hello (feat. Someone)\n[00:02.00]bye (live)"}"""
        assertEquals(body, QqLyrics.unwrapJsonp(body))
    }

    @Test
    fun `JSONP callback is unwrapped`() {
        val json = """{"lyric":"[00:01.00]a (b)"}"""
        assertEquals(json, QqLyrics.unwrapJsonp("MusicJsonCallback($json)"))
        assertEquals(json, QqLyrics.unwrapJsonp("  cb($json);\n"))
    }

    @Test
    fun `leading whitespace before JSON is tolerated`() {
        assertEquals("""{"lyric":"x (y)"}""", QqLyrics.unwrapJsonp("\n  {\"lyric\":\"x (y)\"}  "))
    }
}

package com.example.dink_smb_player.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseParserTest {
    private val json = """
    [
      {"tag_name":"v1.4.0","name":"Dink 1.4.0","body":"old","draft":false,"prerelease":false,"published_at":"2026-09-01T10:00:00Z","html_url":"u1","extra":1,
       "assets":[{"name":"dink-1.4.0.apk","browser_download_url":"https://x/dink-1.4.0.apk","size":100}]},
      {"tag_name":"v1.6.0","name":"Draft","body":"d","draft":true,"prerelease":false,"assets":[]},
      {"tag_name":"v1.5.0","name":"Dink 1.5.0","body":"### Radio\n- thing","draft":false,"prerelease":false,"published_at":"2026-10-12T09:30:00Z","html_url":"u2",
       "assets":[
         {"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":5},
         {"name":"dink-1.5.0.apk","browser_download_url":"https://x/dink-1.5.0.apk","size":12345,"digest":"sha256:ABCDEF"}]},
      {"tag_name":"v1.7.0-rc1","name":"RC","body":"","draft":false,"prerelease":true,"assets":[]}
    ]
    """.trimIndent()

    @Test fun skipsDraftsAndPrereleases() {
        assertEquals(listOf("v1.5.0", "v1.4.0"), ReleaseParser.parse(json).map { it.tag })
    }

    @Test fun picksApkAndDigest() {
        val r = ReleaseParser.parse(json).first()
        val apk = r.apk!!
        assertEquals("dink-1.5.0.apk", apk.name)
        assertEquals(12345L, apk.size)
        assertEquals("abcdef", apk.sha256)
        assertEquals("2026-10-12", r.dateLabel)
        assertEquals("1.5.0", r.version)
    }

    @Test fun missingDigestIsNull() {
        assertNull(ReleaseParser.parse(json).last().apk!!.sha256)
    }

    @Test fun releaseWithoutApkHasNullAsset() {
        val r = ReleaseParser.parse("""[{"tag_name":"v2.0","assets":[]}]""").single()
        assertNull(r.apk)
        assertEquals("v2.0", r.name)
    }
}

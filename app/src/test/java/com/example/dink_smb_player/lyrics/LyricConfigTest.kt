package com.example.dink_smb_player.lyrics

import com.example.dink_smb_player.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricConfigTest {

    private class Fake(override val id: String, override val defaultEnabled: Boolean) : OnlineLyricProvider {
        override val label = id
        override suspend fun fetch(song: Song) = OnlineLyrics()
    }

    private val a = Fake("a", defaultEnabled = true)
    private val b = Fake("b", defaultEnabled = false)
    private val c = Fake("c", defaultEnabled = true)
    private val all = listOf(a, b, c)

    @After
    fun reset() = LyricSettings.hydrate(LyricConfig())

    @Test
    fun `master off by default runs no providers`() {
        assertFalse(LyricConfig().online)
        assertEquals(emptyList<OnlineLyricProvider>(), LyricConfig().activeProviders(all))
    }

    @Test
    fun `master off ignores providers the user switched on`() {
        val cfg = LyricConfig(online = false, providers = mapOf("a" to true, "b" to true, "c" to true))
        assertTrue(cfg.activeProviders(all).isEmpty())
    }

    @Test
    fun `master on uses provider defaults in chain order`() {
        assertEquals(listOf(a, c), LyricConfig(online = true).activeProviders(all))
    }

    @Test
    fun `master on honours explicit per-provider flags`() {
        val cfg = LyricConfig(online = true, providers = mapOf("a" to false, "b" to true))
        assertEquals(listOf(b, c), cfg.activeProviders(all))
    }

    @Test
    fun `stale removed-provider keys are harmless`() {
        val cfg = LyricConfig(online = true, providers = mapOf("genius" to true, "musixmatch" to true))
        assertEquals(listOf(a, c), cfg.activeProviders(all))
    }

    @Test
    fun `in-memory settings gate the real chain`() {
        assertTrue(LyricSettings.activeProviders().isEmpty())
        LyricSettings.setOnline(true)
        assertTrue(LyricSettings.activeProviders().isNotEmpty())
        LyricSettings.set("lrclib", false)
        assertFalse(LyricSettings.activeProviders().any { it.id == "lrclib" })
        LyricSettings.setOnline(false)
        assertTrue(LyricSettings.activeProviders().isEmpty())
    }

    @Test
    fun `Genius and Musixmatch are no longer in the chain`() {
        val ids = OnlineLyricProviders.all.map { it.id }
        assertFalse("genius" in ids)
        assertFalse("musixmatch" in ids)
        assertEquals(
            listOf("lrclib", "netease", "qq", "lyricsify", "letras", "darklyrics", "metalarchives",
                "azlyrics", "songlyrics", "bandcamp", "lyricfind"),
            ids,
        )
    }

    @Test
    fun `fingerprint flow tracks toggles so the now-playing resolver re-keys`() {
        val flow = LyricSettings.fingerprintFlow
        assertEquals("off", flow.value)
        LyricSettings.set("lrclib", false) // master off: what the chain consults is unchanged
        assertEquals("off", flow.value)
        LyricSettings.setOnline(true)
        val on = flow.value
        assertTrue(on.startsWith("on:"))
        assertFalse("lrclib" in on.removePrefix("on:").split(","))
        LyricSettings.set("lrclib", true)
        assertTrue("lrclib" in flow.value.removePrefix("on:").split(","))
        assertEquals(LyricSettings.fingerprint(), flow.value)
        LyricSettings.setOnline(false)
        assertEquals("off", flow.value)
    }

    @Test
    fun `boot hydrate updates the fingerprint flow too`() {
        LyricSettings.hydrate(LyricConfig(online = true))
        assertEquals(LyricSettings.fingerprint(), LyricSettings.fingerprintFlow.value)
        assertTrue(LyricSettings.fingerprintFlow.value.startsWith("on:"))
        LyricSettings.hydrate(LyricConfig())
        assertEquals("off", LyricSettings.fingerprintFlow.value)
    }
}

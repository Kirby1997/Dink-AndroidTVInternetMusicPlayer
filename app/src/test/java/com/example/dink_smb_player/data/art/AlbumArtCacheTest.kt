package com.example.dink_smb_player.data.art

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AlbumArtCacheTest {

    @Test
    fun `art key uses normalized grouping keys, falls back to raw names then id`() {
        assertEquals("beatles|abbey road", AlbumArtCache.artKey("id1", "beatles", "abbey road", "The Beatles", "Abbey Road"))
        // Same album, differently spelled raw artist → same key when grouping keys agree.
        assertEquals(
            AlbumArtCache.artKey("id1", "beatles", "abbey road", "The Beatles", "Abbey Road"),
            AlbumArtCache.artKey("id2", "beatles", "abbey road", "Beatles feat. X", "Abbey Road (Remaster)"),
        )
        assertEquals("the beatles|abbey road", AlbumArtCache.artKey("id1", null, null, " The Beatles ", "Abbey Road"))
        assertEquals("id1", AlbumArtCache.artKey("id1", "beatles", null, "The Beatles", "  "))
        // Two "Greatest Hits" by different artists don't collide.
        assertNotEquals(
            AlbumArtCache.artKey("a", "queen", "greatest hits", null, null),
            AlbumArtCache.artKey("b", "abba", "greatest hits", null, null),
        )
    }

    @Test
    fun `current album keys are the art key, and match the pre-LIB-6 shape`() {
        assertEquals("queen|greatesthits", AlbumArtCache.artKey("a", "queen", "queen|greatesthits", "Queen", "Greatest Hits"))
        // A Song still holding a title-only key maps to the same art key (covers on disk survive).
        assertEquals(
            AlbumArtCache.artKey("a", "queen", "queen|greatesthits", null, null),
            AlbumArtCache.artKey("a", "queen", "greatesthits", null, null),
        )
        // Every track of a compilation shares one cover, whoever its artist.
        assertEquals(
            AlbumArtCache.artKey("1", "artista", "variousartists|now50", null, null),
            AlbumArtCache.artKey("2", "artistb", "variousartists|now50", null, null),
        )
    }

    @Test
    fun `decode scales the longest side to exactly MAX_DIM`() {
        assertEquals(4, AlbumArtCache.sampleSizeFor(3000, 3000, 512)) // → 750, then exact scale
        assertEquals(1, AlbumArtCache.sampleSizeFor(600, 600, 512))
        assertEquals(1, AlbumArtCache.sampleSizeFor(300, 200, 512))
        assertEquals(512 to 512, AlbumArtCache.targetSize(750, 750, 512))
        assertEquals(512 to 256, AlbumArtCache.targetSize(1000, 500, 512))
        assertEquals(300 to 200, AlbumArtCache.targetSize(300, 200, 512)) // never upscaled
        assertEquals(512 to 512, AlbumArtCache.targetSize(512, 512, 512))
    }

    @Test
    fun `keyed mutex serialises per key and drops idle entries`() = runBlocking {
        val locks = KeyedMutex()
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        (1..20).map {
            async {
                locks.withLock("k") {
                    maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                    delay(1)
                    inside.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(1, maxInside.get())
        (1..50).map { i -> async { locks.withLock("album$i") { delay(1) } } }.awaitAll()
        assertEquals(0, locks.size())
    }

    @Test
    fun `cache file names are byte-identical to the old String_format hex`() {
        fun legacy(key: String) = java.security.MessageDigest.getInstance("SHA-1")
            .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", AlbumArtCache.hash("abc"))
        for (key in listOf("", "queen|greatesthits", "variousartists|now 42", "björk|homogenic", "id-\u00ff\u0000", "日本|アルバム")) {
            assertEquals(key, legacy(key), AlbumArtCache.hash(key))
        }
    }
}

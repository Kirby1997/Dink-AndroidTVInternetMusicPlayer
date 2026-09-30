package com.example.dink_smb_player.data.library

import com.example.dink_smb_player.data.index.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Track ids are persisted primary keys (index, playlists, play history, the playback
 * snapshot), so the faster [trackIdFor] must stay byte-identical to the original
 * String.format implementation for every input.
 */
class TrackIdTest {

    /** The pre-SRC-17 implementation, verbatim. */
    private fun legacyTrackIdFor(type: SourceType, sourceId: String, path: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$type|$sourceId|$path".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `known vector`() {
        assertEquals(
            "70f5f0b6fd5deab377c17beaf174c46fd0565cdc",
            trackIdFor(SourceType.Smb, "share-1", "/mnt/music/Ärtist/01 – Song.flac"),
        )
    }

    @Test
    fun `matches the legacy implementation across many inputs`() {
        val rnd = Random(1234)
        val alphabet = "abcXYZ019 /\\._-–ÄöéкиноЖ漢字🎵%&"
        val fixed = listOf("", "a", "/", "smb://nas/music/x.mp3", "Кино/Группа крови/01.mp3", "🎵🎶")
        val randoms = List(5_000) { String(CharArray(rnd.nextInt(0, 80)) { alphabet[rnd.nextInt(alphabet.length)] }) }
        for (type in SourceType.entries) {
            for (path in fixed + randoms) {
                assertEquals(legacyTrackIdFor(type, "src", path), trackIdFor(type, "src", path))
            }
        }
    }

    @Test
    fun `is stable when called concurrently`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 8).map { t ->
                Callable {
                    (0 until 2_000).all { i ->
                        val p = "/music/$t/$i.flac"
                        trackIdFor(SourceType.Smb, "s", p) == legacyTrackIdFor(SourceType.Smb, "s", p)
                    }
                }
            }
            pool.invokeAll(tasks).forEach { assertEquals(true, it.get()) }
        } finally {
            pool.shutdown()
        }
    }
}

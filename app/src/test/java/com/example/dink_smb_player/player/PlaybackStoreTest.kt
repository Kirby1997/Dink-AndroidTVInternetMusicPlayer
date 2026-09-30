package com.example.dink_smb_player.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PLAY-12 / PLAY-7 / PLAY-1: the now-playing session file, its separate position record,
 * the shuffle base order, and the cheap "is there a session" check the media-button
 * receiver uses.
 */
class PlaybackStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dir: File get() = tmp.root

    private fun snap(pos: Float = 12f, index: Int = 1) = PlaybackStore.Snapshot(
        queueIds = listOf("a", "b", "c"),
        index = index,
        positionSec = pos,
        shuffle = true,
        repeat = RepeatMode.All.name,
        baseOrder = listOf(2, 0, 1),
    )

    @Test
    fun `session round trip keeps queue, modes and base order`() = runBlocking {
        PlaybackStore.saveIn(dir, snap())
        assertEquals(snap(), PlaybackStore.loadFrom(dir))
    }

    @Test
    fun `position record overrides the session position for the same track`() = runBlocking {
        PlaybackStore.saveIn(dir, snap(pos = 12f))
        PlaybackStore.savePositionIn(dir, PlaybackStore.Position("b", 97.5f))
        assertEquals(97.5f, PlaybackStore.loadFrom(dir)!!.positionSec)
    }

    @Test
    fun `position record for another track is ignored`() = runBlocking {
        PlaybackStore.saveIn(dir, snap(pos = 12f))
        PlaybackStore.savePositionIn(dir, PlaybackStore.Position("c", 97.5f))
        assertEquals(12f, PlaybackStore.loadFrom(dir)!!.positionSec)
    }

    @Test
    fun `a session save supersedes an older position checkpoint`() = runBlocking {
        PlaybackStore.savePositionIn(dir, PlaybackStore.Position("b", 97.5f))
        PlaybackStore.saveIn(dir, snap(pos = 5f))
        assertEquals(5f, PlaybackStore.loadFrom(dir)!!.positionSec)
    }

    @Test
    fun `corrupt position record falls back to the session position`() = runBlocking {
        PlaybackStore.saveIn(dir, snap(pos = 12f))
        File(dir, "nowplaying_pos.json").writeText("{\"songId\": \"b\", \"positionSec\": ")
        assertEquals(12f, PlaybackStore.loadFrom(dir)!!.positionSec)
    }

    @Test
    fun `corrupt session loads as nothing`() = runBlocking {
        File(dir, "nowplaying.json").writeText("not json {")
        assertNull(PlaybackStore.loadFrom(dir))
    }

    @Test
    fun `old session file without base order still loads`() = runBlocking {
        File(dir, "nowplaying.json").writeText(
            """{"version":1,"queueIds":["a","b"],"index":0,"positionSec":3.0,"shuffle":false,"repeat":"Off"}""",
        )
        val loaded = PlaybackStore.loadFrom(dir)!!
        assertEquals(listOf("a", "b"), loaded.queueIds)
        assertNull(loaded.baseOrder)
    }

    @Test
    fun `clear removes session and position`() = runBlocking {
        PlaybackStore.saveIn(dir, snap())
        PlaybackStore.clearIn(dir)
        assertNull(PlaybackStore.loadFrom(dir))
        assertFalse(File(dir, "nowplaying_pos.json").exists())
    }

    @Test
    fun `has session only for a non-empty session file`() = runBlocking {
        assertFalse(PlaybackStore.hasSessionIn(dir))
        File(dir, "nowplaying.json").writeText("")
        assertFalse(PlaybackStore.hasSessionIn(dir))
        PlaybackStore.saveIn(dir, snap())
        assertTrue(PlaybackStore.hasSessionIn(dir))
    }
}

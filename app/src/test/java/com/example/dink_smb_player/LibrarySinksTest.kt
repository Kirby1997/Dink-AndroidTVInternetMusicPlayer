package com.example.dink_smb_player

import android.content.Context
import androidx.media3.common.Player
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.player.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** The play-credit → markPlayed sink is bound at process level (DinkApplication), so a
 *  service-driven player with no UI still counts each listen exactly once. */
class LibrarySinksTest {

    private fun song(id: String) = Song(
        id = id, title = "T$id", artist = "A", albumId = null, albumTitle = null,
        durationSec = 180, playCount = 0, sourcePath = "/$id.mp3", bitrate = "320",
        mediaUri = "smb://nas/$id.mp3",
    )

    @Test
    fun `play credit reaches the sink exactly once with no UI`() {
        val ctx: Context = mock()
        whenever(ctx.applicationContext).thenReturn(ctx)
        val played = mutableListOf<Pair<Context, String>>()
        val state = PlayerState()
        state.bindLibrarySinks(ctx, playSink = { c, id -> played += c to id }, tagSink = { _, _ -> })

        val engine: Player = mock()
        whenever(engine.mediaItemCount).thenReturn(1)
        state.attachEngine(engine)
        state.playFrom(listOf(song("a")), 0)
        whenever(engine.isPlaying).thenReturn(true)

        repeat(400) { state.pollTick() }   // 100 s of listening

        assertEquals(listOf("a"), played.map { it.second })
        assertSame(ctx, played.single().first)
    }
}

package com.example.dink_smb_player.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.example.dink_smb_player.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The session publishes the engine's metadata, which is empty for a remote file whose
 * tags the extractor can't read. It must then fall back to the façade's current track
 * (matching media id only) so the system Now Playing card isn't blank.
 */
class SessionMetadataFallbackTest {

    private fun song(id: String) = Song(
        id = id,
        title = "Index title $id",
        artist = "Index artist",
        albumId = null,
        albumTitle = "Index album",
        durationSec = 180,
        playCount = 0,
        sourcePath = "/music/$id.mp3",
        bitrate = "320",
        mediaUri = "smb://nas/music/$id.mp3",
    )

    private fun transportFor(current: Song?) = object : PlayerService.Transport {
        override val active = true
        override val current = current
        override fun next() = Unit
        override fun prev() = Unit
    }

    private fun engine(mediaId: String, metadata: MediaMetadata): Player = mock<Player>().also {
        whenever(it.mediaMetadata).thenReturn(metadata)
        whenever(it.currentMediaItem).thenReturn(MediaItem.Builder().setMediaId(mediaId).build())
    }

    @After fun tearDown() { PlayerService.transport = null }

    @Test fun `untagged engine metadata falls back to the index names`() {
        PlayerService.transport = transportFor(song("a"))
        val m = PlayerService.SessionPlayer(engine("a", MediaMetadata.EMPTY)).mediaMetadata
        assertEquals("Index title a", m.title.toString())
        assertEquals("Index artist", m.artist.toString())
        assertEquals("Index album", m.albumTitle.toString())
    }

    @Test fun `engine tags win when present`() {
        PlayerService.transport = transportFor(song("a"))
        val tagged = MediaMetadata.Builder().setTitle("Embedded").setArtist("Tag artist").build()
        val m = PlayerService.SessionPlayer(engine("a", tagged)).mediaMetadata
        assertEquals("Embedded", m.title.toString())
        assertEquals("Tag artist", m.artist.toString())
    }

    @Test fun `no fallback when the facade's track is a different item`() {
        PlayerService.transport = transportFor(song("b"))
        val m = PlayerService.SessionPlayer(engine("a", MediaMetadata.EMPTY)).mediaMetadata
        assertNull(m.title)
    }

    @Test fun `no fallback without a transport`() {
        val m = PlayerService.SessionPlayer(engine("a", MediaMetadata.EMPTY)).mediaMetadata
        assertNull(m.title)
    }
}

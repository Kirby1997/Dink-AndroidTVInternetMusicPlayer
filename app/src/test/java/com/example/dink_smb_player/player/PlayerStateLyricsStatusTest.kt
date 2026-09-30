package com.example.dink_smb_player.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.lyrics.LyricsStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The lyrics pane must say "loading" until the resolver answers, then Loaded / None. */
class PlayerStateLyricsStatusTest {

    private fun song(id: String) = Song(
        id = id,
        title = "Title $id",
        artist = "Artist",
        albumId = null,
        albumTitle = null,
        durationSec = 180,
        playCount = 0,
        sourcePath = "/music/$id.mp3",
        bitrate = "320",
    )

    private val line = listOf(LyricLine(1f, "hi"))

    @Test
    fun `new track is Loading until the resolver answers`() {
        val s = PlayerState()
        s.load(song("a"), null, emptyList(), autoplay = false)
        assertEquals(LyricsStatus.Loading, s.lyricsStatus)
        s.setLyricsFor("a", emptyList())
        assertEquals(LyricsStatus.None, s.lyricsStatus)
    }

    @Test
    fun `resolved lyrics are Loaded and a track change goes back to Loading`() {
        val s = PlayerState()
        s.playFrom(listOf(song("a"), song("b")), 0)
        s.setLyricsFor("a", line)
        assertEquals(LyricsStatus.Loaded, s.lyricsStatus)
        s.jumpTo(1)
        assertEquals(LyricsStatus.Loading, s.lyricsStatus)
    }

    @Test
    fun `stale resolver result for a previous track is ignored`() {
        val s = PlayerState()
        s.playFrom(listOf(song("a"), song("b")), 0)
        s.jumpTo(1)
        s.setLyricsFor("a", line)
        assertEquals(LyricsStatus.Loading, s.lyricsStatus)
    }

    @Test
    fun `reloading the same track keeps its resolved lyrics`() {
        val s = PlayerState()
        s.load(song("a"), null, emptyList(), autoplay = false)
        s.setLyricsFor("a", line)
        s.load(song("a"), null, emptyList())
        assertEquals(LyricsStatus.Loaded, s.lyricsStatus)
        assertEquals(line, s.lyrics)
    }

    // --- Same-song replay: the resolver is keyed on the song id and won't re-run, so
    // every path that re-targets the current track must keep its lyrics. -------------

    private fun resolved(vararg ids: String): PlayerState {
        val s = PlayerState()
        s.playFrom(ids.map { song(it) }, 0)
        s.setLyricsFor(ids.first(), line)
        return s
    }

    @Test
    fun `playFrom replaying the current song keeps its lyrics`() {
        val s = resolved("a", "b")
        s.playFrom(listOf(song("a"), song("b")), 0) // e.g. picking it again in Songs
        assertEquals(LyricsStatus.Loaded, s.lyricsStatus)
        assertEquals(line, s.lyrics)
        s.playFrom(listOf(song("x"), song("a")), 1) // same song from another list (Album/Search)
        assertEquals(LyricsStatus.Loaded, s.lyricsStatus)
    }

    @Test
    fun `playFrom a different song still clears to Loading`() {
        val s = resolved("a", "b")
        s.playFrom(listOf(song("a"), song("b")), 1)
        assertEquals(LyricsStatus.Loading, s.lyricsStatus)
    }

    @Test
    fun `jumping to the current queue row keeps its lyrics`() {
        val s = resolved("a", "b")
        s.jumpTo(0) // Now Playing queue row 0 → moveTo(current)
        assertEquals(LyricsStatus.Loaded, s.lyricsStatus)
        assertEquals(line, s.lyrics)
    }

    @Test
    fun `replay while the first resolve is in flight stays Loading`() {
        val s = PlayerState()
        s.playFrom(listOf(song("a")), 0)
        s.playFrom(listOf(song("a")), 0)
        assertEquals(LyricsStatus.Loading, s.lyricsStatus)
        s.setLyricsFor("a", line)
        assertEquals(LyricsStatus.Loaded, s.lyricsStatus)
    }

    @Test
    fun `resolved-empty same-song replay stays None rather than stuck Loading`() {
        val s = PlayerState()
        s.playFrom(listOf(song("a")), 0)
        s.setLyricsFor("a", emptyList())
        s.jumpTo(0)
        assertEquals(LyricsStatus.None, s.lyricsStatus)
    }

    private fun snapshotOf(vararg ids: String) = PlaybackStore.Snapshot(
        queueIds = ids.toList(),
        index = 0,
        positionSec = 0f,
        shuffle = false,
        repeat = RepeatMode.Off.name,
    )

    @Test
    fun `restoring a track after clearQueue re-resolves instead of reporting None`() {
        // restore() needs an empty queue; clearing it must not leave the old track's
        // resolved marker behind, or restoring that same track reads as "No lyrics".
        val s = resolved("a")
        s.clearQueue()
        s.restore(snapshotOf("a"), mapOf("a" to song("a")))
        assertEquals(LyricsStatus.Loading, s.lyricsStatus)
    }

    private fun mediaItem(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    /** Restored-paused session on [current], lyrics already resolved (Now Playing was
     *  open), then a media-button resumption starts the engine on [engineCurrent]. */
    private fun adoptAfterResolve(current: String, engineCurrent: String): PlayerState {
        val songs = listOf(song("a"), song("b")).map { it.copy(mediaUri = "smb://nas/music/${it.id}.mp3") }
        val byId = songs.associateBy { it.id }
        val engine: Player = mock()
        whenever(engine.mediaItemCount).thenReturn(0)
        val s = PlayerState()
        s.songResolver = { ids -> byId.filterKeys { it in ids } }
        s.restore(snapshotOf(current, if (current == "a") "b" else "a"), byId)
        s.attachEngine(engine)
        s.setLyricsFor(current, line)
        assertEquals(LyricsStatus.Loaded, s.lyricsStatus)
        val captor = argumentCaptor<Player.Listener>()
        verify(engine).addListener(captor.capture())
        whenever(engine.mediaItemCount).thenReturn(2)
        whenever(engine.getMediaItemAt(0)).thenReturn(mediaItem("a"))
        whenever(engine.getMediaItemAt(1)).thenReturn(mediaItem("b"))
        whenever(engine.currentMediaItem).thenReturn(mediaItem(engineCurrent))
        whenever(engine.isPlaying).thenReturn(true)
        whenever(engine.playWhenReady).thenReturn(true)
        captor.firstValue.onIsPlayingChanged(true)
        assertEquals(engineCurrent, s.currentSong?.id)
        return s
    }

    @Test
    fun `adopting a live engine session on the current song keeps its lyrics`() {
        val s = adoptAfterResolve(current = "a", engineCurrent = "a")
        assertEquals(LyricsStatus.Loaded, s.lyricsStatus)
        assertEquals(line, s.lyrics)
    }

    @Test
    fun `adopting a live engine session on another song clears to Loading`() {
        val s = adoptAfterResolve(current = "a", engineCurrent = "b")
        assertEquals(LyricsStatus.Loading, s.lyricsStatus)
    }
}

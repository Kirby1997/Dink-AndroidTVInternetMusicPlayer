package com.example.dink_smb_player.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.example.dink_smb_player.data.model.Album
import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song
import kotlinx.coroutines.delay

enum class RepeatMode { Off, All, One }

/** Tags the engine extracted from a streamed file (Phase 8.7). Any field may be
 *  null when the container didn't carry it. Handed to a sink that persists them. */
data class EngineTags(
    val songId: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val durationMs: Long? = null,
)

/**
 * Façade that holds player state for the UI and drives a [Player] engine when one
 * is attached. The engine is provided by [PlayerService]; until it binds (or for
 * songs without a [Song.mediaUri]) a synthetic 1Hz tick keeps the UI animating.
 *
 * Gapless: when every song in the queue carries a [Song.mediaUri] the full queue
 * is pushed to the engine via [Player.setMediaItems], so ExoPlayer can prepare the
 * next track and cross over without a re-prepare gap. Mixed queues fall back to
 * one-at-a-time loading.
 *
 * Lyrics: [load], [playFrom], [moveTo] etc. clear [lyrics] synchronously. The
 * Composable owner observes [currentSong] and calls [setLyricsFor] once a
 * background resolver returns — keeps the LRC + ID3 file reads off the UI thread.
 */
class PlayerState(
    private val albumLookup: (String?) -> Album? = { null },
) {
    var currentSong by mutableStateOf<Song?>(null)
        private set
    var currentAlbum by mutableStateOf<Album?>(null)
        private set
    var lyrics by mutableStateOf<List<LyricLine>>(emptyList())
        private set
    var timeSec by mutableStateOf(0f)
    var isPlaying by mutableStateOf(false)

    private val _queue: SnapshotStateList<Song> = mutableStateListOf()
    val queue: List<Song> get() = _queue

    var currentIndex by mutableStateOf(-1)
        private set

    var shuffle by mutableStateOf(false)
        private set
    var repeatMode by mutableStateOf(RepeatMode.Off)
        private set

    var karaokeMode by mutableStateOf(false)
        private set

    /** Sink for tags the engine extracts while streaming (Phase 8.7). The Composable
     *  owner sets this to persist enrichment into the library index off-thread. */
    var onMetadataResolved: ((EngineTags) -> Unit)? = null

    private var engine: Player? = null
    private var engineListener: Player.Listener? = null

    // --- Windowed engine queue -------------------------------------------------
    // ExoPlayer setMediaItems() with a whole-library queue (e.g. a 28k-track SMB
    // share) builds tens of thousands of MediaSources on the UI thread → multi-
    // second stall → "Waited 5002ms for KeyEvent" ANR. So for big queues we only
    // ever hand the engine a sliding window of [ENGINE_WINDOW] items centred on the
    // current track; [engineBase] is the queue index that maps to engine item 0.
    // Cross-window advance is driven by STATE_ENDED → onTrackEnded → moveTo, which
    // rebuilds the window. Within a window, gapless + auto-advance are the engine's.
    private val engineWindow = 400
    private var engineBase = 0

    private val isWindowed: Boolean get() = _queue.size > engineWindow

    /** Queue index range to hand the engine, centred on [center] and clamped so a
     *  full window is pushed near the ends. Whole queue when it fits the window. */
    private fun windowRange(center: Int): IntRange {
        if (!isWindowed) return _queue.indices
        val maxStart = _queue.size - engineWindow
        val start = (center - engineWindow / 2).coerceIn(0, maxStart)
        return start until (start + engineWindow)
    }

    // --- Shuffle ---------------------------------------------------------------
    // Shuffle is baked into queue ORDER, not delegated to the engine. ExoPlayer's
    // shuffleModeEnabled only governs the engine's own playlist, which we override
    // with windowing + manual next/prev, so its order wasn't honoured ("plays in
    // order"). Instead `_queue` IS the play order: shuffle reorders it (current track
    // first, rest shuffled), unshuffle restores [baseOrder]. Engine shuffle stays off.
    private var baseOrder: List<Song> = emptyList()

    private fun buildShuffled(songs: List<Song>, startIndex: Int): List<Song> {
        if (songs.size <= 1) return songs
        val first = songs[startIndex]
        val rest = songs.toMutableList().apply { removeAt(startIndex) }.also { it.shuffle() }
        return ArrayList<Song>(songs.size).apply { add(first); addAll(rest) }
    }

    val durationSec: Int get() = currentSong?.durationSec ?: 0

    val progress: Float
        get() = if (durationSec == 0) 0f else (timeSec / durationSec).coerceIn(0f, 1f)

    val currentLyricIndex: Int
        get() = lyrics.indexOfLast { it.timeSec <= timeSec }.coerceAtLeast(0)

    fun albumFor(song: Song): Album? = albumLookup(song.albumId)

    fun load(song: Song, album: Album?, lyrics: List<LyricLine>, autoplay: Boolean = true) {
        currentSong = song
        currentAlbum = album
        this.lyrics = lyrics
        timeSec = 0f
        isPlaying = autoplay
        if (_queue.isEmpty()) {
            _queue.add(song)
            currentIndex = 0
        } else {
            val existing = _queue.indexOfFirst { it.id == song.id }
            currentIndex = if (existing >= 0) existing else {
                _queue.add(song)
                _queue.lastIndex
            }
        }
        applyQueueToEngine(autoplay)
    }

    fun playFrom(songs: List<Song>, startIndex: Int, autoplay: Boolean = true) {
        if (songs.isEmpty()) return
        baseOrder = songs
        val safeStart = startIndex.coerceIn(0, songs.lastIndex)
        // Shuffle = reorder the queue (chosen track first, rest shuffled) and play it
        // sequentially. Engine never shuffles; works the same windowed or not.
        val order = if (shuffle) buildShuffled(songs, safeStart) else songs
        val startIdx = if (shuffle) 0 else safeStart
        _queue.clear()
        _queue.addAll(order)
        currentIndex = startIdx
        val song = order[startIdx]
        currentSong = song
        currentAlbum = albumLookup(song.albumId)
        lyrics = emptyList()
        timeSec = 0f
        isPlaying = autoplay
        applyQueueToEngine(autoplay)
    }

    fun addToQueue(song: Song) {
        val wasEmpty = _queue.isEmpty()
        _queue.add(song)
        baseOrder = (if (baseOrder.isEmpty()) _queue.dropLast(1) else baseOrder) + song
        if (wasEmpty || currentIndex < 0 || currentSong == null) {
            currentIndex = _queue.lastIndex
            currentSong = song
            currentAlbum = albumLookup(song.albumId)
            lyrics = emptyList()
            timeSec = 0f
            isPlaying = true
            applyQueueToEngine(autoplay = true)
            return
        }
        // Append-only: extend the engine queue if it's still all-URI so gapless
        // survives. Skip when windowed — the engine holds a slice, not the tail, so
        // the appended track loads when the window slides to it.
        val player = engine
        val uri = song.mediaUri
        if (player != null && uri != null && !isWindowed && _queue.all { it.mediaUri != null }) {
            player.addMediaItem(mediaItemFor(song))
        }
    }

    fun jumpTo(index: Int) {
        if (index !in _queue.indices) return
        moveTo(index)
    }

    fun clearQueue() {
        _queue.clear()
        baseOrder = emptyList()
        currentIndex = -1
        currentSong = null
        currentAlbum = null
        lyrics = emptyList()
        timeSec = 0f
        isPlaying = false
        engine?.run {
            stop()
            clearMediaItems()
        }
    }

    fun togglePlayPause() {
        val song = currentSong ?: return
        isPlaying = !isPlaying
        if (song.mediaUri != null) engine?.playWhenReady = isPlaying
    }

    fun seek(sec: Float) {
        timeSec = sec.coerceIn(0f, durationSec.toFloat())
        val song = currentSong
        if (song?.mediaUri != null) engine?.seekTo((timeSec * 1000).toLong())
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        reorderAroundCurrent(shuffled = shuffle)
    }

    /** Re-randomise the upcoming queue and force shuffle on. Each press reshuffles
     *  afresh (the Now Playing shuffle button), keeping the current track playing. */
    fun reshuffle() {
        shuffle = true
        reorderAroundCurrent(shuffled = true)
    }

    /** Rebuild [_queue] from [baseOrder] — shuffled (current track first, rest random)
     *  or restored to original order — keeping the current track playing at its
     *  position. baseOrder is the original unshuffled order; if the queue wasn't built
     *  via playFrom, adopt its current order as the base. */
    private fun reorderAroundCurrent(shuffled: Boolean) {
        val current = currentSong ?: return
        if (_queue.isEmpty()) return
        if (baseOrder.isEmpty()) baseOrder = _queue.toList()
        val curInBase = baseOrder.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        val order = if (shuffled) buildShuffled(baseOrder, curInBase) else baseOrder
        val pos = timeSec
        _queue.clear()
        _queue.addAll(order)
        currentIndex = order.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        applyQueueToEngine(autoplay = isPlaying)
        // applyQueueToEngine restarts at 0; keep the current track where it was.
        if (current.mediaUri != null && pos > 0f) {
            timeSec = pos
            engine?.seekTo((pos * 1000).toLong())
        }
    }

    fun cycleRepeatMode() {
        repeatMode = when (repeatMode) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        }
        engine?.repeatMode = engineRepeatMode()
    }

    fun toggleKaraokeMode() {
        karaokeMode = !karaokeMode
    }

    fun next() {
        if (_queue.isEmpty()) return
        // moveTo seeks within the engine window when the target is in it, else
        // rebuilds the window — so this works identically for small and windowed queues.
        val nextIdx = pickNextIndex(advance = 1) ?: return
        moveTo(nextIdx)
    }

    fun prev() {
        if (_queue.isEmpty()) return
        if (timeSec > 3f) {
            timeSec = 0f
            val song = currentSong
            if (song?.mediaUri != null) engine?.seekTo(0L)
            return
        }
        val prevIdx = pickNextIndex(advance = -1) ?: return
        moveTo(prevIdx)
    }

    internal fun onTrackEnded() {
        when (repeatMode) {
            RepeatMode.One -> {
                timeSec = 0f
                isPlaying = true
                val song = currentSong
                if (song?.mediaUri != null) {
                    engine?.seekTo(0L)
                    engine?.playWhenReady = true
                }
            }
            else -> {
                val nextIdx = pickNextIndex(advance = 1)
                if (nextIdx != null) moveTo(nextIdx) else isPlaying = false
            }
        }
    }

    internal fun setEngineIsPlaying(playing: Boolean) {
        isPlaying = playing
    }

    internal fun setEngineDurationMs(durMs: Long) {
        val song = currentSong ?: return
        if (song.mediaUri == null || durMs <= 0) return
        // Persist real duration into the index (import leaves it 0 for streamed tracks).
        onMetadataResolved?.invoke(EngineTags(songId = song.id, durationMs = durMs))
        val newDur = (durMs / 1000).toInt()
        if (newDur == song.durationSec) return
        val updated = song.copy(durationSec = newDur)
        currentSong = updated
        val idx = currentIndex
        if (idx in _queue.indices) _queue[idx] = updated
    }

    /**
     * The engine parsed the streamed file's embedded tags (ID3/Vorbis/MP4). For
     * remote sources (SMB/cloud) the index only has filename/folder-derived names, so
     * upgrade the in-memory [Song] and hand the raw tags to [onMetadataResolved] to
     * persist. Local tracks already carry clean MediaStore tags — skip them.
     */
    internal fun onEngineMetadata(metadata: MediaMetadata, currentMediaId: String?) {
        val idx = _queue.indexOfFirst { it.id == currentMediaId }
        if (idx < 0) return
        val song = _queue[idx]
        val uri = song.mediaUri ?: return
        if (!isRemoteUri(uri)) return

        val title = metadata.title?.toString()?.trim()?.ifBlank { null }
        val artist = (metadata.artist ?: metadata.albumArtist)?.toString()?.trim()?.ifBlank { null }
        val album = metadata.albumTitle?.toString()?.trim()?.ifBlank { null }
        val year = metadata.recordingYear ?: metadata.releaseYear
        val track = metadata.trackNumber
        if (title == null && artist == null && album == null && year == null && track == null) return

        val enriched = song.copy(
            title = title ?: song.title,
            artist = artist ?: song.artist,
            albumTitle = album ?: song.albumTitle,
        )
        if (enriched != song) {
            _queue[idx] = enriched
            if (idx == currentIndex) {
                currentSong = enriched
                currentAlbum = albumLookup(enriched.albumId)
            }
        }
        onMetadataResolved?.invoke(
            EngineTags(song.id, title, artist, album, year, track, null),
        )
    }

    private fun isRemoteUri(uri: String): Boolean =
        when (uri.substringBefore("://").lowercase()) { "smb", "gdrive" -> true; else -> false }

    /** [engineIdx] is the engine's media-item index; map it back to the queue
     *  index through [engineBase] since the engine only holds a window. */
    internal fun syncFromEngine(engineIdx: Int) {
        val q = engineBase + engineIdx
        if (q !in _queue.indices) return
        if (q == currentIndex) return
        currentIndex = q
        val song = _queue[q]
        currentSong = song
        currentAlbum = albumLookup(song.albumId)
        lyrics = emptyList()
        timeSec = 0f
    }

    /** Owner sets lyrics once a background resolver returns. No-op if [currentSong]
     *  has changed since the resolver started, so stale results don't flash. */
    fun setLyricsFor(songId: String, list: List<LyricLine>) {
        if (currentSong?.id != songId) return
        lyrics = list
    }

    internal fun pollTick() {
        val song = currentSong ?: return
        val player = engine
        if (song.mediaUri != null && player != null) {
            timeSec = (player.currentPosition / 1000f).coerceAtLeast(0f)
            return
        }
        if (isPlaying) {
            val next = timeSec + 0.25f
            if (durationSec > 0 && next >= durationSec) {
                timeSec = durationSec.toFloat()
                onTrackEnded()
            } else {
                timeSec = next
            }
        }
    }

    fun attachEngine(player: Player) {
        if (engine === player) return
        detachEngine()
        engine = player
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                if (currentSong?.mediaUri != null) setEngineIsPlaying(playing)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (currentSong?.mediaUri == null) return
                when (playbackState) {
                    Player.STATE_READY -> setEngineDurationMs(player.duration)
                    Player.STATE_ENDED -> onTrackEnded()
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
                syncFromEngine(player.currentMediaItemIndex)
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                // Fires once the engine has parsed the stream's embedded tags.
                onEngineMetadata(mediaMetadata, player.currentMediaItem?.mediaId)
            }
        }
        engineListener = listener
        player.addListener(listener)
        // Shuffle is baked into _queue order, never the engine's job.
        player.shuffleModeEnabled = false
        player.repeatMode = engineRepeatMode()
        if (currentSong != null) applyQueueToEngine(autoplay = isPlaying)
    }

    fun detachEngine() {
        val player = engine ?: return
        engineListener?.let { player.removeListener(it) }
        engineListener = null
        engine = null
    }

    private fun pickNextIndex(advance: Int): Int? {
        if (_queue.isEmpty()) return null
        // Order is already shuffled in the queue when shuffle is on, so advance is
        // always sequential; RepeatMode.All wraps around the ends.
        val raw = currentIndex + advance
        return when {
            raw in _queue.indices -> raw
            repeatMode == RepeatMode.All -> ((raw % _queue.size) + _queue.size) % _queue.size
            else -> null
        }
    }

    private fun moveTo(idx: Int) {
        currentIndex = idx
        val song = _queue[idx]
        currentSong = song
        currentAlbum = albumLookup(song.albumId)
        lyrics = emptyList()
        timeSec = 0f
        isPlaying = true
        val player = engine
        if (player != null && isQueueAllUri()) {
            val engineIdx = idx - engineBase
            if (engineIdx in 0 until player.mediaItemCount) {
                // Target is inside the current window — seek, no re-prepare.
                player.seekTo(engineIdx, 0L)
                player.playWhenReady = true
            } else {
                // Outside the window — rebuild it centred on the new track.
                applyQueueToEngine(autoplay = true)
            }
        } else {
            applyQueueToEngine(autoplay = true)
        }
    }

    private fun applyQueueToEngine(autoplay: Boolean) {
        val player = engine ?: return
        if (_queue.isEmpty()) {
            player.stop()
            player.clearMediaItems()
            return
        }
        if (isQueueAllUri()) {
            val center = currentIndex.coerceIn(0, _queue.lastIndex)
            val range = windowRange(center)
            engineBase = range.first
            val items = range.map { mediaItemFor(_queue[it]) }
            val startInWindow = (center - engineBase).coerceIn(0, items.lastIndex)
            player.setMediaItems(items, startInWindow, 0L)
            // Repeat-All across the whole (possibly windowed) queue is driven by
            // moveTo/onTrackEnded; shuffle is baked into queue order. Engine does neither.
            player.repeatMode = engineRepeatMode()
            player.shuffleModeEnabled = false
            player.prepare()
            player.playWhenReady = autoplay
            return
        }
        val song = currentSong ?: return
        if (song.mediaUri == null) {
            player.stop()
            player.clearMediaItems()
            return
        }
        player.setMediaItem(mediaItemFor(song))
        player.prepare()
        player.playWhenReady = autoplay
    }

    /** Builds a MediaItem carrying enough metadata that MediaSession can answer
     *  controller queries (Bluetooth, Wear, Auto) without a follow-up RPC. Without
     *  this, BT companions log "Timeout while waiting for metadata to sync". */
    private fun mediaItemFor(song: Song): MediaItem {
        val uri = song.mediaUri ?: error("mediaItemFor called for song without mediaUri")
        val builder = MediaItem.Builder().setMediaId(song.id).setUri(uri)
        // For LOCAL tracks the Song already carries clean MediaStore tags, so set them
        // for immediate controller (BT/Auto) display. For REMOTE (SMB/cloud) tracks the
        // index has only filename/folder-derived names — leave MediaItem metadata empty
        // so the engine surfaces the file's REAL embedded tags via onMediaMetadataChanged
        // (Phase 8.7 enrichment); a MediaItem override would mask them.
        if (!isRemoteUri(uri)) {
            builder.setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .apply { song.albumTitle?.let { setAlbumTitle(it) } }
                    .build(),
            )
        }
        return builder.build()
    }

    private fun isQueueAllUri(): Boolean = _queue.isNotEmpty() && _queue.all { it.mediaUri != null }

    private fun engineRepeatMode(): Int = when {
        // Windowed: the engine only holds a slice, so REPEAT_ALL would loop the
        // slice. Queue-wide repeat is handled in onTrackEnded → moveTo instead.
        isWindowed && repeatMode == RepeatMode.All -> Player.REPEAT_MODE_OFF
        repeatMode == RepeatMode.Off -> Player.REPEAT_MODE_OFF
        repeatMode == RepeatMode.All -> Player.REPEAT_MODE_ALL
        else -> Player.REPEAT_MODE_ONE
    }
}

@Composable
fun rememberPlayerState(
    albumLookup: (String?) -> Album? = { null },
): PlayerState {
    val context = LocalContext.current
    val state = remember { PlayerState(albumLookup) }

    DisposableEffect(context) {
        val bindIntent = Intent(context, PlayerService::class.java).apply {
            action = PlayerService.ACTION_BIND_LOCAL
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? PlayerService.LocalBinder ?: return
                state.attachEngine(binder.getPlayer())
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                state.detachEngine()
            }
        }
        val bound = runCatching {
            context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        onDispose {
            state.detachEngine()
            if (bound) runCatching { context.unbindService(connection) }
        }
    }

    LaunchedEffect(state) {
        while (true) {
            delay(250L)
            state.pollTick()
        }
    }
    return state
}

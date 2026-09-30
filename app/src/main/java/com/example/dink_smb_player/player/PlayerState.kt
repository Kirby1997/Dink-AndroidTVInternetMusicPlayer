package com.example.dink_smb_player.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.model.Album
import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.lyrics.LyricsStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RepeatMode { Off, All, One }

/** Slice of the live session handed to MediaSession playback resumption when the
 *  activity is alive — see [PlayerState.resumptionWindow] and
 *  [PlayerService.Companion.liveSession]. */
data class ResumptionWindow(val songs: List<Song>, val startIndex: Int, val positionMs: Long)

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
 * Lyrics: [load], [playFrom], [moveTo] etc. clear [lyrics] synchronously when the
 * track changes (a same-song replay keeps them). The
 * Composable owner observes [currentSong] and calls [setLyricsFor] once a
 * background resolver returns — keeps the LRC + ID3 file reads off the UI thread.
 *
 * Ownership: one process-wide instance ([shared]). [PlayerService] attaches its engine
 * and runs the poll + persistence loop, so queue advance across engine windows,
 * error-skip, ImportThrottle and session saves keep working after the activity is gone
 * (Back out of the app while playing). The UI ([rememberPlayerState]) only observes and
 * drives it. All access is on the main thread.
 */
class PlayerState(
    var albumLookup: (String?) -> Album? = { null },
) {
    var currentSong by mutableStateOf<Song?>(null)
        private set
    var currentAlbum by mutableStateOf<Album?>(null)
        private set
    var lyrics by mutableStateOf<List<LyricLine>>(emptyList())
        private set
    /** Song id [lyrics] were resolved for; differs from [currentSong] while resolving. */
    private var lyricsSongId by mutableStateOf<String?>(null)
    val lyricsStatus: LyricsStatus
        get() = when {
            lyrics.isNotEmpty() -> LyricsStatus.Loaded
            currentSong != null && lyricsSongId != currentSong?.id -> LyricsStatus.Loading
            else -> LyricsStatus.None
        }
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

    /** True once the app has attempted to restore the last-played session at launch
     *  (see [rememberPlayerState]). Home waits for this before its own position-0 preload
     *  of the resume track, so a real saved session (track + position + queue) always wins
     *  the race over the Home shortcut. */
    var sessionRestoreDone by mutableStateOf(false)
        private set

    internal fun markRestoreDone() { sessionRestoreDone = true }

    /** Transient, user-facing playback error (e.g. a track whose file vanished from the
     *  share). Set when a source fails; the UI observes it and takes it with
     *  [consumePlaybackError]. Error-skip runs in the service with no UI attached, so an
     *  error nobody saw must not toast when the app is next opened (see [PLAYBACK_ERROR_TTL_MS]). */
    var playbackError by mutableStateOf<String?>(null)
        private set
    private var playbackErrorAtMs = 0L

    /** Monotonic clock for [playbackError] ageing; tests swap it. */
    internal var clockMs: () -> Long = { SystemClock.elapsedRealtime() }

    private fun reportPlaybackError(msg: String) {
        playbackErrorAtMs = clockMs()
        playbackError = msg
    }

    /** Clears [playbackError] and returns it, or null when it is older than
     *  [PLAYBACK_ERROR_TTL_MS] — raised while no UI was attached to show it. */
    fun consumePlaybackError(): String? {
        val msg = playbackError ?: return null
        playbackError = null
        return msg.takeIf { clockMs() - playbackErrorAtMs <= PLAYBACK_ERROR_TTL_MS }
    }

    // Consecutive engine source-errors since the last successful READY. Bounds the
    // auto-skip so an unreachable queue (NAS offline, every path stale) stops instead
    // of spinning through every track.
    private var consecutiveErrors = 0
    private val MAX_SKIP_ON_ERROR = 8
    private val POLL_SEC = 0.25f
    private val PLAY_CREDIT_SEC = 30f
    private val REASON_NOT_FOUND = "file not found on share"

    /** Sink for tags the engine extracts while streaming (Phase 8.7). The Composable
     *  owner sets this to persist enrichment into the library index off-thread. */
    var onMetadataResolved: ((EngineTags) -> Unit)? = null

    /** Resolves engine media ids back to library Songs when adopting a live engine
     *  session (media-button playback resumption). Set by [shared]. */
    var songResolver: ((List<String>) -> Map<String, Song>)? = null

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
    // 100, not 400: setMediaItems builds a MediaSource per item ON MAIN — 400 took
    // ~1.1s on this cortex-a9 TV (measured, the post-launch jank spike). 100 still
    // gives ±50 tracks of seamless next/prev before a window rebuild.
    private val engineWindow = 100
    private var engineBase = 0

    // Start position (ms) to seed into the engine on the NEXT applyQueueToEngine, then
    // cleared. Set only when resuming a persisted session so playback picks up where the
    // user left off; normal playFrom/load start at 0.
    private var pendingSeekMs: Long = 0L

    // Session-restore leaves the engine EMPTY until the first play intent: setMediaItems
    // builds a MediaSource per item on the main thread (~0.7s for a 100-item window on
    // this TV), which was the biggest post-launch jank block — and a restored session is
    // paused, so nothing needs the engine until the user actually acts. Remote ▶ with an
    // empty engine takes the MediaSession onPlaybackResumption path instead, which
    // rebuilds the same session from disk.
    private var pendingEngineApply = false

    private fun ensureEngineQueueApplied() {
        if (pendingEngineApply) {
            pendingEngineApply = false
            applyQueueToEngine(autoplay = false)
            return
        }
        // Safety net: we think a queue is applied but the engine holds nothing (a
        // resumption handoff that never reached setMediaItems, a service restart).
        // Re-apply at the position the UI is showing instead of no-opping into a
        // dead play button.
        if (engineLostQueue()) {
            pendingSeekMs = (timeSec * 1000).toLong()
            applyQueueToEngine(autoplay = false)
        }
    }

    private fun engineLostQueue(): Boolean {
        val player = engine ?: return false
        return _queue.isNotEmpty() && currentIndex in _queue.indices &&
            player.mediaItemCount == 0 && currentSong?.mediaUri != null
    }

    /** The engine doesn't hold our queue yet: the next action must do the one full apply. */
    private fun engineApplyOutstanding(): Boolean = pendingEngineApply || engineLostQueue()

    /**
     * Serve a media-button playback resumption from the LIVE session instead of the
     * disk snapshot: instant (no library reload in the service) and never stale.
     * Media3 is about to hand exactly this window to the engine and play it, so this
     * also spends the deferred restore apply and claims the window bookkeeping.
     * Null when there's no fully-streamable session — caller falls back to disk.
     * Main-thread only (MediaSession callbacks arrive on the player's looper).
     */
    fun resumptionWindow(claim: Boolean = true): ResumptionWindow? {
        if (currentIndex !in _queue.indices || !isQueueAllUri()) return null
        val range = windowRange(currentIndex)
        val songs = range.map { _queue[it] }
        val posMs = if (pendingEngineApply) pendingSeekMs else (timeSec * 1000).toLong()
        // claim = false: a controller only asking what WOULD resume (isForPlayback false);
        // Media3 won't load it, so the deferred apply must stay pending.
        if (claim) {
            engineBase = range.first
            pendingEngineApply = false
            pendingSeekMs = 0L
            // Media3 loads the window itself, so the engine never sees applyQueueToEngine's
            // mode setup — a restored Repeat One/All must still reach it.
            engine?.let {
                it.repeatMode = engineRepeatMode()
                it.shuffleModeEnabled = false
            }
        }
        return ResumptionWindow(
            songs = songs,
            startIndex = currentIndex - range.first,
            positionMs = posMs.coerceAtLeast(0L),
        )
    }

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
        // Re-loading the track already showing keeps its resolved lyrics — the owner's
        // resolver is keyed on the song id and won't run again to refill them.
        if (lyrics.isNotEmpty()) {
            this.lyrics = lyrics
            lyricsSongId = song.id
        } else {
            clearLyricsOnSongChange(song)
        }
        currentSong = song
        currentAlbum = album
        timeSec = 0f
        isPlaying = autoplay
        pendingSeekMs = 0L
        if (_queue.isEmpty()) {
            _queue.add(song)
            baseOrder = listOf(song)
            currentIndex = 0
        } else {
            val existing = _queue.indexOfFirst { it.id == song.id }
            currentIndex = if (existing >= 0) existing else {
                _queue.add(song)
                // Keep the unshuffled base in step, or a later shuffle toggle rebuilds the
                // queue without this track and plays a different song than the UI shows.
                if (baseOrder.isNotEmpty()) baseOrder = baseOrder + song
                _queue.lastIndex
            }
        }
        applyQueueToEngine(autoplay)
    }

    fun playFrom(songs: List<Song>, startIndex: Int, autoplay: Boolean = true) {
        if (songs.isEmpty()) return
        consecutiveErrors = 0 // fresh user-initiated playback — retry after a prior stop
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
        clearLyricsOnSongChange(song)
        currentSong = song
        currentAlbum = albumLookup(song.albumId)
        timeSec = 0f
        isPlaying = autoplay
        pendingSeekMs = 0L
        applyQueueToEngine(autoplay)
    }

    fun addToQueue(song: Song) {
        val wasEmpty = _queue.isEmpty()
        _queue.add(song)
        baseOrder = (if (baseOrder.isEmpty()) _queue.dropLast(1) else baseOrder) + song
        if (wasEmpty || currentIndex < 0 || currentSong == null) {
            currentIndex = _queue.lastIndex
            clearLyricsOnSongChange(song)
            currentSong = song
            currentAlbum = albumLookup(song.albumId)
            timeSec = 0f
            isPlaying = true
            pendingSeekMs = 0L
            applyQueueToEngine(autoplay = true)
            return
        }
        // Append-only: extend the engine queue if it's still all-URI so gapless
        // survives. Skip when windowed — the engine holds a slice, not the tail, so
        // the appended track loads when the window slides to it.
        val player = engine
        val uri = song.mediaUri
        // Skip the engine append while a session-restore apply is still deferred — the
        // engine is empty then, and the eventual full apply includes this track anyway.
        if (player != null && uri != null && !pendingEngineApply &&
            !isWindowed && _queue.all { it.mediaUri != null }
        ) {
            player.addMediaItem(mediaItemFor(song))
        }
        // Growing past the engine window flips Repeat-All from engine-driven to
        // façade-driven (see engineRepeatMode); without this the engine keeps looping
        // its old slice and the appended track never plays.
        engine?.repeatMode = engineRepeatMode()
    }

    fun jumpTo(index: Int) {
        if (index !in _queue.indices) return
        moveTo(index)
    }

    fun clearQueue() {
        pendingEngineApply = false
        _queue.clear()
        baseOrder = emptyList()
        currentIndex = -1
        clearLyricsOnSongChange(null)
        currentSong = null
        currentAlbum = null
        timeSec = 0f
        isPlaying = false
        engine?.run {
            stop()
            clearMediaItems()
        }
    }

    fun togglePlayPause() {
        val song = currentSong ?: return
        ensureEngineQueueApplied()
        isPlaying = !isPlaying
        if (song.mediaUri != null) {
            val player = engine
            if (player != null) {
                // After a source error (or stop) the engine sits in IDLE and ignores
                // playWhenReady until prepared again — without this, play is a no-op.
                if (isPlaying && player.playbackState == Player.STATE_IDLE) player.prepare()
                // Queue ran out (repeat off): the engine sits in ENDED with playWhenReady
                // still true, so setting it again is a no-op. Replay the current track.
                if (isPlaying && player.playbackState == Player.STATE_ENDED) {
                    timeSec = 0f
                    player.seekTo(player.currentMediaItemIndex, 0L)
                }
                player.playWhenReady = isPlaying
            }
        }
    }

    fun seek(sec: Float) {
        // Unknown duration (streamed track not yet probed) must not clamp every seek to 0.
        timeSec = if (durationSec > 0) sec.coerceIn(0f, durationSec.toFloat()) else sec.coerceAtLeast(0f)
        val song = currentSong
        if (song?.mediaUri != null && engineApplyOutstanding()) {
            // Deferred session: prepare straight at the target instead of applying at the
            // restored spot and then seeking (two opens of the file).
            pendingSeekMs = (timeSec * 1000).toLong()
            applyQueueToEngine(autoplay = isPlaying)
            return
        }
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
        // A base that lost track of the current song (or the queue) would rebuild a queue
        // without it and play something other than what's shown — re-adopt the queue.
        if (baseOrder.isEmpty() || baseOrder.none { it.id == current.id }) baseOrder = _queue.toList()
        val curInBase = baseOrder.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        val order = if (shuffled) buildShuffled(baseOrder, curInBase) else baseOrder
        val pos = timeSec
        _queue.clear()
        _queue.addAll(order)
        currentIndex = order.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        // Seed the start position into the rebuild itself so the engine prepares at the
        // current spot, rather than preparing at 0 and then seeking (a second buffer).
        pendingSeekMs = if (current.mediaUri != null && pos > 0f) (pos * 1000).toLong() else 0L
        applyQueueToEngine(autoplay = isPlaying)
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
            // Restart-from-0 must also override a deferred resume position, or the
            // next engine apply seeks straight back to the stale restored spot.
            pendingSeekMs = 0L
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

    /**
     * Engine source error (e.g. STATUS_OBJECT_PATH_NOT_FOUND — the file was moved or
     * deleted on the share, common with Servarr/lidarr auto-renames). A single bad track
     * must not brick the player: skip past it to the next, but bound the skipping so a
     * fully-unreachable queue stops instead of racing through every track.
     */
    internal fun onPlaybackError(error: PlaybackException) {
        val failedTitle = currentSong?.title ?: "track"
        val reason = errorReason(error)
        consecutiveErrors++
        val skipLimit = minOf(_queue.size, MAX_SKIP_ON_ERROR)
        val nextIdx = if (consecutiveErrors <= skipLimit) pickNextIndex(advance = 1) else null
        if (nextIdx != null) {
            reportPlaybackError("Skipped \"$failedTitle\" — $reason")
            moveTo(nextIdx)
        } else {
            // Either the queue end, or we've hit the skip budget (likely share offline).
            isPlaying = false
            engine?.stop()
            val hint = if (reason == REASON_NOT_FOUND) {
                " Re-import the folder to refresh moved files."
            } else ""
            reportPlaybackError("Can't play \"$failedTitle\" — $reason.$hint")
        }
    }

    /**
     * Human-readable cause for a source error. Everything used to report as
     * "file not found", which sent users re-importing healthy folders when the real
     * problem was a dropped connection. Media3's errorCode for a custom DataSource is
     * usually just ERROR_CODE_IO_UNSPECIFIED, so classify off the cause-chain text
     * (same trick as SmbClient.friendlyError / SmbDataSource.isConnectionError).
     */
    private fun errorReason(error: PlaybackException): String {
        val text = buildString {
            var e: Throwable? = error
            while (e != null) { append(e.message ?: e::class.simpleName.orEmpty()); append(' '); e = e.cause }
        }.uppercase()
        return when {
            "OBJECT_NAME_NOT_FOUND" in text || "OBJECT_PATH_NOT_FOUND" in text ||
                "FILE_NOT_FOUND" in text || "NO SUCH FILE" in text -> REASON_NOT_FOUND
            "ACCESS_DENIED" in text || "LOGON_FAILURE" in text -> "access denied by share"
            "TIMEOUT" in text || "TIMED OUT" in text -> "network timeout"
            "SOCKET" in text || "CONNECTION" in text || "RESET" in text ||
                "BROKEN PIPE" in text || "UNREACH" in text || "TRANSPORT" in text ||
                "EOF" in text -> "network error reaching share"
            "MALFORMED" in text || "UNRECOGNIZED" in text || "PARS" in text -> "unplayable file"
            else -> "playback error"
        }
    }

    internal fun setEngineIsPlaying(playing: Boolean) {
        isPlaying = playing
        // Throttle background SMB imports while a track streams, so concurrent tag-read
        // extractors don't starve playback's reads (the "janky during import" report).
        com.example.dink_smb_player.data.source.ImportThrottle.playbackActive = playing
    }

    internal fun setEngineDurationMs(durMs: Long) {
        val song = currentSong ?: return
        if (song.mediaUri == null || durMs <= 0) return
        val newDur = (durMs / 1000).toInt()
        // Unchanged = the index already holds it. READY fires on every seek/rebuffer and
        // each persist rewrites the whole library file, so only write real corrections.
        if (newDur == song.durationSec) return
        // Persist real duration into the index (import leaves it 0 for streamed tracks).
        onMetadataResolved?.invoke(EngineTags(songId = song.id, durationMs = durMs))
        val updated = song.copy(durationSec = newDur)
        currentSong = updated
        val idx = currentIndex
        if (idx in _queue.indices) _queue[idx] = updated
    }

    /**
     * The engine parsed the streamed file's embedded tags (ID3/Vorbis/MP4). For
     * remote sources (SMB) the index only has filename/folder-derived names, so
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
        uri.substringBefore("://").equals("smb", ignoreCase = true)

    /** [engineIdx] is the engine's media-item index; map it back to the queue
     *  index through [engineBase] since the engine only holds a window. */
    internal fun syncFromEngine(engineIdx: Int) {
        val q = engineBase + engineIdx
        if (q !in _queue.indices) return
        if (q == currentIndex) return
        currentIndex = q
        val song = _queue[q]
        clearLyricsOnSongChange(song)
        currentSong = song
        currentAlbum = albumLookup(song.albumId)
        timeSec = 0f
    }

    /** Owner sets lyrics once a background resolver returns. No-op if [currentSong]
     *  has changed since the resolver started, so stale results don't flash. */
    fun setLyricsFor(songId: String, list: List<LyricLine>) {
        if (currentSong?.id != songId) return
        lyrics = list
        lyricsSongId = songId
    }

    /** Call BEFORE assigning [currentSong] = [next]. Clears [lyrics] only when the track
     *  actually changes. Replaying the current song (queue row 0, its Home card, picking
     *  it in Songs) keeps its lyrics: the owner's resolver is keyed on the song id and
     *  won't re-run, so wiping them left "No lyrics" until the next track. */
    private fun clearLyricsOnSongChange(next: Song?) {
        if (next != null && currentSong?.id == next.id) return
        lyrics = emptyList()
        lyricsSongId = null
    }

    internal fun pollTick() {
        val song = currentSong ?: return
        val player = engine
        if (song.mediaUri != null && player != null) {
            // Deferred restore / not-yet-applied queue: the engine is empty and reports 0,
            // which would wipe the restored position (and make prev() skip back a track
            // instead of restarting, and persist 0 as the resume point).
            if (pendingEngineApply || player.mediaItemCount == 0) return
            timeSec = (player.currentPosition / 1000f).coerceAtLeast(0f)
            creditListening()
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

    /**
     * Mirror the engine's play INTENT into [isPlaying]. Media3's isPlaying is false while
     * buffering, so following it flipped the button to ▶ on every skip/seek, made a press
     * during buffering "play" instead of pause, and released [ImportThrottle] exactly
     * when the stream was starved. Intent = wants to play and isn't stopped/finished.
     */
    private fun syncPlayIntent(player: Player) {
        if (currentSong?.mediaUri == null) return
        val state = player.playbackState
        val wantsPlay = player.isPlaying ||
            (player.playWhenReady && (state == Player.STATE_BUFFERING || state == Player.STATE_READY))
        setEngineIsPlaying(wantsPlay)
    }

    // --- Play credit -----------------------------------------------------------
    // A track counts as played (Recently played, play count) only after it has actually
    // been listened to — not on restore, preload or a skip past it.
    /** Invoked once per listen with the song id. Set by the owner to persist it. */
    var onTrackPlayed: ((String) -> Unit)? = null
    private var creditSongId: String? = null
    private var listenedSec = 0f
    private var credited = false

    private fun resetListenCredit() {
        creditSongId = currentSong?.id
        listenedSec = 0f
        credited = false
    }

    /** Called on each 250 ms poll while an engine track is loaded. */
    private fun creditListening() {
        val song = currentSong ?: return
        if (song.id != creditSongId) resetListenCredit()
        if (credited || !isPlaying || engine?.isPlaying != true) return
        listenedSec += POLL_SEC
        // Scrobble-style: 30 s, or half the track if it's shorter than a minute.
        val need = if (song.durationSec in 1 until 60) song.durationSec / 2f else PLAY_CREDIT_SEC
        if (listenedSec >= need) {
            credited = true
            onTrackPlayed?.invoke(song.id)
        }
    }

    fun attachEngine(player: Player) {
        if (engine === player) return
        detachEngine()
        engine = player
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                // Engine came alive while our apply was still deferred (or our queue
                // is empty): a disk-path playback resumption populated it behind the
                // façade. Adopt it — otherwise the next guarded action rebuilds the
                // queue over live playback and seeks back to the stale disk position.
                if (playing && (pendingEngineApply || _queue.isEmpty()) && player.mediaItemCount > 0) {
                    songResolver?.let { resolve ->
                        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                        adoptEngineSession(resolve(ids))
                    }
                }
                syncPlayIntent(player)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                syncPlayIntent(player)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (currentSong?.mediaUri == null) return
                // Before the handlers below: onTrackEnded/moveTo set their own intent and
                // must have the last word.
                syncPlayIntent(player)
                when (playbackState) {
                    Player.STATE_READY -> {
                        setEngineDurationMs(player.duration)
                        consecutiveErrors = 0 // a track loaded fine — reset the skip budget
                    }
                    Player.STATE_ENDED -> onTrackEnded()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError(error)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                when (reason) {
                    // AUTO = gapless advance. SEEK = a skip that bypassed the façade: TV
                    // remote ⏭/⏮, the system Now Playing panel, Assistant — MediaSession
                    // drives the engine directly. Ignoring SEEK left the UI (title, lyrics,
                    // currentIndex, saved session) on the skipped track while the next one
                    // played, and the following next() then restarted the same song. The
                    // façade's own seeks set currentIndex first, so they no-op here.
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                        syncFromEngine(player.currentMediaItemIndex)
                        // Gapless/seek transitions don't pass through STATE_READY, so the
                        // new track's real duration would never replace the index guess.
                        setEngineDurationMs(player.duration)
                    }
                    // Repeat-One loop: a fresh listen of the same track.
                    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> resetListenCredit()
                }
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
        // Re-entering the app while the service kept playing: this façade is fresh
        // (repeat Off), so take the user's repeat mode from the live engine rather than
        // silently switching it off below.
        if (_queue.isEmpty() && player.mediaItemCount > 0 && (player.isPlaying || player.playWhenReady)) {
            repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.One
                Player.REPEAT_MODE_ALL -> RepeatMode.All
                else -> repeatMode
            }
        }
        player.repeatMode = engineRepeatMode()
        // An engine that arrives already playing can only be a media-button playback
        // resumption (PlayerService.onPlaybackResumption) — the UI can't have driven it
        // before binding. Adopt that session; re-applying our own state (a paused disk
        // snapshot restored while the bind was still in flight) would stop live playback.
        val resolver = songResolver
        if (resolver != null && player.mediaItemCount > 0 && (player.isPlaying || player.playWhenReady)) {
            val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            adoptEngineSession(resolver(ids))
        } else if (currentSong != null && !pendingEngineApply) {
            // pendingEngineApply: a restored-paused session stays deferred through the
            // bind too — applying here would put the setMediaItems cost right back on
            // the launch path.
            applyQueueToEngine(autoplay = isPlaying)
        }
        // While we hold the engine, media-button playback resumption serves the live
        // session (fast, position-accurate) instead of reloading the disk snapshot.
        PlayerService.liveSession = { claim -> resumptionWindow(claim) }
        // Session next/prev (remote keys, system panel) route through the façade so
        // windowed queues, repeat and shuffle behave exactly like the on-screen buttons.
        PlayerService.transport = object : PlayerService.Transport {
            override val active: Boolean get() = _queue.isNotEmpty()
            override val current: Song? get() = currentSong
            override fun next() = this@PlayerState.next()
            override fun prev() = this@PlayerState.prev()
        }
    }

    fun detachEngine() {
        val player = engine ?: return
        PlayerService.liveSession = null
        PlayerService.transport = null
        engineListener?.let { player.removeListener(it) }
        engineListener = null
        engine = null
        // The service (and its engine) is going away but this façade lives on with the
        // process. Park the session like a restore: paused, applied lazily at the current
        // spot on the next engine's first play intent (not on its attach).
        if (currentIndex in _queue.indices) {
            pendingSeekMs = (timeSec * 1000).toLong()
            pendingEngineApply = true
        }
        setEngineIsPlaying(false)
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
        // Deferred session (restored, engine still empty): one apply, straight at the
        // target. Applying the restored window first and then seeking/rebuilding cost a
        // second setMediaItems (~0.7 s on Main each) and opened the old track for nothing.
        val applyOnce = engineApplyOutstanding()
        pendingEngineApply = false
        // A different track never inherits a resume position. If the engine wasn't bound
        // yet, the deferred restore offset would otherwise survive to the bind-time apply
        // and start this track at the previous track's saved position.
        pendingSeekMs = 0L
        currentIndex = idx
        val song = _queue[idx]
        clearLyricsOnSongChange(song)
        currentSong = song
        currentAlbum = albumLookup(song.albumId)
        timeSec = 0f
        isPlaying = true
        val player = engine
        if (player != null && isQueueAllUri() && !applyOnce) {
            val engineIdx = idx - engineBase
            if (engineIdx in 0 until player.mediaItemCount) {
                // Target is inside the current window — seek, no re-prepare needed
                // unless the engine is IDLE (post source-error / stop), where seek +
                // playWhenReady alone never restart playback.
                player.seekTo(engineIdx, 0L)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
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
        // Any full apply satisfies a deferred session-restore apply.
        pendingEngineApply = false
        val player = engine ?: return
        if (_queue.isEmpty()) {
            player.stop()
            player.clearMediaItems()
            return
        }
        // Consume any resume position exactly once; normal (re)apply starts at 0.
        val startMs = pendingSeekMs.also { pendingSeekMs = 0L }
        if (isQueueAllUri()) {
            val center = currentIndex.coerceIn(0, _queue.lastIndex)
            val range = windowRange(center)
            engineBase = range.first
            val items = range.map { mediaItemFor(_queue[it]) }
            val startInWindow = (center - engineBase).coerceIn(0, items.lastIndex)
            player.setMediaItems(items, startInWindow, startMs)
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
        player.setMediaItem(mediaItemFor(song), startMs)
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
        // for immediate controller (BT/Auto) display. For REMOTE (SMB) tracks the
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

    // --- Session persistence ---------------------------------------------------

    /** A cheap value that changes whenever the persisted-worthy STRUCTURE of the session
     *  changes (track, order, modes, queue length) — but NOT on every position tick. The
     *  persistence loop keys a debounced save on this; position is captured at save time. */
    fun saveSignature(): String =
        "${currentSong?.id}|$currentIndex|$shuffle|$repeatMode|${_queue.size}"

    /** Snapshot of the current session for [PlaybackStore], or null when nothing is
     *  playing. Windows the queue to [PlaybackStore.MAX_QUEUE] ids centred on the current
     *  track (see PlaybackStore) and records the position, so a relaunch resumes here. */
    fun snapshot(): PlaybackStore.Snapshot? {
        if (_queue.isEmpty() || currentIndex !in _queue.indices) return null
        val max = PlaybackStore.MAX_QUEUE
        val start = if (_queue.size <= max) 0
            else (currentIndex - max / 2).coerceIn(0, _queue.size - max)
        val end = minOf(_queue.size, start + max)
        val ids = _queue.subList(start, end).map { it.id }
        return PlaybackStore.Snapshot(
            queueIds = ids,
            index = currentIndex - start,
            positionSec = timeSec,
            shuffle = shuffle,
            repeat = repeatMode.name,
            baseOrder = if (shuffle) baseOrderIndices(ids) else null,
        )
    }

    /** Position checkpoint for [PlaybackStore.savePosition]; null when nothing is loaded. */
    fun positionRecord(): PlaybackStore.Position? =
        currentSong?.let { PlaybackStore.Position(it.id, timeSec) }

    /** [baseOrder] restricted to the saved window, as indices into [ids] (duplicates
     *  matched in turn). Null when there's no usable base. */
    private fun baseOrderIndices(ids: List<String>): List<Int>? {
        if (baseOrder.isEmpty()) return null
        val slots = HashMap<String, ArrayDeque<Int>>(ids.size * 2)
        ids.forEachIndexed { i, id -> slots.getOrPut(id) { ArrayDeque() }.addLast(i) }
        return baseOrder.mapNotNull { slots[it.id]?.removeFirstOrNull() }.takeIf { it.isNotEmpty() }
    }

    /** Rebuild a persisted session. Resolves ids through [byId] (rows deleted from the
     *  library since last run just drop out); restores PAUSED and seeks to the saved
     *  position when playback starts. No-ops if the current queue is already populated
     *  (a live session must never be clobbered) or nothing resolves. */
    fun restore(snapshot: PlaybackStore.Snapshot, byId: Map<String, Song>) {
        if (_queue.isNotEmpty()) return
        // A media-button playback resumption (PlayerService.onPlaybackResumption) may
        // have started the engine while no activity was alive. Adopt that live session
        // instead of clobbering it with the disk snapshot, which would pause playback
        // and seek to a stale position.
        val eng = engine
        if (eng != null && eng.mediaItemCount > 0 && (eng.isPlaying || eng.playWhenReady)) {
            adoptEngineSession(byId)
            return
        }
        val songs = snapshot.queueIds.mapNotNull { byId[it] }
        if (songs.isEmpty()) return
        // Keep the current track pinned even if earlier ids dropped: find it by its old
        // window index, then locate where it landed after the mapNotNull compaction.
        val wantedId = snapshot.queueIds.getOrNull(snapshot.index)
        val idx = (wantedId?.let { id -> songs.indexOfFirst { it.id == id } } ?: -1)
            .let { if (it >= 0) it else snapshot.index.coerceIn(0, songs.lastIndex) }
        _queue.clear()
        _queue.addAll(songs)
        // A shuffled session saved its unshuffled order; without it the shuffled queue
        // became the "original" and turning shuffle off changed nothing.
        val base = snapshot.baseOrder?.takeIf { snapshot.shuffle }
            ?.mapNotNull { i -> snapshot.queueIds.getOrNull(i)?.let { byId[it] } }
        baseOrder = if (base.isNullOrEmpty()) songs else base
        currentIndex = idx
        val song = songs[idx]
        clearLyricsOnSongChange(song)
        currentSong = song
        currentAlbum = albumLookup(song.albumId)
        shuffle = snapshot.shuffle
        repeatMode = runCatching { RepeatMode.valueOf(snapshot.repeat) }.getOrDefault(RepeatMode.Off)
        val posSec = snapshot.positionSec.coerceAtLeast(0f)
        timeSec = posSec
        isPlaying = false
        pendingSeekMs = (posSec * 1000).toLong()
        // Deferred: the engine stays empty until the first play/seek/skip intent (see
        // pendingEngineApply). The restored session is paused, so nothing is lost, and
        // the ~0.7s main-thread setMediaItems cost moves off the launch path.
        pendingEngineApply = true
    }

    /** Mirror an already-playing engine queue into this façade (see [restore] and
     *  [attachEngine]). */
    private fun adoptEngineSession(byId: Map<String, Song>) {
        val eng = engine ?: return
        val ids = (0 until eng.mediaItemCount).map { eng.getMediaItemAt(it).mediaId }
        val songs = ids.mapNotNull { byId[it] }
        if (songs.isEmpty()) return
        pendingSeekMs = 0L // a restore() that lost the bind race must not seek us back
        pendingEngineApply = false // engine queue is authoritative here
        _queue.clear()
        _queue.addAll(songs)
        baseOrder = songs
        engineBase = 0
        val curId = eng.currentMediaItem?.mediaId
        currentIndex = songs.indexOfFirst { it.id == curId }.coerceAtLeast(0)
        val song = songs[currentIndex]
        clearLyricsOnSongChange(song)
        currentSong = song
        currentAlbum = albumLookup(song.albumId)
        timeSec = (eng.currentPosition / 1000f).coerceAtLeast(0f)
        isPlaying = eng.isPlaying
        if (songs.size != ids.size) {
            // Some engine items no longer resolve in the index; engine and _queue indices
            // would diverge, so re-apply the resolved queue (brief re-prepare, same spot).
            pendingSeekMs = eng.currentPosition
            applyQueueToEngine(autoplay = eng.playWhenReady)
        }
    }

    companion object {
        /** A [playbackError] older than this when the UI consumes it is dropped. */
        internal const val PLAYBACK_ERROR_TTL_MS = 5_000L

        private var sharedInstance: PlayerState? = null

        /** The process-wide façade (see class doc), created on first use with the
         *  library-backed [songResolver]. Main thread only. */
        fun shared(context: Context): PlayerState {
            sharedInstance?.let { return it }
            val appCtx = context.applicationContext
            return PlayerState().also { state ->
                // Resolves engine media ids when adopting a session the engine started on
                // its own (media-button resumption). Only consulted once the engine is
                // already playing, which implies the library index is restored.
                state.songResolver = { ids ->
                    val wanted = ids.toHashSet()
                    LibraryRepository.songsNow(appCtx)
                        .asSequence().filter { it.id in wanted }.associateBy { it.id }
                }
                sharedInstance = state
            }
        }

        /** The façade if this process has created one, without creating it. */
        fun sharedOrNull(): PlayerState? = sharedInstance
    }
}

/**
 * The UI's handle on the process-wide [PlayerState]. Binding keeps [PlayerService] (which
 * attaches the engine and runs the poll/persist loop) alive while the UI is up; the
 * façade itself outlives this composition, so playback bookkeeping continues after the
 * activity finishes.
 */
@Composable
fun rememberPlayerState(
    albumLookup: (String?) -> Album? = { null },
): PlayerState {
    val context = LocalContext.current
    val state = remember { PlayerState.shared(context) }
    state.albumLookup = albumLookup

    DisposableEffect(context) {
        val bindIntent = Intent(context, PlayerService::class.java).apply {
            action = PlayerService.ACTION_BIND_LOCAL
        }
        // The service attaches its own engine to the shared façade; the binding only
        // keeps it (and its loop) alive while the UI shows a paused session.
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = runCatching {
            context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        onDispose {
            if (bound) runCatching { context.unbindService(connection) }
        }
    }

    // Resume the last session (paused). Waits for the library index so queue ids can
    // resolve to real tracks, and only restores when nothing is loaded yet (the user
    // started something, the service resumed via a media key, or this process already
    // holds the session from an earlier activity). Saving is PlayerService's job.
    LaunchedEffect(state) {
        val appCtx = context.applicationContext
        // Off main: if this wins the boot race against DinkApp's startup effect, the
        // restore's 25k-row upsert would otherwise run on this effect's main dispatcher.
        withContext(Dispatchers.Default) { LibraryRepository.ensureRestored(appCtx) }
        if (state.currentSong == null) {
            PlaybackStore.load(appCtx)?.let { snap ->
                // The full-library Song mapping + id index is pure computation — build it
                // off main. Only restore() itself must stay here (it owns Compose state).
                val byId = withContext(Dispatchers.Default) {
                    LibraryRepository.songsNow(appCtx).associateBy { it.id }
                }
                state.restore(snap, byId)
            }
        }
        // Unblock Home's fallback preload — either we restored a session, or there was
        // none and Home may load its resume-track shortcut.
        state.markRestoreDone()
    }
    return state
}

@file:OptIn(UnstableApi::class)

package com.example.dink_smb_player.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.dink_smb_player.MainActivity
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.data.source.smb.DinkDataSourceFactory
import com.example.dink_smb_player.data.source.smb.SmbConnectionRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground media service that hosts the ExoPlayer + MediaSession.
 *
 * Lifetime: bound by [rememberPlayerState] via [LocalBinder]. Stays alive across
 * activity restarts while audio plays; MediaSessionService promotes itself to
 * foreground automatically once playback begins. TV remote / Bluetooth media keys
 * route through the session even when the activity is backgrounded.
 *
 * The engine is attached to the process-wide [PlayerState] here, not by the UI, and the
 * 250 ms poll + session persistence run in this service. So window advance, error-skip,
 * ImportThrottle and saves carry on after the activity finishes (PLAY-6).
 */
class PlayerService : MediaSessionService() {

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // PlayerState is main-thread state; its poll and persistence decisions run here.
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val persistPolicy = SessionPersistPolicy()

    inner class LocalBinder : Binder() {
        fun getPlayer(): ExoPlayer = requireNotNull(exoPlayer) {
            "PlayerService.getPlayer() called before onCreate completed"
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Audio-only renderers: DefaultRenderersFactory enumerates every video,
        // image, and text codec on the device at first prepare, dumping ~40 lines
        // of "Unsupported mime video/*" into logcat and burning ~200 ms. We're a
        // music app — never need a video pipeline.
        val audioOnlyRenderers = RenderersFactory {
                handler, _, audioListener, _, _ ->
            arrayOf<Renderer>(
                MediaCodecAudioRenderer(this, MediaCodecSelector.DEFAULT, handler, audioListener),
            )
        }
        // DataSource pipeline: file:// / content:// / http(s) keep DefaultDataSource;
        // smb:// routes through SmbDataSource (smbj) on the DEDICATED playback
        // connection (playback = true) so imports/walks can't contend with the stream.
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(DinkDataSourceFactory(this, playback = true))
        // Audio is cheap (~10 MB for 5 min of 320 kbps): buffer far ahead so playback
        // rides out background SMB contention (a monitor walk is ~2 min) and NAS
        // hiccups without rebuffering. Byte cap stays at DefaultLoadControl's
        // audio default (~13 MB) — this is a 32-bit device, don't balloon the heap.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                /* maxBufferMs = */ 300_000,
                /* bufferForPlaybackMs = */ DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                /* bufferForPlaybackAfterRebufferMs = */
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            // Keep 30 s behind the playhead so a short seek-back replays from RAM
            // instead of re-opening the SMB file.
            .setBackBuffer(/* backBufferDurationMs = */ 30_000, /* retainBackBufferFromKeyframe = */ false)
            .build()
        val player = ExoPlayer.Builder(this, audioOnlyRenderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            // Request + respect system audio focus: pause when another app (e.g. a video
            // app) starts playing, and re-request focus on our next play(). Without this
            // Media3 never asks for focus, so Dink talks over other apps and the two live
            // "playing" sessions fight over the remote's media keys.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
        exoPlayer = player

        // Phase 11 EQ: pin a known audio session id so the graphic equalizer
        // (android.media.audiofx.Equalizer) can bind to the player's output. Generated
        // up front + applied to the engine, then the persisted curve is attached before
        // the first track plays. Best-effort — a device without the effect just no-ops.
        runCatching {
            val sessionId = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
            player.setAudioSessionId(sessionId)
            // Creating the effect is a round-trip to audioserver plus a prefs read, and
            // onCreate runs on main during launch (bind from the first composition).
            // EqEngine is synchronized; a restored session is paused, so the curve lands
            // well before the first note in practice.
            serviceScope.launch {
                EqEngine.attach(this@PlayerService, sessionId)
                // Service torn down while we were attaching: don't leak the effect.
                if (!isActive) EqEngine.release()
            }
        }

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivityPi = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val session = MediaSession.Builder(this, SessionPlayer(player))
            .setSessionActivity(sessionActivityPi)
            .setCallback(PlayTogglesCallback())
            .build()
        mediaSession = session
        // Register with the service so Media3's notification manager tracks it. Sessions
        // are otherwise only added when a MediaController connects via onGetSession, and
        // the app binds with a plain ServiceConnection — so no media notification was ever
        // posted and the service never went foreground while playing. Without that, Home
        // left a playing process at a cached oom_adj (killable under memory pressure) and
        // removing the task from Recents killed playback outright.
        addSession(session)

        // Cheap: the engine is empty, and a restored session defers its apply until the
        // first play intent (pendingEngineApply), so nothing lands on the launch path.
        val state = PlayerState.shared(this)
        state.attachEngine(player)
        startSessionLoop(state)
    }

    /** UI-independent heartbeat: position poll (drives play credit and the synthetic
     *  tick) and the persistence policy. */
    private fun startSessionLoop(state: PlayerState) {
        mainScope.launch {
            while (isActive) {
                delay(POLL_MS)
                state.pollTick()
                persist(state, persistPolicy.onTick(SystemClock.elapsedRealtime(), state.saveSignature(), state.isPlaying))
            }
        }
    }

    /** Capture on main (Compose state), write on IO in a process-lived scope so a final
     *  save from [onDestroy] still lands. */
    private fun persist(state: PlayerState, action: SessionPersistPolicy.Action) {
        val ctx = applicationContext
        when (action) {
            SessionPersistPolicy.Action.SaveSession -> {
                val snap = state.snapshot()
                persistScope.launch {
                    if (snap != null) PlaybackStore.save(ctx, snap) else PlaybackStore.clear(ctx)
                }
            }
            SessionPersistPolicy.Action.SavePosition -> state.positionRecord()?.let { pos ->
                persistScope.launch { PlaybackStore.savePosition(ctx, pos) }
            }
            SessionPersistPolicy.Action.None -> Unit
        }
    }

    /**
     * What MediaSession controllers (TV remote ⏭/⏮, the system Now Playing panel,
     * Assistant, Bluetooth) drive. Next/previous are handed to the app's [PlayerState]
     * whenever it holds a queue (with or without an activity): the engine only ever has
     * a window of the queue, so a raw seekToNext() bypassed the façade (UI stuck on the
     * skipped track) and was a dead key at the window's last item. With no façade queue
     * (an engine session it couldn't adopt) the engine's own behaviour is the fallback.
     */
    internal class SessionPlayer(player: Player) : ForwardingPlayer(player) {
        private fun routed(): Transport? = transport?.takeIf { it.active && mediaItemCount > 0 }

        override fun seekToNext() { routed()?.next() ?: super.seekToNext() }
        override fun seekToNextMediaItem() { routed()?.next() ?: super.seekToNextMediaItem() }
        override fun seekToPrevious() { routed()?.prev() ?: super.seekToPrevious() }
        override fun seekToPreviousMediaItem() { routed()?.prev() ?: super.seekToPreviousMediaItem() }

        // The engine hides next/prev at its window edges; the façade can still move.
        override fun getAvailableCommands(): Player.Commands {
            val base = super.getAvailableCommands()
            if (routed() == null) return base
            return base.buildUpon().addAll(*ROUTED_COMMANDS).build()
        }

        override fun isCommandAvailable(command: Int): Boolean =
            super.isCommandAvailable(command) || (command in ROUTED_COMMANDS && routed() != null)

        // Remote (SMB) MediaItems carry no metadata on purpose, so the engine can
        // surface the file's embedded tags for enrichment. A file the extractor finds no
        // tags in (ID3v1/APE-only) then published a blank title to the system Now Playing
        // card and notification. Fall back to the index's names — they come from the
        // import-time tag reads — without touching what the engine itself reports.
        override fun getMediaMetadata(): MediaMetadata {
            val engine = super.getMediaMetadata()
            if (engine.title != null) return engine
            val song = transport?.current?.takeIf { it.id == currentMediaItem?.mediaId } ?: return engine
            return engine.buildUpon()
                .setTitle(song.title)
                .setArtist(song.artist)
                .apply { song.albumTitle?.let { setAlbumTitle(it) } }
                .build()
        }
    }

    /** Next/previous as the app's queue understands them. See [SessionPlayer]. */
    interface Transport {
        /** The façade holds a queue; otherwise the engine's own next/prev apply. */
        val active: Boolean
        /** The façade's current track, for session metadata fallback. */
        val current: Song?
        fun next()
        fun prev()
    }

    /**
     * Make the remote's PLAY key behave like PLAY/PAUSE: by default Media3 maps
     * KEYCODE_MEDIA_PLAY to resume-only (the dedicated PAUSE key already toggled), so
     * a remote with a separate ▶ button could start but not stop playback. Intercept
     * PLAY while already playing → pause; everything else falls through to Media3's
     * default media-button handling (PAUSE, PLAY_PAUSE, NEXT/PREV, headset hook, …).
     */
    @UnstableApi
    private inner class PlayTogglesCallback : MediaSession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
            if (keyEvent != null &&
                keyEvent.action == KeyEvent.ACTION_DOWN &&
                keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY &&
                session.player.isPlaying
            ) {
                session.player.pause()
                return true
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        /**
         * BLD-11, logging-only. The service is exported, so any app can bind a controller.
         * Media3 already hands untrusted controllers (no MEDIA_CONTENT_CONTROL, not a
         * notification listener, not us) read-only commands by default, so this only
         * records who connects from outside [ControllerAllowlist] — the TV's media-key
         * path, Now Playing card and Assistant packages vary by build and must be seen
         * on-device before [ControllerAllowlist.ENFORCE] is ever turned on.
         */
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            val decision = ControllerAllowlist.decide(
                packageName = controller.packageName,
                uid = controller.uid,
                isTrusted = controller.isTrusted,
                ownPackage = packageName,
            )
            if (decision == ControllerAllowlist.Decision.UNKNOWN) {
                Log.i(TAG, "controller outside allowlist: ${controller.packageName} uid=${controller.uid}" +
                    " trusted=${controller.isTrusted}" + if (ControllerAllowlist.ENFORCE) " → rejected" else "")
                if (ControllerAllowlist.ENFORCE) {
                    return Futures.immediateFuture(MediaSession.ConnectionResult.reject())
                }
            }
            return super.onConnectAsync(session, controller)
        }

        /**
         * A media key arrived with no live playback (service just started by
         * MediaButtonReceiver, or the engine was stopped): rebuild the last session
         * from [PlaybackStore] so the remote's ▶ resumes where the user left off even
         * after the app was swiped away. Windowed because handing the engine a
         * 1000-item queue builds that many MediaSources on this 32-bit device.
         * [isForPlayback] = false (a controller only asking what would resume) gets the
         * same answer without loading anything into the player.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Warm fast-path: the session is already in memory (typically a
            // restored-paused session whose engine apply is deferred). Serve its live
            // window instantly — no disk reload, no stale position. Callbacks arrive on
            // the player's application thread (main), where PlayerState lives.
            liveSession?.invoke(isForPlayback)?.let { win ->
                return Futures.immediateFuture(win.toMediaItems())
            }
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val result = try {
                    Result.success(loadResumption(isForPlayback))
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                withContext(Dispatchers.Main) {
                    completeResumption(result, isForPlayback, future) { stopWithoutPlayback() }
                }
            }
            return future
        }
    }

    private suspend fun loadResumption(isForPlayback: Boolean): MediaSession.MediaItemsWithStartPosition {
        val ctx = applicationContext
        val snap = PlaybackStore.load(ctx)
            ?: throw IllegalStateException("no saved playback session")
        LibraryRepository.ensureRestored(ctx)
        // Cold process (started by MediaButtonReceiver, no activity ever ran): the SMB
        // registry is empty, so every smb:// open would fail with "Unknown SMB share id".
        // Hydrate shares + creds from disk, same as MonitorWorker does for its
        // cold-process walks. Idempotent when the activity is alive — it maintains the
        // same registry. Rethrows cancellation (onDestroy cancels serviceScope).
        SmbConnectionRegistry.hydrate(ctx)
        val byId = LibraryRepository.songsNow(ctx).associateBy { it.id }
        if (isForPlayback) {
            // Restore into the shared façade and serve its window, so the whole saved
            // queue (not just the engine window), shuffle base order and repeat mode come
            // back, and service-side advance continues past the window with no UI.
            val win = withContext(Dispatchers.Main) {
                val state = PlayerState.shared(this@PlayerService)
                state.restore(snap, byId)
                state.resumptionWindow()
            }
            if (win != null) return win.toMediaItems()
        }
        val resolved = snap.queueIds.mapNotNull { byId[it] }.filter { it.mediaUri != null }
        if (resolved.isEmpty()) {
            throw IllegalStateException("saved session resolves to no playable tracks")
        }
        val wantedId = snap.queueIds.getOrNull(snap.index)
        val idx = resolved.indexOfFirst { it.id == wantedId }
            .let { if (it >= 0) it else snap.index.coerceIn(0, resolved.lastIndex) }
        val start = (idx - RESUME_WINDOW / 2)
            .coerceIn(0, (resolved.size - RESUME_WINDOW).coerceAtLeast(0))
        val window = resolved.subList(start, minOf(resolved.size, start + RESUME_WINDOW))
        return MediaSession.MediaItemsWithStartPosition(
            window.map { resumptionItemFor(it) },
            idx - start,
            (snap.positionSec * 1000).toLong(),
        )
    }

    /**
     * Resumption failed for a real play request. The service may have been started as a
     * FOREGROUND service by the media-button receiver, and Media3 only calls
     * startForeground() once there is something to show, so without this the system
     * kills the app (ForegroundServiceDidNotStartInTimeException). Satisfy the contract
     * with a silent, deferred notification and stop — what Media3's private
     * stopSelfSafely() does.
     */
    private fun stopWithoutPlayback() {
        try {
            val channelId = DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        getString(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
            val notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setOnlyAlertOnce(true)
                .setOngoing(false)
                .build()
            startForeground(FALLBACK_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: we weren't started in the
            // foreground (e.g. the activity is up), so nothing is owed.
            Log.w(TAG, "resumption fallback: startForeground not allowed", e)
        } finally {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    companion object {
        const val ACTION_BIND_LOCAL = "com.example.dink_smb_player.player.BIND_LOCAL"
        private const val TAG = "PlayerService"

        /** Queue slice handed to the engine on media-button playback resumption. */
        private const val RESUME_WINDOW = 100
        private const val POLL_MS = 250L
        private const val FALLBACK_NOTIFICATION_ID = 0x0D1C

        /** Session writes outlive the service (the final save in onDestroy). */
        private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Set by [PlayerState.attachEngine] while it holds the engine; lets
         *  [onPlaybackResumption] serve the live in-memory session instead of
         *  reloading the disk snapshot (the argument is isForPlayback: whether the
         *  window is really being loaded). Cleared by [PlayerState.detachEngine]. */
        @Volatile
        var liveSession: ((Boolean) -> ResumptionWindow?)? = null

        /** Set by [PlayerState.attachEngine] while it holds the engine; see
         *  [SessionPlayer]. Main-thread only, like the session callbacks that use it. */
        @Volatile
        var transport: Transport? = null

        private fun ResumptionWindow.toMediaItems() = MediaSession.MediaItemsWithStartPosition(
            songs.map { resumptionItemFor(it) },
            startIndex,
            positionMs,
        )

        private val ROUTED_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        /** Local tracks carry clean MediaStore tags; remote (smb) ones stay
         *  metadata-less so the engine's embedded-tag parse isn't masked (same rule
         *  as PlayerState.mediaItemFor). */
        private fun resumptionItemFor(song: com.example.dink_smb_player.data.model.Song): MediaItem {
            val builder = MediaItem.Builder().setMediaId(song.id).setUri(song.mediaUri)
            val scheme = song.mediaUri?.substringBefore("://")?.lowercase()
            if (scheme != "smb") {
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
    }

    /**
     * MediaSessionService binds external MediaController clients via [SERVICE_INTERFACE].
     * Our in-process activity binds via [ACTION_BIND_LOCAL] and gets the [LocalBinder]
     * instead so it can drive the ExoPlayer directly without an IPC controller hop.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == ACTION_BIND_LOCAL) binder else super.onBind(intent)
    }

    /**
     * Keep playing when the task is swiped away mid-play; otherwise shut down. Deliberately
     * does NOT call super: Media3's default stops unless [Player.isPlaying], which is false
     * while buffering, so a swipe during a rebuffer would kill playback. And a plain
     * stopSelf() is not enough since Media3 1.6 — the service stays foreground for 10 min
     * after a pause; [pauseAllPlayersAndStopSelf] drops that before stopping.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        mainScope.cancel()
        // Last word on the session before the engine goes: a pending structural change,
        // else the position. The façade keeps its state for the next service instance.
        PlayerState.sharedOrNull()?.let { state ->
            persist(state, persistPolicy.flush(SystemClock.elapsedRealtime(), state.saveSignature()))
            state.detachEngine()
        }
        serviceScope.cancel()
        EqEngine.release()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        super.onDestroy()
    }

}

/**
 * Completes a disk-path playback resumption on the main thread. On failure the future
 * fails FIRST — Media3 then runs its "play anyway" on the empty player synchronously —
 * and only for a real play request ([isForPlayback]) does [onPlayFailed] then satisfy
 * the foreground-start contract and stop the service. A controller merely asking what
 * would resume never started anything, so it gets no fallback.
 */
internal fun <T> completeResumption(
    result: Result<T>,
    isForPlayback: Boolean,
    future: SettableFuture<T>,
    onPlayFailed: () -> Unit,
) {
    result.fold(
        onSuccess = { future.set(it) },
        onFailure = { t ->
            future.setException(t)
            if (isForPlayback) onPlayFailed()
        },
    )
}

/**
 * Who may drive the exported media session (BLD-11). Pure so it's JVM-testable; see
 * [PlayerService]'s onConnectAsync for why it only logs today.
 */
internal object ControllerAllowlist {
    /** Off until logcat on the TV shows every legitimate controller is ALLOWED. */
    const val ENFORCE = false

    enum class Decision { ALLOWED, UNKNOWN }

    /** Below this uid is the platform itself (system_server, Bluetooth, media, …). */
    private const val FIRST_APPLICATION_UID = 10_000

    /** Known system controllers on Android TV / Google TV. Package names, not signatures,
     *  so this is a filter for well-behaved apps, not a security boundary on its own. */
    val KNOWN_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.bluetooth",
        // Platform MediaController without a resolvable package (Media3's placeholder).
        "android.media.session.MediaController",
        // Launchers / Now Playing surfaces.
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher",
        "com.google.android.tvrecommendations",
        // Assistant / voice.
        "com.google.android.katniss",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.googleassistant",
        // Google TV remote app + CEC/remote services.
        "com.google.android.tv.remote.service",
        "com.google.android.tv.remote",
        "com.android.tv.settings",
    )

    fun decide(packageName: String, uid: Int, isTrusted: Boolean, ownPackage: String): Decision = when {
        packageName == ownPackage -> Decision.ALLOWED
        isTrusted -> Decision.ALLOWED
        uid in 0 until FIRST_APPLICATION_UID -> Decision.ALLOWED
        packageName in KNOWN_PACKAGES -> Decision.ALLOWED
        else -> Decision.UNKNOWN
    }
}

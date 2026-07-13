package com.example.dink_smb_player.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.dink_smb_player.MainActivity
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.prefs.EncryptedShareStore
import com.example.dink_smb_player.data.prefs.SharePrefs
import com.example.dink_smb_player.data.source.smb.DinkDataSourceFactory
import com.example.dink_smb_player.data.source.smb.SmbConnectionRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground media service that hosts the ExoPlayer + MediaSession.
 *
 * Lifetime: bound by [rememberPlayerState] via [LocalBinder]. Stays alive across
 * activity restarts while audio plays; MediaSessionService promotes itself to
 * foreground automatically once playback begins. TV remote / Bluetooth media keys
 * route through the session even when the activity is backgrounded.
 */
class PlayerService : MediaSessionService() {

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
            EqEngine.attach(this, sessionId)
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

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPi)
            .setCallback(PlayTogglesCallback())
            .build()
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
         * A media key arrived with no live playback (service just started by
         * MediaButtonReceiver, or the engine was stopped): rebuild the last session
         * from [PlaybackStore] so the remote's ▶ resumes where the user left off even
         * after the app was swiped away. Same id-resolution as [PlayerState.restore];
         * windowed because handing the engine a 1000-item queue builds that many
         * MediaSources on this 32-bit device.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val ctx = applicationContext
                try {
                    val snap = PlaybackStore.load(ctx)
                        ?: throw IllegalStateException("no saved playback session")
                    LibraryRepository.ensureRestored(ctx)
                    // Cold process (started by MediaButtonReceiver, no activity ever ran):
                    // the SMB registry is empty, so every smb:// open would fail with
                    // "Unknown SMB share id". Hydrate shares + creds from disk, same as
                    // MonitorWorker does for its cold-process walks. Idempotent when the
                    // activity is alive — it maintains the same registry.
                    val shares = runCatching { SharePrefs(ctx).shares.first() }.getOrDefault(emptyList())
                    if (shares.isNotEmpty()) SmbConnectionRegistry.update(shares)
                    val secretStore = EncryptedShareStore(ctx)
                    SmbConnectionRegistry.installCredLookup { sid ->
                        runCatching { secretStore.getSmbCreds(sid) }.getOrNull()
                    }
                    val byId = LibraryRepository.songsNow(ctx).associateBy { it.id }
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
                    val items = window.map { song ->
                        val builder = MediaItem.Builder().setMediaId(song.id).setUri(song.mediaUri)
                        // Local tracks carry clean MediaStore tags; remote (smb/gdrive) ones
                        // stay metadata-less so the engine's embedded-tag parse isn't masked
                        // (same rule as PlayerState.mediaItemFor).
                        val scheme = song.mediaUri?.substringBefore("://")?.lowercase()
                        if (scheme != "smb" && scheme != "gdrive") {
                            builder.setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(song.title)
                                    .setArtist(song.artist)
                                    .apply { song.albumTitle?.let { setAlbumTitle(it) } }
                                    .build(),
                            )
                        }
                        builder.build()
                    }
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            items,
                            idx - start,
                            (snap.positionSec * 1000).toLong(),
                        ),
                    )
                } catch (t: Throwable) {
                    future.setException(t)
                }
            }
            return future
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * MediaSessionService binds external MediaController clients via [SERVICE_INTERFACE].
     * Our in-process activity binds via [ACTION_BIND_LOCAL] and gets the [LocalBinder]
     * instead so it can drive the ExoPlayer directly without an IPC controller hop.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == ACTION_BIND_LOCAL) binder else super.onBind(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
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

    companion object {
        const val ACTION_BIND_LOCAL = "com.example.dink_smb_player.player.BIND_LOCAL"

        /** Queue slice handed to the engine on media-button playback resumption. */
        private const val RESUME_WINDOW = 100
    }
}

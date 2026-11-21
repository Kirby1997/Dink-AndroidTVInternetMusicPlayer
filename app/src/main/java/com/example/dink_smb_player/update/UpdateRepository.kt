package com.example.dink_smb_player.update

import android.content.Context
import android.util.Log
import com.example.dink_smb_player.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo) : UpdateState
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * Process-wide updater. Sideload ("github") flavor: check + download + verify + install via
 * PackageInstaller. Play flavor ([BuildConfig.SELF_UPDATE] false): only fetches the release list
 * for the changelog reader, on manual request; never offers an install and never badges.
 */
object UpdateRepository {
    private const val TAG = "UpdateRepository"
    private const val INSTALL_TIMEOUT_MS = 3 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One flag guards a whole check, a whole download(+commit); a second press is a no-op. */
    private val busy = AtomicBoolean(false)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _releases = MutableStateFlow<List<ReleaseInfo>>(emptyList())
    val releases: StateFlow<List<ReleaseInfo>> = _releases.asStateFlow()

    private val _badge = MutableStateFlow(false)
    /** True when the sideload flavor knows of a newer release. */
    val badge: StateFlow<Boolean> = _badge.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** Non-fatal guidance (e.g. where to allow installs on this TV). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var launched = false
    @Volatile private var latest: ReleaseInfo? = null
    @Volatile private var sessionId = -1
    @Volatile private var installToken = 0

    private fun ctx(): Context = checkNotNull(appContext) { "UpdateRepository.attach not called" }
    fun prefs(context: Context) = UpdatePrefs(context.applicationContext)

    /** Cheap: stores the app context. Safe on the main thread. */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** Deferred launch work (cleanup, cached changelog, auto-check, worker scheduling). Off main, once. */
    fun onAppLaunch(context: Context, delayMs: Long = 20_000L) {
        attach(context)
        if (launched) return
        launched = true
        scope.launch {
            val c = ctx()
            _releases.value = GitHubReleaseClient(c, prefs(c)).cached()
            if (BuildConfig.SELF_UPDATE) {
                ApkInstaller.clearUpdates(c)
                evaluate(markUpToDate = false)
                delay(delayMs)
                val p = prefs(c)
                val auto = p.autoCheckNow()
                if (auto) UpdateWorker.schedule(c) else UpdateWorker.cancel(c)
                if (UpdatePolicy.isLaunchCheckDue(auto, p.lastCheck(), System.currentTimeMillis())) {
                    check(manual = false)
                }
            }
        }
    }

    /** Toggle handler: persists, and (de)schedules the daily worker. */
    suspend fun setAutoCheck(context: Context, on: Boolean) {
        attach(context)
        prefs(context).setAutoCheck(on)
        if (on) UpdateWorker.schedule(context.applicationContext) else UpdateWorker.cancel(context.applicationContext)
    }

    internal suspend fun checkInBackground() {
        if (BuildConfig.SELF_UPDATE) check(manual = false)
    }

    fun checkAsync() { scope.launch { check(manual = true) } }

    /**
     * Fetch releases and update state. No-op while another check/download is running. A failed
     * check never clobbers Available / ReadyToInstall; manual checks surface the failure as a notice.
     */
    suspend fun check(manual: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        try {
            val c = ctx()
            val p = prefs(c)
            val prior = _state.value
            if (prior is UpdateState.Downloading || prior is UpdateState.Installing) return
            // Keep Available / ReadyToInstall visible while re-checking.
            val showChecking = prior !is UpdateState.Available && prior !is UpdateState.ReadyToInstall
            if (showChecking) _state.value = UpdateState.Checking
            if (manual) _notice.value = null
            try {
                _releases.value = GitHubReleaseClient(c, p).fetch()
                p.setLastCheck(System.currentTimeMillis())
                evaluate(markUpToDate = true)
            } catch (e: CancellationException) {
                if (showChecking && _state.value == UpdateState.Checking) _state.value = prior
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
                // lastCheck is deliberately not updated on failure (incl. rate limits).
                if (_releases.value.isEmpty()) _releases.value = GitHubReleaseClient(c, p).cached()
                evaluate(markUpToDate = false)
                val msg = when (e) {
                    is RateLimitException -> e.message.orEmpty()
                    is IOException -> "Couldn't reach GitHub. ${e.message.orEmpty()}".trim()
                    else -> "Update check failed."
                }
                if (_state.value == UpdateState.Checking) _state.value = UpdateState.Error(msg)
                if (manual) _notice.value = msg
            }
        } finally {
            busy.set(false)
        }
    }

    /** Recomputes [latest]/[badge]/state from [releases]. Never disturbs a running download/install. */
    private fun evaluate(markUpToDate: Boolean) {
        val newer = if (BuildConfig.SELF_UPDATE) {
            _releases.value.firstOrNull { it.apk != null && Semver.isNewer(it.tag, BuildConfig.VERSION_NAME) }
        } else null
        latest = newer
        _badge.value = newer != null
        val cur = _state.value
        if (cur is UpdateState.Downloading || cur is UpdateState.Installing || cur is UpdateState.ReadyToInstall) return
        if (newer != null) _state.value = UpdateState.Available(newer)
        else if (markUpToDate || cur is UpdateState.Available) _state.value = UpdateState.UpToDate
    }

    /** Download (if needed), verify, then install or ask for the install permission. */
    fun downloadAndInstall() {
        if (!BuildConfig.SELF_UPDATE) return
        val release = latest ?: return
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                _notice.value = null
                val apk = release.apk ?: return@launch
                val c = ctx()
                val file = File(ApkInstaller.updatesDir(c), ApkInstaller.APK_NAME)
                try {
                    // Reuse an earlier download only when a published checksum proves it is this release's APK.
                    val reusable = apk.sha256 != null && file.exists() && file.length() == apk.size &&
                        verify(c, file, apk)
                    if (!reusable) download(c, release, apk, file)
                    _state.value = UpdateState.ReadyToInstall(release)
                    install(release, file)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    file.delete()
                    Log.w(TAG, "Download/install failed: ${e.message}", e)
                    _state.value = UpdateState.Error(e.message ?: "Download failed.")
                }
            } finally {
                busy.set(false)
            }
        }
    }

    /** Re-attempt the install of an already-downloaded APK (after granting the install permission). */
    fun installDownloaded() {
        val s = _state.value as? UpdateState.ReadyToInstall ?: return
        val file = File(ApkInstaller.updatesDir(ctx()), ApkInstaller.APK_NAME)
        if (!file.exists()) { downloadAndInstall(); return }
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                install(s.release, file)
            } catch (e: Exception) {
                Log.w(TAG, "Install failed: ${e.message}", e)
                _state.value = UpdateState.Error(e.message ?: "Install failed.")
            } finally {
                busy.set(false)
            }
        }
    }

    private fun install(release: ReleaseInfo, file: File) {
        val c = ctx()
        if (!ApkInstaller.canInstall(c)) {
            _state.value = UpdateState.ReadyToInstall(release)
            _notice.value = if (ApkInstaller.openUnknownSourcesSettings(c)) {
                "Allow Dink to install apps in the screen that just opened, press Back, then press Install."
            } else {
                "Allow Dink to install unknown apps: open your TV's Settings, then " +
                    "Apps > Special app access > Install unknown apps > Dink, then come back and press Install."
            }
            return
        }
        _notice.value = null // the "allow unknown apps" instructions are done with
        _state.value = UpdateState.Installing(release)
        try {
            sessionId = ApkInstaller.commit(c, file)
        } catch (e: IOException) {
            Log.w(TAG, "Session commit failed: ${e.message}", e)
            _state.value = UpdateState.Error("Install failed: ${e.message}")
            return
        }
        // Never stay in Installing forever (prompt dismissed without a result, process quirks).
        val token = ++installToken
        scope.launch {
            delay(INSTALL_TIMEOUT_MS)
            if (token == installToken) resetStaleInstall("The install didn't finish. Press Install to try again.")
        }
    }

    /** Updates tab became visible: any session still "Installing" is stale, since the system prompt is gone. */
    fun onUpdatesTabShown() {
        if (_state.value is UpdateState.Installing) resetStaleInstall(null)
    }

    private fun resetStaleInstall(notice: String?) {
        val s = _state.value as? UpdateState.Installing ?: return
        installToken++
        val id = sessionId
        sessionId = -1
        if (id >= 0) appContext?.let { ApkInstaller.abandon(it, id) }
        _state.value = UpdateState.ReadyToInstall(s.release)
        if (notice != null) _notice.value = notice
    }

    /** The system confirm prompt couldn't be shown (app in background): keep the APK, let the tab retry. */
    internal fun onInstallNeedsForeground() {
        installToken++
        sessionId = -1
        val rel = (_state.value as? UpdateState.Installing)?.release ?: latest ?: return
        _state.value = UpdateState.ReadyToInstall(rel)
        _notice.value = "Open Dink and press Install to confirm the update."
    }

    internal fun onInstallResult(success: Boolean, message: String?) {
        installToken++
        sessionId = -1
        if (success) {
            // Normally the process is replaced before this runs; clean up if it wasn't.
            appContext?.let { ApkInstaller.clearUpdates(it) }
            _state.value = UpdateState.UpToDate
        } else {
            Log.w(TAG, "Installer reported failure: $message")
            _state.value = UpdateState.Error(message ?: "Install failed.")
        }
    }

    private fun verify(c: Context, file: File, asset: ReleaseAsset): Boolean {
        asset.sha256?.let { if (!ApkInstaller.sha256Hex(file).equals(it, ignoreCase = true)) return false }
        return ApkInstaller.signatureProblem(c, file) == null
    }

    private fun download(c: Context, release: ReleaseInfo, asset: ReleaseAsset, dest: File) {
        if (!DownloadUrlPolicy.isAllowed(asset.url)) {
            throw IOException("Refusing to download the update from an untrusted address.")
        }
        ApkInstaller.clearUpdates(c)
        if (asset.size > 0 && !ApkInstaller.hasSpaceFor(c, asset.size)) {
            throw IOException("Not enough free storage to download the update (needs about ${asset.size / (1024 * 1024) + 32} MB).")
        }
        if (asset.sha256 == null) {
            Log.w(TAG, "Release ${release.tag} has no published digest; relying on the signer check only")
        }
        _state.value = UpdateState.Downloading(release, 0f)
        val part = File(dest.parentFile, ApkInstaller.PART_NAME)
        try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val req = Request.Builder().url(asset.url)
                .header("User-Agent", "Dink-Android/${BuildConfig.VERSION_NAME}").build()
            GitHubReleaseClient.downloadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed (HTTP ${resp.code}).")
                val body = resp.body ?: throw IOException("Empty download.")
                val total = if (asset.size > 0) asset.size else body.contentLength()
                var done = 0L
                var lastPct = -1
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    _state.value = UpdateState.Downloading(release, pct / 100f)
                                }
                            }
                        }
                    }
                }
            }
            if (asset.size > 0 && part.length() != asset.size) throw IOException("Download was incomplete. Try again.")
            asset.sha256?.let { want ->
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (!got.equals(want, ignoreCase = true)) throw IOException("The downloaded file failed its checksum. Try again.")
            }
            if (!part.renameTo(dest)) throw IOException("Couldn't save the download.")
            ApkInstaller.signatureProblem(c, dest)?.let { dest.delete(); throw IOException(it) }
        } finally {
            part.delete()
        }
    }
}

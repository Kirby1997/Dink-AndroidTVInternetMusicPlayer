package com.example.dink_smb_player.update

import android.content.Context
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

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
    private var latest: ReleaseInfo? = null

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
            val client = GitHubReleaseClient(c, prefs(c))
            _releases.value = client.cached()
            if (BuildConfig.SELF_UPDATE) {
                ApkInstaller.clearUpdates(c)
                evaluate(markUpToDate = false)
                delay(delayMs)
                val p = prefs(c)
                val auto = p.autoCheckNow()
                if (auto) UpdateWorker.schedule(c) else UpdateWorker.cancel(c)
                if (UpdatePolicy.isLaunchCheckDue(auto, p.lastCheck(), System.currentTimeMillis())) {
                    check()
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
        if (BuildConfig.SELF_UPDATE) check()
    }

    fun checkAsync() { scope.launch { check() } }

    /** Fetch releases and update state. Skipped while a download/install is running. */
    suspend fun check() {
        val busy = _state.value
        if (busy is UpdateState.Downloading || busy is UpdateState.Installing) return
        if (!mutex.tryLock()) return
        try {
            val c = ctx()
            val p = prefs(c)
            _state.value = UpdateState.Checking
            withContext(Dispatchers.IO) {
                try {
                    _releases.value = GitHubReleaseClient(c, p).fetch()
                    p.setLastCheck(System.currentTimeMillis())
                    evaluate(markUpToDate = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (_releases.value.isEmpty()) _releases.value = GitHubReleaseClient(c, p).cached()
                    evaluate(markUpToDate = false)
                    _state.value = UpdateState.Error(
                        if (e is IOException) "Couldn't reach GitHub. ${e.message.orEmpty()}".trim()
                        else "Update check failed.",
                    )
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    /** Recomputes [latest]/[badge]/state from [releases]. */
    private fun evaluate(markUpToDate: Boolean) {
        val newer = if (BuildConfig.SELF_UPDATE) {
            _releases.value.firstOrNull { it.apk != null && Semver.isNewer(it.tag, BuildConfig.VERSION_NAME) }
        } else null
        latest = newer
        _badge.value = newer != null
        val cur = _state.value
        if (cur is UpdateState.Downloading || cur is UpdateState.Installing || cur is UpdateState.ReadyToInstall) return
        if (newer != null) _state.value = UpdateState.Available(newer)
        else if (markUpToDate) _state.value = UpdateState.UpToDate
    }

    /** Download (if needed), verify, then install or ask for the install permission. */
    fun downloadAndInstall() {
        if (!BuildConfig.SELF_UPDATE) return
        val release = latest ?: return
        val cur = _state.value
        if (cur is UpdateState.Downloading || cur is UpdateState.Installing) return
        scope.launch {
            _notice.value = null
            val apk = release.apk ?: return@launch
            val c = ctx()
            val file = File(ApkInstaller.updatesDir(c), "dink-${release.version}.apk")
            try {
                if (!(file.exists() && file.length() == apk.size && verify(c, file, apk))) {
                    download(c, release, apk, file)
                }
                _state.value = UpdateState.ReadyToInstall(release)
                install(release, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                file.delete()
                _state.value = UpdateState.Error(e.message ?: "Download failed.")
            }
        }
    }

    /** Re-attempt the install of an already-downloaded APK (after granting the install permission). */
    fun installDownloaded() {
        val s = _state.value as? UpdateState.ReadyToInstall ?: return
        scope.launch {
            val file = File(ApkInstaller.updatesDir(ctx()), "dink-${s.release.version}.apk")
            if (!file.exists()) { downloadAndInstall(); return@launch }
            install(s.release, file)
        }
    }

    private fun install(release: ReleaseInfo, file: File) {
        val c = ctx()
        if (!ApkInstaller.canInstall(c)) {
            _state.value = UpdateState.ReadyToInstall(release)
            if (!ApkInstaller.openUnknownSourcesSettings(c)) {
                _notice.value = "Allow Dink to install unknown apps: open your TV's Settings, then " +
                    "Apps > Special app access > Install unknown apps > Dink, then come back and press Install."
            } else {
                _notice.value = "Allow Dink to install apps in the screen that just opened, press Back, then press Install."
            }
            return
        }
        _state.value = UpdateState.Installing(release)
        try {
            ApkInstaller.commit(c, file)
        } catch (e: IOException) {
            _state.value = UpdateState.Error("Install failed: ${e.message}")
        }
    }

    internal fun onInstallResult(success: Boolean, message: String?) {
        if (success) {
            // Normally the process is replaced before this runs; clean up if it wasn't.
            appContext?.let { ApkInstaller.clearUpdates(it) }
            _state.value = UpdateState.UpToDate
        } else {
            _state.value = UpdateState.Error(message ?: "Install failed.")
        }
    }

    private fun verify(c: Context, file: File, asset: ReleaseAsset): Boolean {
        asset.sha256?.let { if (!ApkInstaller.sha256Hex(file).equals(it, ignoreCase = true)) return false }
        return ApkInstaller.signatureProblem(c, file) == null
    }

    private fun download(c: Context, release: ReleaseInfo, asset: ReleaseAsset, dest: File) {
        ApkInstaller.clearUpdates(c)
        if (asset.size > 0 && !ApkInstaller.hasSpaceFor(c, asset.size)) {
            throw IOException("Not enough free storage to download the update (needs about ${asset.size / (1024 * 1024) + 32} MB).")
        }
        _state.value = UpdateState.Downloading(release, 0f)
        val part = File(dest.parentFile, dest.name + ".part")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val req = Request.Builder().url(asset.url)
            .header("User-Agent", "Dink-Android/${BuildConfig.VERSION_NAME}").build()
        GitHubReleaseClient.defaultClient.newCall(req).execute().use { resp ->
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
        if (asset.size > 0 && part.length() != asset.size) {
            part.delete(); throw IOException("Download was incomplete. Try again.")
        }
        val want = asset.sha256
        if (want != null) {
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(want, ignoreCase = true)) {
                part.delete(); throw IOException("The downloaded file failed its checksum. Try again.")
            }
        }
        if (!part.renameTo(dest)) { part.delete(); throw IOException("Couldn't save the download.") }
        ApkInstaller.signatureProblem(c, dest)?.let { dest.delete(); throw IOException(it) }
    }
}

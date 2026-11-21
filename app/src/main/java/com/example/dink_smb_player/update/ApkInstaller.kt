package com.example.dink_smb_player.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.provider.Settings
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** APK file handling for the sideload updater: free-space check, signature check, PackageInstaller commit. */
internal object ApkInstaller {
    const val ACTION_RESULT = "com.dink.player.action.UPDATE_INSTALL_RESULT"

    /** Fixed local names: nothing from the (remote) release metadata ever becomes a path. */
    const val APK_NAME = "dink-update.apk"
    const val PART_NAME = "dink-update.apk.part"

    fun updatesDir(context: Context): File = File(context.cacheDir, "updates").also { it.mkdirs() }

    fun clearUpdates(context: Context) {
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    /** Free-space guard: the APK plus a margin for the installer's own staging copy. */
    fun hasSpaceFor(context: Context, sizeBytes: Long): Boolean =
        updatesDir(context).usableSpace >= sizeBytes + 32L * 1024 * 1024

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun certSha256(sig: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).toHex()

    /**
     * Pure rule for "is this APK signed by the installed app's key". With a single new signer
     * the new APK's signing-certificate history (key rotation) must contain the installed
     * signer; with multiple signers the full SHA-256 sets must be equal.
     */
    fun signersMatch(
        newHasMultipleSigners: Boolean,
        newHistory: Set<String>,
        newSigners: Set<String>,
        installedSigners: Set<String>,
    ): Boolean {
        if (newSigners.isEmpty() || installedSigners.isEmpty()) return false
        return if (!newHasMultipleSigners && installedSigners.size == 1) {
            installedSigners.first() in newHistory.ifEmpty { newSigners }
        } else {
            newSigners == installedSigners
        }
    }

    /**
     * Returns null when [apk] is a Dink package signed with the same certificate as the
     * installed app, else a user-facing reason.
     */
    fun signatureProblem(context: Context, apk: File): String? {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return "The downloaded file isn't a valid APK."
        if (archive.packageName != context.packageName) {
            return "The downloaded APK is for a different app (${archive.packageName})."
        }
        val installed = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()
            ?: return "Couldn't read the installed app's signature."
        val newInfo = archive.signingInfo ?: return "Couldn't verify the update's signature."
        val oldInfo = installed.signingInfo ?: return "Couldn't verify the update's signature."
        val newSigners = newInfo.apkContentsSigners.orEmpty().map(::certSha256).toSet()
        val newHistory = newInfo.signingCertificateHistory.orEmpty().map(::certSha256).toSet()
        val oldSigners = oldInfo.apkContentsSigners.orEmpty().map(::certSha256).toSet()
        if (!signersMatch(newInfo.hasMultipleSigners(), newHistory, newSigners, oldSigners)) {
            return "The update is signed with a different key than the installed app " +
                "(for example a debug build versus the release APK). Uninstall and reinstall to switch."
        }
        return null
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the per-app "install unknown apps" screen. False when this ROM has no such screen. */
    fun openUnknownSourcesSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    }

    fun abandon(context: Context, sessionId: Int) {
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
    }

    /** Streams [apk] into a PackageInstaller session and commits it (result goes to [UpdateInstallReceiver]).
     *  Returns the session id. The session is abandoned on any failure. */
    @Throws(IOException::class)
    fun commit(context: Context, apk: File): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = try {
            installer.createSession(params)
        } catch (e: Exception) {
            throw if (e is IOException) e else IOException(e.message, e)
        }
        var session: PackageInstaller.Session? = null
        try {
            session = installer.openSession(id)
            session.openWrite("dink-update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Explicit component: the receiver is declared without an intent-filter, so an
            // action + package intent never reached it and the confirm prompt never showed.
            val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_RESULT)
            val pi = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pi.intentSender)
            return id
        } catch (e: Exception) {
            runCatching { session?.abandon() }
            abandon(context, id)
            throw if (e is IOException) e else IOException(e.message, e)
        } finally {
            runCatching { session?.close() }
        }
    }
}

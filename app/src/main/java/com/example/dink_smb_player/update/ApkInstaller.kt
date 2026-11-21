package com.example.dink_smb_player.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** APK file handling for the sideload updater: free-space check, signature check, PackageInstaller commit. */
internal object ApkInstaller {
    const val ACTION_RESULT = "com.dink.player.action.UPDATE_INSTALL_RESULT"

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

    /**
     * Returns null when [apk] is a Dink package signed with the same certificate as the
     * installed app, else a user-facing reason.
     */
    @Suppress("DEPRECATION")
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
        val newSigs = archive.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet()
        val oldSigs = installed.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet()
        if (newSigs.isNullOrEmpty() || oldSigs.isNullOrEmpty()) return "Couldn't verify the update's signature."
        if (newSigs != oldSigs) {
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

    /** Streams [apk] into a PackageInstaller session and commits it; the result arrives at [UpdateInstallReceiver]. */
    @Throws(IOException::class)
    fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            session.openWrite("dink-update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ACTION_RESULT).setPackage(context.packageName)
            val pi = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pi.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw if (e is IOException) e else IOException(e.message, e)
        } finally {
            session.close()
        }
    }
}

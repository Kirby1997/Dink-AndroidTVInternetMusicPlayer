package com.example.dink_smb_player.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/** Receives PackageInstaller session results (declared in the github flavor's manifest only). */
class UpdateInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        UpdateRepository.attach(context)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (confirm == null || !foreground) {
                    // Background activity starts are blocked; let the Updates tab retry.
                    val sid = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                    if (sid >= 0) ApkInstaller.abandon(context, sid)
                    UpdateRepository.onInstallNeedsForeground()
                } else {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { UpdateRepository.onInstallNeedsForeground() }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateRepository.onInstallResult(true, null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateRepository.onInstallResult(false, "Install cancelled.")
            else -> UpdateRepository.onInstallResult(false, message ?: "Install failed (code $status).")
        }
    }
}

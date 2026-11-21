package com.example.dink_smb_player.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Receives PackageInstaller session results (declared in the github flavor's manifest only). */
class UpdateInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { UpdateRepository.onInstallResult(false, "Couldn't open the install prompt.") }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateRepository.onInstallResult(true, null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateRepository.onInstallResult(false, "Install cancelled.")
            else -> UpdateRepository.onInstallResult(false, message ?: "Install failed (code $status).")
        }
    }
}

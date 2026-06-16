package com.example.dink_smb_player.data.source.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires [LocalSyncWorker] whenever Android publishes a volume state change. Covers
 * USB drives and SD cards alike — the broadcast contains a file:// URI to the
 * mountpoint, but we don't need the path: MediaStore re-indexes the new volume on
 * its own, we just need to re-read it.
 *
 * MEDIA_MOUNTED / _UNMOUNTED / _EJECT / _REMOVED are all handled the same way:
 * a Local catalog refresh. UNMOUNT cases prune tracks that vanished with the drive.
 */
class UsbMountReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MEDIA_MOUNTED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED -> LocalSyncWorker.enqueue(context)
        }
    }
}

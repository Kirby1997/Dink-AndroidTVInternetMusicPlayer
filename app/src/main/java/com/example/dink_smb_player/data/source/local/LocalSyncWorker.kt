package com.example.dink_smb_player.data.source.local

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.dink_smb_player.data.MediaLibrary
import com.example.dink_smb_player.data.library.LibraryRepository

/**
 * Re-queries MediaStore.Audio and updates [MediaLibrary.localSongs]. Triggered on
 * volume mount events ([UsbMountReceiver]) and at app boot. Runs on WorkManager
 * because volume-mount broadcasts arrive in a constrained background context where
 * we can't safely block — WorkManager defers the scan to its own executor.
 *
 * Single unique work entry ([UNIQUE_NAME]) with REPLACE policy collapses rapid
 * mount/unmount bursts (Android sometimes fires two mount intents within a second
 * when a multi-partition drive surfaces) into one scan.
 */
class LocalSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Rehydrate the index from disk before refresh()'s mutate-and-persist, or a
        // cold-process run (volume mount / boot) would persist a snapshot missing
        // every imported SMB/cloud track. See LibraryRepository.ensureRestored.
        LibraryRepository.ensureRestored(applicationContext)
        MediaLibrary.refresh(applicationContext)
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "local-media-sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<LocalSyncWorker>().build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

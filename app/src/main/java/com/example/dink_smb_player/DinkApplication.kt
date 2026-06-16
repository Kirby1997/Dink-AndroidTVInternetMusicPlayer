package com.example.dink_smb_player

import android.app.Application
import android.content.Context
import com.example.dink_smb_player.data.prefs.SharePrefs
import com.example.dink_smb_player.data.source.MonitorWorker
import com.example.dink_smb_player.data.source.local.LocalSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.logging.Filter
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.Logger

/**
 * jaudiotagger walks every padding / atom it sees at INFO via java.util.logging.
 * On a TV with logcat tailing this floods the buffer (`id3 coldsilver.mp3:Found
 * padding starting at:859040` etc.) and tells you nothing useful.
 *
 * Setting `Logger.level` on a parent doesn't reliably propagate because jaudiotagger
 * touches its child loggers in static init and sometimes sets their own level. The
 * sturdy fix is a [Filter] installed on the root j.u.l handlers — records get
 * dropped *before* they reach logcat, regardless of any per-logger level the
 * library set on itself.
 */
class DinkApplication : Application() {
    /**
     * Process-lifetime coroutine scope. Use for work that must outlive any single
     * composable / activity — DataStore writes triggered from a wizard that
     * navigates away the moment Save is pressed, background SMB syncs, etc.
     *
     * `SupervisorJob` so one failure doesn't poison the rest. Main dispatcher is
     * fine — IO ops switch to `Dispatchers.IO` internally.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        installLoggerFilter()
        // Boot-time MediaStore scan. If READ_MEDIA_AUDIO hasn't been granted yet
        // the query returns an empty cursor — safe to call unconditionally. A
        // later grant via LocalStorageScreen re-runs refresh() explicitly.
        LocalSyncWorker.enqueue(this)
        // Periodic re-import of monitored SMB shares + local media into the library index.
        MonitorWorker.reschedule(this)
        // One-time: adopt already-imported folders as monitored so existing libraries
        // auto-update without the user re-toggling anything. Then fire a catch-up scan
        // so files added while Dink was closed appear shortly after launch.
        appScope.launch {
            runCatching { migrateAutoMonitorOnce(this@DinkApplication) }
            MonitorWorker.enqueueNow(this@DinkApplication)
        }
    }

    /** Backfill `monitoredPaths`/`monitoredFolders` from imported folders, once. Guarded
     *  by a flag so a deliberate later un-monitor isn't re-enabled on the next boot. */
    private suspend fun migrateAutoMonitorOnce(context: Context) {
        val flags = context.getSharedPreferences("dink_flags", MODE_PRIVATE)
        if (flags.getBoolean(FLAG_MONITOR_MIGRATED, false)) return
        val prefs = SharePrefs(context)
        prefs.shares.first().forEach { share ->
            if (share.importPaths.isNotEmpty() && share.monitoredPaths.isEmpty()) {
                prefs.saveShare(share.copy(monitoredPaths = share.importPaths))
            }
        }
        prefs.providers.first().forEach { provider ->
            if (provider.importFolders.isNotEmpty() && provider.monitoredFolders.isEmpty()) {
                prefs.saveProvider(provider.copy(monitoredFolders = provider.importFolders))
            }
        }
        flags.edit().putBoolean(FLAG_MONITOR_MIGRATED, true).apply()
    }

    private fun installLoggerFilter() {
        // 1. Touch LogManager so AndroidConfig has installed the AndroidLoggingHandler
        //    on the root logger before we try to mutate it.
        LogManager.getLogManager()
        val root = Logger.getLogger("")

        // 2. Hammer the root level + every existing handler level to WARNING.
        //    This is the sturdy bit — Android's AndroidLoggingHandler honours
        //    Handler.level even on versions where it ignores Handler.filter or
        //    where jaudiotagger's static-init resets per-logger levels back to INFO.
        //    Our own code uses android.util.Log directly (not j.u.l), so this
        //    only affects library chatter.
        root.level = Level.WARNING
        root.handlers.forEach { it.level = Level.WARNING }

        // 3. Belt: still set per-logger levels for any code path that checks
        //    Logger.isLoggable() early and short-circuits.
        for (name in NOISY_LOGGER_NAMES) {
            Logger.getLogger(name).level = Level.WARNING
        }

        // 4. Braces: install a Filter too. Some Android versions honour it on the
        //    AndroidLoggingHandler, killing records regardless of level.
        val drop = Filter { record ->
            val name = record.loggerName ?: return@Filter true
            !NOISY_LOGGER_PREFIXES.any { name == it || name.startsWith("$it.") }
        }
        root.handlers.forEach { handler ->
            val existing = handler.filter
            handler.filter = if (existing == null) {
                drop
            } else {
                Filter { record -> existing.isLoggable(record) && drop.isLoggable(record) }
            }
        }
    }

    companion object {
        private const val FLAG_MONITOR_MIGRATED = "monitor_migrated"

        private val NOISY_LOGGER_PREFIXES = listOf(
            "org.jaudiotagger",
            "id3", "audio", "tag", "mp3", "mp4", "flac", "ogg",
        )
        private val NOISY_LOGGER_NAMES = listOf(
            "org.jaudiotagger",
            "org.jaudiotagger.audio",
            "org.jaudiotagger.audio.mp3",
            "org.jaudiotagger.audio.mp4",
            "org.jaudiotagger.audio.flac",
            "org.jaudiotagger.tag",
            "org.jaudiotagger.tag.id3",
            "org.jaudiotagger.tag.id3.framebody",
            "id3", "audio", "tag", "mp3",
        )
    }
}

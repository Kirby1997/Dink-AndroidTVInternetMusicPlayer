package com.example.dink_smb_player.update

object UpdatePolicy {
    const val LAUNCH_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** Whether a launch-time auto-check should run now. A clock that went backwards
     *  (lastCheck in the future) counts as due so a bad timestamp can't suppress checks forever. */
    fun isLaunchCheckDue(
        autoCheck: Boolean,
        lastCheckEpochMs: Long,
        nowEpochMs: Long,
        intervalMs: Long = LAUNCH_CHECK_INTERVAL_MS,
    ): Boolean {
        if (!autoCheck) return false
        if (lastCheckEpochMs <= 0L) return true
        if (lastCheckEpochMs > nowEpochMs) return true
        return nowEpochMs - lastCheckEpochMs >= intervalMs
    }
}

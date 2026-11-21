package com.example.dink_smb_player.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val h = 60L * 60 * 1000
    private val now = 1_000_000_000_000L

    @Test fun offNeverDue() = assertFalse(UpdatePolicy.isLaunchCheckDue(false, 0, now))
    @Test fun neverCheckedIsDue() = assertTrue(UpdatePolicy.isLaunchCheckDue(true, 0, now))
    @Test fun recentNotDue() = assertFalse(UpdatePolicy.isLaunchCheckDue(true, now - 5 * h, now))
    @Test fun exactlySixHoursDue() = assertTrue(UpdatePolicy.isLaunchCheckDue(true, now - 6 * h, now))
    @Test fun oldDue() = assertTrue(UpdatePolicy.isLaunchCheckDue(true, now - 30 * h, now))
    @Test fun futureTimestampDue() = assertTrue(UpdatePolicy.isLaunchCheckDue(true, now + h, now))
}

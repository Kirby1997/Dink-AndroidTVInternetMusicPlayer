package com.example.dink_smb_player.player

import com.example.dink_smb_player.player.ControllerAllowlist.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** BLD-11: which MediaSession controllers count as expected. */
class ControllerAllowlistTest {

    private val own = "com.dink.player"

    private fun decide(pkg: String, uid: Int = 10_123, trusted: Boolean = false) =
        ControllerAllowlist.decide(packageName = pkg, uid = uid, isTrusted = trusted, ownPackage = own)

    @Test
    fun `own package is allowed even when untrusted`() {
        assertEquals(Decision.ALLOWED, decide(own))
    }

    @Test
    fun `trusted controllers are allowed whatever the package`() {
        assertEquals(Decision.ALLOWED, decide("com.some.vendor.remote", trusted = true))
    }

    @Test
    fun `platform uids are allowed`() {
        assertEquals(Decision.ALLOWED, decide("whatever", uid = 1000))
        assertEquals(Decision.ALLOWED, decide("com.android.bluetooth", uid = 1002))
        assertEquals(Decision.ALLOWED, decide("x", uid = 0))
    }

    @Test
    fun `known tv system packages are allowed`() {
        listOf(
            "com.android.systemui",
            "com.google.android.katniss",
            "com.google.android.apps.tv.launcherx",
            "android.media.session.MediaController",
        ).forEach { assertEquals(it, Decision.ALLOWED, decide(it)) }
    }

    @Test
    fun `unknown third-party app is flagged`() {
        assertEquals(Decision.UNKNOWN, decide("com.random.app"))
        // Negative / unset uid is not treated as a platform uid.
        assertEquals(Decision.UNKNOWN, decide("com.random.app", uid = -1))
        // Prefix of a known package is not a match.
        assertEquals(Decision.UNKNOWN, decide("com.google.android.katniss.evil"))
    }

    @Test
    fun `enforcement stays off until verified on device`() {
        assertFalse(ControllerAllowlist.ENFORCE)
    }
}

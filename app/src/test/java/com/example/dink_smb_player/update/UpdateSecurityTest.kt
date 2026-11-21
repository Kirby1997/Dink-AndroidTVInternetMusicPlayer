package com.example.dink_smb_player.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSecurityTest {
    // ---- semver pre-release precedence ----
    @Test fun rcOrder() {
        assertTrue(Semver.isNewer("1.5.0-rc2", "1.5.0-rc1"))
        assertTrue(Semver.isNewer("1.5.0", "1.5.0-rc2"))
        assertFalse(Semver.isNewer("1.5.0-rc1", "1.5.0"))
        assertTrue(Semver.isNewer("1.5.0-rc1", "1.4.9"))
    }

    @Test fun dottedNumericPreRelease() {
        assertTrue(Semver.isNewer("1.0.0-rc.10", "1.0.0-rc.2"))
        assertTrue(Semver.isNewer("1.0.0-rc.1", "1.0.0-rc"))
        assertTrue(Semver.isNewer("1.0.0-beta", "1.0.0-1"))
    }

    @Test fun longComponents() {
        assertTrue(Semver.isNewer("1.5.4000000000", "1.5.3999999999"))
    }

    // ---- pickApk preference ----
    private fun a(name: String) = AssetDto(name, "https://github.com/x/$name", 10, null)

    @Test fun prefersExactName() {
        val r = ReleaseParser.pickApk(listOf(a("dink-1.5.0-release.apk"), a("dink-1.5.0.apk"), a("dink-other.apk")), "1.5.0")
        assertEquals("dink-1.5.0.apk", r!!.name)
    }

    @Test fun thenReleaseThenOtherDink() {
        assertEquals("dink-x-release.apk", ReleaseParser.pickApk(listOf(a("dink-y.apk"), a("dink-x-release.apk")), "9")!!.name)
        assertEquals("dink-y.apk", ReleaseParser.pickApk(listOf(a("other.apk"), a("dink-y.apk")), "9")!!.name)
    }

    @Test fun neverDebug() {
        assertNull(ReleaseParser.pickApk(listOf(a("dink-1.5.0-debug.apk"), a("app-github-debug.apk")), "1.5.0"))
    }

    // ---- download URL allowlist ----
    @Test fun allowsGithubHosts() {
        assertTrue(DownloadUrlPolicy.isAllowed("https://github.com/o/r/releases/download/v1/dink-1.apk"))
        assertTrue(DownloadUrlPolicy.isAllowed("https://objects.githubusercontent.com/x"))
        assertTrue(DownloadUrlPolicy.isAllowed("https://release-assets.githubusercontent.com/x"))
    }

    @Test fun rejectsOthers() {
        assertFalse(DownloadUrlPolicy.isAllowed("http://github.com/x"))
        assertFalse(DownloadUrlPolicy.isAllowed("https://evil.com/x"))
        assertFalse(DownloadUrlPolicy.isAllowed("https://github.com.evil.com/x"))
        assertFalse(DownloadUrlPolicy.isAllowed("https://evilgithubusercontent.com/x"))
        assertFalse(DownloadUrlPolicy.isAllowed("https://github.com@evil.com/x"))
        assertFalse(DownloadUrlPolicy.isAllowed("file:///sdcard/x.apk"))
        assertFalse(DownloadUrlPolicy.isAllowed("not a url"))
    }

    // ---- signer matching ----
    @Test fun singleSignerHistory() {
        assertTrue(ApkInstaller.signersMatch(false, setOf("old", "new"), setOf("new"), setOf("old")))
        assertFalse(ApkInstaller.signersMatch(false, setOf("x", "new"), setOf("new"), setOf("old")))
        assertTrue(ApkInstaller.signersMatch(false, emptySet(), setOf("a"), setOf("a")))
    }

    @Test fun multiSignerRequiresEqualSets() {
        assertTrue(ApkInstaller.signersMatch(true, emptySet(), setOf("a", "b"), setOf("a", "b")))
        assertFalse(ApkInstaller.signersMatch(true, emptySet(), setOf("a", "b"), setOf("a")))
        assertFalse(ApkInstaller.signersMatch(false, emptySet(), emptySet(), setOf("a")))
    }
}

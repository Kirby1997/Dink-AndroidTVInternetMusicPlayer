package com.example.dink_smb_player.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemverTest {
    @Test fun stripsLeadingV() = assertEquals(0, Semver.compare("v1.5.0", "1.5.0"))
    @Test fun shortEqualsLong() = assertEquals(0, Semver.compare("1.5", "1.5.0"))
    @Test fun numericNotLexical() = assertTrue(Semver.isNewer("1.10.0", "1.9.9"))
    @Test fun patchBump() = assertTrue(Semver.isNewer("v1.5.1", "1.5.0"))
    @Test fun olderNotNewer() = assertFalse(Semver.isNewer("1.4.9", "1.5.0"))
    @Test fun sameNotNewer() = assertFalse(Semver.isNewer("v1.5.0", "1.5.0"))
    @Test fun longerNewer() = assertTrue(Semver.isNewer("1.5.0.1", "1.5"))
    @Test fun prereleaseBelowRelease() = assertTrue(Semver.isNewer("1.6.0", "1.6.0-beta1"))
    @Test fun buildMetadataIgnored() = assertEquals(0, Semver.compare("1.5.0+7", "1.5.0"))
    @Test fun garbageIsZero() = assertFalse(Semver.isNewer("abc", "0.0.1"))
}

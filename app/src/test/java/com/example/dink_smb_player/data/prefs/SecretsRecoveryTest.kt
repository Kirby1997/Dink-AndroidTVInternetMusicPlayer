package com.example.dink_smb_player.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/** LIB-1: a broken master key must never crash-loop; reset once, else in-memory. Review
 *  finding 7: only real corruption resets; a transient Keystore error is retried, then runs
 *  in memory without deleting anything. */
class SecretsRecoveryTest {

    @Test
    fun healthyStoreOpensWithoutReset() {
        var resets = 0
        val (v, outcome) = SecretsRecovery.open(build = { "store" }, reset = { resets++ }, fallback = { "mem" })
        assertEquals("store", v)
        assertEquals(SecretsRecovery.Outcome.Ok, outcome)
        assertEquals(0, resets)
    }

    @Test
    fun brokenKeyIsResetThenRebuilt() {
        var keyDeleted = false
        val (v, outcome) = SecretsRecovery.open(
            // Mirrors the real crash loop: build keeps failing until the key alias is gone.
            build = { if (!keyDeleted) throw GeneralSecurityException("bad key") else "store" },
            reset = { keyDeleted = true },
            fallback = { "mem" },
            sleep = {},
        )
        assertEquals("store", v)
        assertEquals(SecretsRecovery.Outcome.Reset, outcome)
    }

    @Test
    fun corruptRebuildFailingFallsBackInsteadOfThrowing() {
        var builds = 0
        val (v, outcome) = SecretsRecovery.open(
            build = { builds++; throw AEADBadTagException("tag mismatch") },
            reset = { throw IllegalStateException("reset failed too") },
            fallback = { "mem" },
            sleep = {},
        )
        assertEquals("mem", v)
        assertEquals(SecretsRecovery.Outcome.Unavailable, outcome)
        assertEquals("corruption isn't retried; one rebuild after the reset", 2, builds)
    }

    @Test
    fun transientKeystoreErrorIsRetriedAndNeverResets() {
        var builds = 0
        var resets = 0
        val sleeps = ArrayList<Long>()
        val (v, outcome) = SecretsRecovery.open(
            build = { builds++; if (builds < 3) throw KeyStoreException("keystore busy") else "store" },
            reset = { resets++ },
            fallback = { "mem" },
            sleep = { sleeps += it },
        )
        assertEquals("store", v)
        assertEquals(SecretsRecovery.Outcome.Ok, outcome)
        assertEquals(0, resets)
        assertEquals(listOf(SecretsRecovery.BACKOFF_MS, SecretsRecovery.BACKOFF_MS * 2), sleeps)
    }

    @Test
    fun persistentNonCorruptionFailureUsesMemoryAndDeletesNothing() {
        var builds = 0
        var resets = 0
        val (v, outcome) = SecretsRecovery.open(
            build = { builds++; throw SecurityException("keystore dead") },
            reset = { resets++ },
            fallback = { "mem" },
            sleep = {},
        )
        assertEquals("mem", v)
        assertEquals(SecretsRecovery.Outcome.Unavailable, outcome)
        assertEquals(SecretsRecovery.BUILD_ATTEMPTS, builds)
        assertEquals("saved passwords survive for the next launch", 0, resets)
    }

    @Test
    fun corruptionIsRecognisedThroughWrappersAndTransientIsNot() {
        assertTrue(SecretsRecovery.isCorruption(AEADBadTagException()))
        assertTrue(SecretsRecovery.isCorruption(KeyStoreException("the master key exists but is unusable", UnrecoverableKeyException())))
        assertTrue(SecretsRecovery.isCorruption(IOException("wrap", InvalidProtocolBufferException("bad keyset"))))
        assertTrue(SecretsRecovery.isCorruption(InvalidKeyException("key permanently invalidated")))
        assertTrue("Tink's bare GSE: corrupted key material", SecretsRecovery.isCorruption(GeneralSecurityException("invalid keyset")))
        assertFalse(SecretsRecovery.isCorruption(KeyStoreException("system error")))
        assertFalse(SecretsRecovery.isCorruption(ProviderException("keystore2 binder died")))
        assertFalse(SecretsRecovery.isCorruption(IOException("EIO")))
        assertFalse(SecretsRecovery.isCorruption(SecurityException("denied")))
    }

    /** Stands in for protobuf's (or Tink's shaded) class, matched by simple name. */
    private class InvalidProtocolBufferException(msg: String) : IOException(msg)

    @Test
    fun inMemoryPrefsBehaveLikePrefs() {
        val p = InMemoryPrefs()
        p.edit().putString("smb:1", "x").apply()
        assertEquals("x", p.getString("smb:1", null))
        assertTrue(p.contains("smb:1"))
        p.edit().remove("smb:1").commit()
        assertNull(p.getString("smb:1", null))
        p.edit().putString("a", "1").putString("b", "2").apply()
        p.edit().clear().putString("c", "3").apply()
        assertEquals(setOf("c"), p.all.keys)
    }
}

package com.example.dink_smb_player.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore

private const val LEGACY_CLOUD_PREFIX = "cloud:"

@Serializable
data class SmbCreds(val user: String, val password: String, val domain: String? = null)

/**
 * Persists SMB credentials in EncryptedSharedPreferences.
 * Keys are namespaced by source id so a single share id maps cleanly to its secret blob.
 *
 * NB: never log values read from this store.
 */
class EncryptedShareStore private constructor(context: Context) {

    /** What the user needs to be told about their saved credentials, if anything. */
    enum class Notice {
        /** The secure store was unreadable and has been wiped: saved passwords/tokens are gone. */
        Reset,
        /** The secure store can't be opened at all this session: credentials are kept in memory only. */
        Unavailable,
    }

    companion object {
        @Volatile private var instance: EncryptedShareStore? = null

        private const val SECRETS_FILE = "dink_secrets"
        // Plain (unencrypted) prefs holding only the "credentials were reset" flag, so a
        // reset that happens in a UI-less process (worker, media-button resume) is still
        // shown the next time the UI is up.
        private const val META_FILE = "dink_secrets_meta"
        private const val KEY_RESET_PENDING = "reset_pending"

        private val _notice = MutableStateFlow<Notice?>(null)
        /** Non-null while there's an unacknowledged credential notice for the UI. */
        val notice: StateFlow<Notice?> = _notice.asStateFlow()

        /**
         * Process-wide store. Construction is Keystore + Tink init (~350 ms on the TV);
         * building one per caller paid that repeatedly (twice concurrently at launch, again
         * per SMB folder opened) and let two instances race the corrupt-file recovery.
         * First call may block — call it off the main thread.
         */
        fun get(context: Context): EncryptedShareStore =
            instance ?: synchronized(this) {
                instance ?: EncryptedShareStore(context.applicationContext).also { instance = it }
            }

        /** The UI showed [notice]; don't show it again. */
        fun acknowledgeNotice(context: Context) {
            _notice.value = null
            runCatching {
                context.applicationContext.getSharedPreferences(META_FILE, Context.MODE_PRIVATE)
                    .edit().remove(KEY_RESET_PENDING).apply()
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    // A wedged Tink keyset / secrets file / Keystore master key makes create() throw
    // forever, which crash-looped every launch (the old recovery wiped the file but kept
    // the broken master key, so the rebuild threw again). Recover by deleting BOTH the
    // Keystore alias and the file and rebuilding once — but only when the failure says the
    // key or data is bad; a Keystore hiccup is retried, then run on an in-memory store for
    // the session (nothing deleted, the next launch tries again) rather than crash.
    private val prefs: SharedPreferences = run {
        val (p, outcome) = SecretsRecovery.open(
            build = { build(context) },
            reset = { resetKeyAndFile(context) },
            fallback = { InMemoryPrefs() },
            log = { msg, t -> android.util.Log.e("EncryptedShareStore", msg, t) },
        )
        val meta = runCatching { context.getSharedPreferences(META_FILE, Context.MODE_PRIVATE) }.getOrNull()
        when (outcome) {
            SecretsRecovery.Outcome.Ok ->
                if (meta?.getBoolean(KEY_RESET_PENDING, false) == true) _notice.value = Notice.Reset
            SecretsRecovery.Outcome.Reset -> {
                runCatching { meta?.edit()?.putBoolean(KEY_RESET_PENDING, true)?.apply() }
                _notice.value = Notice.Reset
            }
            SecretsRecovery.Outcome.Unavailable -> _notice.value = Notice.Unavailable
        }
        p
    }

    private fun build(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            SECRETS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Delete the master key from AndroidKeyStore and the secrets file (which also holds
     *  the Tink keysets wrapped by that key). Each step independent: a missing alias or
     *  file is fine. This store is the only user of the default master-key alias. */
    private fun resetKeyAndFile(context: Context) {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }.onFailure { android.util.Log.e("EncryptedShareStore", "keystore alias delete failed", it) }
        runCatching { context.deleteSharedPreferences(SECRETS_FILE) }
            .onFailure { android.util.Log.e("EncryptedShareStore", "secrets file delete failed", it) }
    }

    // Every accessor below is guarded: an individual entry (or the key, mid-session) can
    // go bad after construction, and a throw here would surface as a crash in playback,
    // a worker, or the add-share flow.

    // ---------- SMB ----------

    fun putSmbCreds(shareId: String, creds: SmbCreds) {
        prefs.edit().putString(smbKey(shareId), json.encodeToString(creds)).apply()
    }

    fun getSmbCreds(shareId: String): SmbCreds? {
        // getString itself can throw if this entry's ciphertext is corrupt — guard
        // the whole read so one bad value doesn't bubble up as a crash.
        val raw = runCatching { prefs.getString(smbKey(shareId), null) }.getOrNull() ?: return null
        return runCatching { json.decodeFromString<SmbCreds>(raw) }.getOrNull()
    }

    fun deleteSmbCreds(shareId: String) {
        runCatching { prefs.edit().remove(smbKey(shareId)).apply() }
            .onFailure { android.util.Log.e("EncryptedShareStore", "deleteSmbCreds failed", it) }
    }

    // ---------- Legacy ----------

    /** Delete OAuth tokens the removed cloud (Google Drive) feature stored under
     *  `cloud:<provider id>`. Returns how many were removed; no write when there are none. */
    fun purgeLegacyCloudTokens(): Int = runCatching {
        val stale = prefs.all.keys.filter { it.startsWith(LEGACY_CLOUD_PREFIX) }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach { remove(it) } }.apply()
        stale.size
    }.onFailure { android.util.Log.e("EncryptedShareStore", "purgeLegacyCloudTokens failed", it) }
        .getOrDefault(0)

    private fun smbKey(id: String) = "smb:$id"
}

/**
 * Open-with-recovery policy for the secrets store, separated from Android so it's unit
 * testable. Never throws.
 *
 *  1. Try [build] up to [BUILD_ATTEMPTS] times with a short backoff: the Keystore daemon can
 *     fail a call transiently (busy at boot, binder hiccup), and resetting on that would wipe
 *     every saved password for nothing.
 *  2. If the failure means corruption ([isCorruption]: the key or the stored bytes are bad),
 *     [reset] (delete key + file) and build once more.
 *  3. Otherwise, or if the rebuild fails too, use [fallback] for the session. A non-corruption
 *     failure deletes nothing, so a later launch retries with the saved data intact.
 */
internal object SecretsRecovery {
    enum class Outcome { Ok, Reset, Unavailable }

    const val BUILD_ATTEMPTS = 3
    const val BACKOFF_MS = 150L

    fun <T> open(
        build: () -> T,
        reset: () -> Unit,
        fallback: () -> T,
        log: (String, Throwable) -> Unit = { _, _ -> },
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): Pair<T, Outcome> {
        var error: Throwable? = null
        for (attempt in 1..BUILD_ATTEMPTS) {
            val r = runCatching(build)
            r.getOrNull()?.let { return it to Outcome.Ok }
            val e = r.exceptionOrNull()!!
            error = e
            // Bad key / bad bytes won't fix themselves on a retry.
            if (isCorruption(e)) break
            if (attempt < BUILD_ATTEMPTS) {
                log("secrets store open failed (attempt $attempt) — retrying", e)
                runCatching { sleep(BACKOFF_MS * attempt) }
            }
        }
        val failure = error!!
        if (!isCorruption(failure)) {
            log("secrets store unavailable (not corruption) — in-memory store this session, nothing deleted", failure)
            return fallback() to Outcome.Unavailable
        }
        log("secrets store corrupt — deleting master key + file and rebuilding", failure)
        runCatching(reset).onFailure { log("secrets reset failed", it) }
        val second = runCatching(build)
        second.getOrNull()?.let { return it to Outcome.Reset }
        log("secrets store rebuild failed — using in-memory store this session", second.exceptionOrNull()!!)
        return fallback() to Outcome.Unavailable
    }

    /**
     * True when [t] (or a cause) says the master key or the stored keyset/values are bad, as
     * opposed to the Keystore being unreachable. What EncryptedSharedPreferences / Tink throw:
     *  - AEADBadTagException / BadPaddingException: ciphertext doesn't verify under this key.
     *  - InvalidKeyException (incl. KeyPermanentlyInvalidatedException), UnrecoverableKeyException:
     *    the Keystore key is invalidated or unreadable.
     *  - InvalidProtocolBufferException (plain or Tink-shaded protobuf): the keyset bytes don't parse.
     *  - a bare GeneralSecurityException: Tink's "invalid keyset, corrupted key material",
     *    "decryption failed", etc.
     * Not corruption: KeyStoreException, NoSuchAlgorithm/Provider, ProviderException, IOException,
     * SecurityException — a Keystore or I/O problem that may clear on its own.
     */
    fun isCorruption(t: Throwable): Boolean {
        var e: Throwable? = t
        var depth = 0
        while (e != null && depth++ < 16) {
            if (e is javax.crypto.BadPaddingException ||
                e is java.security.InvalidKeyException ||
                e is java.security.UnrecoverableKeyException ||
                e.javaClass.simpleName == "InvalidProtocolBufferException" ||
                e.javaClass == java.security.GeneralSecurityException::class.java
            ) return true
            e = e.cause
        }
        return false
    }
}

/** Process-memory [SharedPreferences] — the last-resort secrets store when the Keystore
 *  is unusable. Credentials entered this session work until the process dies. */
internal class InMemoryPrefs : SharedPreferences {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += l }
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= l }

    private inner class Editor : SharedPreferences.Editor {
        private val puts = HashMap<String, Any?>()
        private var clearAll = false
        private fun put(key: String, value: Any?): SharedPreferences.Editor { puts[key] = value; return this }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values?.toSet())
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = put(key, null)
        override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
        override fun commit(): Boolean {
            synchronized(map) {
                if (clearAll) map.clear()
                puts.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
            puts.keys.forEach { k -> listeners.forEach { it.onSharedPreferenceChanged(this@InMemoryPrefs, k) } }
            return true
        }
        override fun apply() { commit() }
    }
}

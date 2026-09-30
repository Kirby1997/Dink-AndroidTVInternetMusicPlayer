package com.example.dink_smb_player.data.prefs

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.dink_smb_player.data.model.SmbShare
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// A corrupt prefs file otherwise throws on every read AND write forever (a share
// can never be re-added). Reset to empty on corruption so the store self-heals.
private val Context.shareDataStore by preferencesDataStore(
    name = "dink_sources",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private val SMB_SHARES_KEY = stringSetPreferencesKey("smb_shares_json")
// Written by the removed cloud (Google Drive) feature; only ever deleted now.
private val LEGACY_CLOUD_PROVIDERS_KEY = stringSetPreferencesKey("cloud_providers_json")

/**
 * Non-secret persistence for SMB shares. Stored as a set of JSON blobs
 * (one per source) inside a Preferences DataStore — small N, no need for a separate
 * Room table for what is fundamentally configuration. Secrets live in [EncryptedShareStore].
 *
 * Addresses Plan.txt pain #1: shares survive process death and reboot.
 */
class SharePrefs internal constructor(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.shareDataStore)

    private val json = Json { ignoreUnknownKeys = true }

    val shares: Flow<List<SmbShare>> = store.data
        .catch { e -> Log.e("SharePrefs", "shares read failed, emitting empty", e); emit(emptyPreferences()) }
        .map { prefs -> decodeSet<SmbShare>(prefs[SMB_SHARES_KEY]) }

    suspend fun saveShare(share: SmbShare) {
        store.edit { prefs ->
            val current = decodeSet<SmbShare>(prefs[SMB_SHARES_KEY])
            val next = current.filter { it.id != share.id } + share
            prefs[SMB_SHARES_KEY] = encodeSet(next)
        }
    }

    /**
     * Read-modify-write of ONE share against its CURRENT stored value, atomically inside
     * [DataStore.edit]. Use this — not [saveShare] with a copy taken earlier — for every
     * write-back after async work (import completion stamps, folder ticks, monitor
     * stamps): a stale copy would clobber concurrent edits and, worse, re-insert a share
     * the user deleted mid-import. No-op (returns null) when [id] is no longer stored;
     * otherwise returns the updated share. [transform] must keep the id.
     */
    suspend fun updateShare(id: String, transform: (SmbShare) -> SmbShare): SmbShare? {
        var updated: SmbShare? = null
        store.edit { prefs ->
            val current = decodeSet<SmbShare>(prefs[SMB_SHARES_KEY])
            val existing = current.firstOrNull { it.id == id } ?: return@edit
            val next = transform(existing).copy(id = id)
            prefs[SMB_SHARES_KEY] = encodeSet(current.map { if (it.id == id) next else it })
            updated = next
        }
        return updated
    }

    suspend fun deleteShare(id: String) {
        store.edit { prefs ->
            val current = decodeSet<SmbShare>(prefs[SMB_SHARES_KEY])
            prefs[SMB_SHARES_KEY] = encodeSet(current.filter { it.id != id })
        }
    }

    /** Drop the provider list the removed cloud feature stored. No-op (no write) when
     *  there is nothing to drop. */
    suspend fun purgeLegacyCloud() {
        if (store.data.first()[LEGACY_CLOUD_PROVIDERS_KEY] == null) return
        store.edit { it.remove(LEGACY_CLOUD_PROVIDERS_KEY) }
    }

    private inline fun <reified T> decodeSet(set: Set<String>?): List<T> =
        set?.mapNotNull { blob ->
            runCatching { json.decodeFromString<T>(blob) }
                .onFailure { Log.w("SharePrefs", "dropping unparseable source blob: ${it.message}") }
                .getOrNull()
        } ?: emptyList()

    private inline fun <reified T> encodeSet(list: List<T>): Set<String> =
        list.map { json.encodeToString(it) }.toSet()
}

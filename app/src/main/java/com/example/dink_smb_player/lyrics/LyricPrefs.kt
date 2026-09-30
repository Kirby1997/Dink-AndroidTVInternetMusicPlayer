package com.example.dink_smb_player.lyrics

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

private val Context.lyricDataStore by preferencesDataStore(name = "dink_lyrics")

/**
 * Online-lyric settings: the master "Online lyrics" switch plus per-provider toggles.
 * [online] defaults OFF — nothing is sent to any lyric service until the user opts in
 * (the privacy policy promises exactly that). Per-provider flags are kept while the
 * master is off, so re-enabling restores the user's previous selection.
 */
data class LyricConfig(
    val online: Boolean = false,
    val providers: Map<String, Boolean> = emptyMap(),
) {
    fun isProviderOn(p: OnlineLyricProvider): Boolean = providers[p.id] ?: p.defaultEnabled

    /** Providers [LyricChain] may query, in chain order. Empty when [online] is off. */
    fun activeProviders(all: List<OnlineLyricProvider>): List<OnlineLyricProvider> =
        if (!online) emptyList() else all.filter { isProviderOn(it) }

    /** Identifies what a lookup under this config may consult; [LyricCache] entries
     *  resolved under a different fingerprint are misses. */
    fun fingerprint(all: List<OnlineLyricProvider>): String =
        if (!online) "off" else "on:" + activeProviders(all).joinToString(",") { it.id }
}

/**
 * In-memory cache of the lyric settings, so [LyricChain] needn't read DataStore per track. Hydrated at boot from
 * [LyricPrefs] and updated immediately when the user flips a Settings toggle (so the
 * next track resolves with the new setting without a round-trip).
 */
object LyricSettings {
    @Volatile
    private var current: LyricConfig = LyricConfig()

    fun config(): LyricConfig = current

    fun activeProviders(): List<OnlineLyricProvider> =
        current.activeProviders(OnlineLyricProviders.all)

    fun fingerprint(): String = current.fingerprint(OnlineLyricProviders.all)

    private val _fingerprintFlow = MutableStateFlow(fingerprint())

    /** [fingerprint] as observable state, updated by boot [hydrate] and user toggles alike.
     *  The now-playing lyric resolver keys on it, so a change to what the chain may consult
     *  (Online lyrics switched on, a provider toggled) re-resolves the current track. */
    val fingerprintFlow: StateFlow<String> = _fingerprintFlow.asStateFlow()

    /** Called with the old and new fingerprint when a user toggle changes what the chain
     *  may consult — [LyricChain] clears its cache (LYR-9). Boot [hydrate] doesn't fire it:
     *  cached entries carry their fingerprint, so a stale one simply misses. */
    @Volatile
    var onUserChange: (() -> Unit)? = null

    /** Replace everything (boot hydrate). */
    fun hydrate(config: LyricConfig) {
        current = config
        _fingerprintFlow.value = fingerprint()
    }

    fun setOnline(enabled: Boolean) {
        userUpdate(current.copy(online = enabled))
    }

    fun set(id: String, enabled: Boolean) {
        userUpdate(current.copy(providers = current.providers + (id to enabled)))
    }

    private fun userUpdate(next: LyricConfig) {
        val before = fingerprint()
        current = next
        val after = fingerprint()
        if (after != before) onUserChange?.invoke()
        _fingerprintFlow.value = after
    }
}

/**
 * Persists lyric settings. Keys: `lyrics_online` (master) and `lyric_<providerId>`.
 * Keys for removed providers (Genius, Musixmatch) may linger in old installs; they're
 * never read, and are dropped on the next settings write.
 */
class LyricPrefs(private val context: Context) {

    val toggles: Flow<LyricConfig> = context.lyricDataStore.data.map { prefs ->
        LyricConfig(
            online = prefs[ONLINE_KEY] ?: false,
            providers = OnlineLyricProviders.all.associate { p ->
                p.id to (prefs[booleanPreferencesKey("lyric_${p.id}")] ?: p.defaultEnabled)
            },
        )
    }

    suspend fun setOnline(enabled: Boolean) {
        context.lyricDataStore.edit {
            it[ONLINE_KEY] = enabled
            dropRemovedProviders(it)
        }
    }

    suspend fun setProvider(id: String, enabled: Boolean) {
        context.lyricDataStore.edit {
            it[booleanPreferencesKey("lyric_$id")] = enabled
            dropRemovedProviders(it)
        }
    }

    private fun dropRemovedProviders(prefs: MutablePreferences) {
        REMOVED_PROVIDER_IDS.forEach { prefs.remove(booleanPreferencesKey("lyric_$it")) }
    }

    private companion object {
        val ONLINE_KEY = booleanPreferencesKey("lyrics_online")
        val REMOVED_PROVIDER_IDS = listOf("genius", "musixmatch")
    }
}

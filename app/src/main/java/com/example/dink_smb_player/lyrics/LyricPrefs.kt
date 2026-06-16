package com.example.dink_smb_player.lyrics

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.lyricDataStore by preferencesDataStore(name = "dink_lyrics")

/**
 * In-memory cache of per-provider lyric toggles. [LyricChain] runs synchronously on
 * an IO thread and can't suspend to read DataStore, so it reads here. Hydrated at
 * boot from [LyricPrefs] and updated immediately when the user flips a Settings
 * toggle (so the next track resolves with the new setting without a round-trip).
 */
object LyricSettings {
    @Volatile
    private var flags: Map<String, Boolean> =
        OnlineLyricProviders.all.associate { it.id to it.defaultEnabled }

    fun isEnabled(id: String): Boolean =
        flags[id] ?: OnlineLyricProviders.all.firstOrNull { it.id == id }?.defaultEnabled ?: true

    fun snapshot(): Map<String, Boolean> = flags

    /** Replace all flags (boot hydrate), filling defaults for any missing provider. */
    fun hydrate(map: Map<String, Boolean>) {
        flags = OnlineLyricProviders.all.associate { it.id to (map[it.id] ?: it.defaultEnabled) }
    }

    fun set(id: String, enabled: Boolean) {
        flags = flags + (id to enabled)
    }
}

/** Persists per-provider lyric toggles. Keys: `lyric_<providerId>`. */
class LyricPrefs(private val context: Context) {

    val toggles: Flow<Map<String, Boolean>> = context.lyricDataStore.data.map { prefs ->
        OnlineLyricProviders.all.associate { p ->
            p.id to (prefs[booleanPreferencesKey("lyric_${p.id}")] ?: p.defaultEnabled)
        }
    }

    suspend fun setProvider(id: String, enabled: Boolean) {
        context.lyricDataStore.edit { it[booleanPreferencesKey("lyric_$id")] = enabled }
    }
}

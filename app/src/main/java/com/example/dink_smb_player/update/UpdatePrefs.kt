package com.example.dink_smb_player.update

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.dink_smb_player.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.updateDataStore by preferencesDataStore(name = "dink_update")

/** Updater settings: auto-check toggle (default ON in the sideload flavor), last check time, cached ETag. */
class UpdatePrefs(private val context: Context) {
    val autoCheck: Flow<Boolean> = context.updateDataStore.data.map { it[AUTO] ?: BuildConfig.SELF_UPDATE }

    suspend fun autoCheckNow(): Boolean = autoCheck.first()
    suspend fun lastCheck(): Long = context.updateDataStore.data.first()[LAST] ?: 0L
    suspend fun etag(): String? = context.updateDataStore.data.first()[ETAG]

    suspend fun setAutoCheck(on: Boolean) { context.updateDataStore.edit { it[AUTO] = on } }
    suspend fun setLastCheck(ms: Long) { context.updateDataStore.edit { it[LAST] = ms } }
    suspend fun setEtag(tag: String?) {
        context.updateDataStore.edit { if (tag == null) it.remove(ETAG) else it[ETAG] = tag }
    }

    private companion object {
        val AUTO = booleanPreferencesKey("auto_check")
        val LAST = longPreferencesKey("last_check_epoch_ms")
        val ETAG = stringPreferencesKey("releases_etag")
    }
}

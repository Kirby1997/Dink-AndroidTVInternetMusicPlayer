package com.example.dink_smb_player.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SmbCreds(val user: String, val password: String, val domain: String? = null)

@Serializable
data class CloudToken(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMs: Long? = null,
)

/**
 * Persists SMB credentials and cloud OAuth tokens in EncryptedSharedPreferences.
 * Keys are namespaced by source id so a single share/provider id maps cleanly to its secret blob.
 *
 * NB: never log values read from this store.
 */
class EncryptedShareStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "dink_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ---------- SMB ----------

    fun putSmbCreds(shareId: String, creds: SmbCreds) {
        prefs.edit().putString(smbKey(shareId), json.encodeToString(creds)).apply()
    }

    fun getSmbCreds(shareId: String): SmbCreds? {
        val raw = prefs.getString(smbKey(shareId), null) ?: return null
        return runCatching { json.decodeFromString<SmbCreds>(raw) }.getOrNull()
    }

    fun deleteSmbCreds(shareId: String) {
        prefs.edit().remove(smbKey(shareId)).apply()
    }

    // ---------- Cloud ----------

    fun putCloudToken(providerId: String, token: CloudToken) {
        prefs.edit().putString(cloudKey(providerId), json.encodeToString(token)).apply()
    }

    fun getCloudToken(providerId: String): CloudToken? {
        val raw = prefs.getString(cloudKey(providerId), null) ?: return null
        return runCatching { json.decodeFromString<CloudToken>(raw) }.getOrNull()
    }

    fun deleteCloudToken(providerId: String) {
        prefs.edit().remove(cloudKey(providerId)).apply()
    }

    private fun smbKey(id: String) = "smb:$id"
    private fun cloudKey(id: String) = "cloud:$id"
}

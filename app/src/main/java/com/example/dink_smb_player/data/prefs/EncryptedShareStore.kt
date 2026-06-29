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

    // A wedged Tink keyset / secrets file makes create() throw forever, which would
    // silently block every credential write (and thus break adding a share). Recover
    // by wiping the file once and rebuilding rather than staying permanently broken.
    private val prefs: SharedPreferences = runCatching { build(context) }
        .getOrElse {
            android.util.Log.e("EncryptedShareStore", "secrets store corrupt, resetting", it)
            context.deleteSharedPreferences("dink_secrets")
            build(context)
        }

    private fun build(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
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
        // getString itself can throw if this entry's ciphertext is corrupt — guard
        // the whole read so one bad value doesn't bubble up as a crash.
        val raw = runCatching { prefs.getString(smbKey(shareId), null) }.getOrNull() ?: return null
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

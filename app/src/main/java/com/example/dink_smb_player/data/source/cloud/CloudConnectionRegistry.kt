package com.example.dink_smb_player.data.source.cloud

import com.example.dink_smb_player.data.model.CloudProvider
import com.example.dink_smb_player.data.prefs.CloudToken
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry mapping `?pid=<providerId>` on `gdrive://` URIs back to the
 * persisted [CloudProvider] config + its OAuth [CloudToken]. Mirror of
 * [com.example.dink_smb_player.data.source.smb.SmbConnectionRegistry].
 *
 * Wired at app start in [com.example.dink_smb_player.DinkApp]: provider list mirrored
 * from [com.example.dink_smb_player.data.prefs.SharePrefs.providers]; the token store
 * closures bridge to [com.example.dink_smb_player.data.prefs.EncryptedShareStore].
 *
 * [CloudDataSource] calls [validAccessToken] on every `open()` so a token that
 * expired mid-session is refreshed transparently before the stream starts.
 *
 * NB: nothing here downloads audio. We only hold credentials so the data source can
 * STREAM bytes on demand via HTTP Range — files never land on device storage.
 */
object CloudConnectionRegistry {

    private val byId = ConcurrentHashMap<String, CloudProvider>()

    @Volatile private var tokenGet: ((String) -> CloudToken?)? = null
    @Volatile private var tokenPut: ((String, CloudToken) -> Unit)? = null

    fun update(providers: List<CloudProvider>) {
        byId.clear()
        providers.forEach { byId[it.id] = it }
    }

    fun add(provider: CloudProvider) { byId[provider.id] = provider }

    fun installTokenStore(get: (String) -> CloudToken?, put: (String, CloudToken) -> Unit) {
        tokenGet = get
        tokenPut = put
    }

    fun provider(id: String): CloudProvider? = byId[id]
    fun token(id: String): CloudToken? = tokenGet?.invoke(id)

    /**
     * Return a usable bearer token for [providerId], refreshing + persisting it
     * first if it's within 60s of expiry. Blocks on the refresh HTTP call — call
     * from a background thread (the data source's `open()` already runs off-main).
     * Returns null only if the provider was never connected.
     */
    fun validAccessToken(providerId: String): String? {
        val tok = token(providerId) ?: return null
        val now = System.currentTimeMillis()
        val skewMs = 60_000L
        if (tok.expiresAtMs == null || tok.expiresAtMs > now + skewMs) return tok.accessToken
        val refreshToken = tok.refreshToken ?: return tok.accessToken // no refresh path — try the stale one
        // Drive-only for v1; dispatch on provider type when more providers land.
        return GoogleDriveClient.refresh(refreshToken).map { r ->
            val updated = tok.copy(
                accessToken = r.accessToken,
                expiresAtMs = now + r.expiresInSec * 1000L,
            )
            tokenPut?.invoke(providerId, updated)
            r.accessToken
        }.getOrDefault(tok.accessToken)
    }
}

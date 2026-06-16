package com.example.dink_smb_player.data.source.smb

import com.example.dink_smb_player.data.model.SmbShare
import com.example.dink_smb_player.data.prefs.SmbCreds
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry mapping `?sid=<shareId>` query params on smb:// URIs back to
 * the persisted [SmbShare] config + [SmbCreds] needed to open a connection.
 *
 * Plumbed at app start: [com.example.dink_smb_player.DinkApp] collects
 * [com.example.dink_smb_player.data.prefs.SharePrefs].shares and pushes them to
 * [update]; [credLookup] is installed once with a closure over the
 * [com.example.dink_smb_player.data.prefs.EncryptedShareStore].
 *
 * [SmbDataSource] reads from this registry on every `open()` so newly-added
 * shares are immediately playable.
 */
object SmbConnectionRegistry {
    private val sharesById = ConcurrentHashMap<String, SmbShare>()

    @Volatile
    private var credLookup: ((String) -> SmbCreds?)? = null

    fun update(shares: List<SmbShare>) {
        sharesById.clear()
        shares.forEach { sharesById[it.id] = it }
    }

    /** Push a single new share without dropping the rest. Used by the wizard so
     *  playback can resolve `?sid=` before the DataStore Flow re-emits. */
    fun add(share: SmbShare) {
        sharesById[share.id] = share
    }

    fun installCredLookup(lookup: (String) -> SmbCreds?) {
        credLookup = lookup
    }

    fun share(id: String): SmbShare? = sharesById[id]
    fun creds(id: String): SmbCreds? = credLookup?.invoke(id)
}

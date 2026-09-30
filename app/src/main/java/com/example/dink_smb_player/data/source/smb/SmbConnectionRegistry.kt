package com.example.dink_smb_player.data.source.smb

import android.content.Context
import com.example.dink_smb_player.data.model.SmbShare
import com.example.dink_smb_player.data.prefs.EncryptedShareStore
import com.example.dink_smb_player.data.prefs.SharePrefs
import com.example.dink_smb_player.data.prefs.SmbCreds
import com.example.dink_smb_player.data.source.SourceLocks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

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
 *
 * Cold processes (a WorkManager monitor pass, a media-button start of PlayerService) never
 * run DinkApp's wiring: they call [hydrate] first, or every smb:// open — tag reads, playback
 * — fails with "Unknown SMB share id".
 *
 * A share tombstoned by [SourceLocks.remove] (being deleted) is never (re-)registered.
 */
object SmbConnectionRegistry {
    // Immutable snapshot swapped by reference. update() used to clear() then refill a
    // shared map, so an open() racing it saw an empty registry ("Unknown SMB share id").
    @Volatile
    private var sharesById: Map<String, SmbShare> = emptyMap()
    private val writeLock = Any()

    @Volatile
    private var credLookup: ((String) -> SmbCreds?)? = null

    fun update(shares: List<SmbShare>) {
        val next = shares.filterNot { SourceLocks.isRemoved(it.id) }.associateBy { it.id }
        synchronized(writeLock) { sharesById = next }
    }

    /** Push a single new share without dropping the rest. Used by the wizard so
     *  playback can resolve `?sid=` before the DataStore Flow re-emits. */
    fun add(share: SmbShare) {
        if (SourceLocks.isRemoved(share.id)) return
        synchronized(writeLock) { sharesById = sharesById + (share.id to share) }
    }

    /** Drop a share being deleted, so nothing resolves `?sid=` to it any more. */
    fun remove(shareId: String) {
        synchronized(writeLock) { sharesById = sharesById - shareId }
    }

    /**
     * Load every saved share and the encrypted-cred lookup from disk. For cold entry points
     * where DinkApp's boot wiring never ran (MonitorWorker, PlayerService's media-button
     * resumption). MERGES into what is registered — a warm process's newer entries (a wizard
     * [add] the DataStore hasn't re-emitted yet) survive — and is idempotent.
     *
     * A failed prefs read leaves the registry as it was, but cancellation always propagates:
     * a caller being torn down (service onDestroy, worker stopped) must not carry on into the
     * blocking Keystore open.
     */
    suspend fun hydrate(context: Context) {
        val ctx = context.applicationContext
        withContext(Dispatchers.IO) {
            hydrateFrom(
                loadShares = { SharePrefs(ctx).shares.first() },
                openCreds = {
                    val store = EncryptedShareStore.get(ctx)
                    val lookup: (String) -> SmbCreds? = { sid -> runCatching { store.getSmbCreds(sid) }.getOrNull() }
                    lookup
                },
            )
        }
    }

    /** [hydrate] with the disk reads injected — see there. Visible for tests. */
    internal suspend fun hydrateFrom(
        loadShares: suspend () -> List<SmbShare>,
        openCreds: () -> ((String) -> SmbCreds?),
    ) {
        val shares = try {
            loadShares()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            android.util.Log.w("SmbConnectionRegistry", "hydrate: couldn't read saved shares", t)
            emptyList()
        }
        currentCoroutineContext().ensureActive()
        val fresh = shares.filterNot { SourceLocks.isRemoved(it.id) }
        if (fresh.isNotEmpty()) {
            synchronized(writeLock) { sharesById = sharesById + fresh.associateBy { it.id } }
        }
        val lookup = try {
            openCreds()
        } catch (t: Throwable) {
            android.util.Log.w("SmbConnectionRegistry", "hydrate: couldn't open the cred store", t)
            null
        }
        if (lookup != null) installCredLookup(lookup)
    }

    fun installCredLookup(lookup: (String) -> SmbCreds?) {
        credLookup = lookup
    }

    fun share(id: String): SmbShare? = sharesById[id]
    fun creds(id: String): SmbCreds? = credLookup?.invoke(id)
}

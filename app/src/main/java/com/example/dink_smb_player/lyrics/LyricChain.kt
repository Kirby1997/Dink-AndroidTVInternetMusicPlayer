package com.example.dink_smb_player.lyrics

import android.content.Context
import android.util.Log
import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** What the chain reads from. Injected so tests can drive it with fakes. */
internal interface LyricSources {
    /** The track's directory for sidecar lookup, or null when it has none. */
    fun sidecarDir(song: Song): SidecarDir?
    /** Embedded lyrics (USLT / SYLT / ©lyr / LYRICS). Throws on a transient failure. */
    suspend fun embedded(song: Song): LyricResult
    /** Online providers the user allows, in priority order. Empty when the master
     *  "Online lyrics" switch is off — then nothing touches the network. */
    fun providers(): List<OnlineLyricProvider>
}

/**
 * foo_openlyrics-style provider chain. Resolution order prefers *synced* lyrics
 * (real `[mm:ss.xx]` timestamps) over plain text, regardless of source:
 *
 *   1. Sidecar `.lrc`          — user-curated, synced (local or SMB, LYR-1)
 *   2. Online synced tier      — LRCLIB, NetEase, QQ, Lyricsify, Letras queried IN PARALLEL;
 *                                the best-priority synced hit wins after a short grace
 *                                (LYR-7). LRCLIB `instrumental` counts as an answer (LYR-10).
 *   3. Embedded tags           — SYLT / USLT / ©lyr / LYRICS, read on demand (started
 *                                alongside tier 2, cancelled if tier 2 wins)
 *   4. Sidecar `.txt`, then an `.lrc` without stamps — user-curated, plain
 *   5. Instrumental verdict from tier 2
 *   6. Plain text from tier 2, else the plain-only scrapers one at a time until one hits
 *
 * Online sources only run when the master "Online lyrics" switch is on (default OFF —
 * no network lookups at all otherwise); each is then independently toggleable in
 * Settings ([LyricSettings] / [LyricPrefs]).
 *
 * [resolve] suspends and is cancellable (LYR-8): a track skip cancels the in-flight
 * OkHttp calls. Each request is capped by [LyricHttp.CALL_TIMEOUT_SEC] and the online
 * part by [DEADLINE_MS] overall (LYR-6). Results are cached ([LyricCache], LYR-9).
 */
object LyricChain {

    private const val TAG = "LyricChain"

    /** After the first synced hit, how long higher-priority providers still in flight get
     *  to answer before the hit is taken. */
    const val GRACE_MS = 300L

    /** Overall budget for the online lookups of one resolve. */
    const val DEADLINE_MS = 15_000L

    @Volatile
    private var cache: LyricCache? = null

    private fun cacheFor(context: Context): LyricCache =
        cache ?: synchronized(this) {
            cache ?: LyricCache(File(context.applicationContext.cacheDir, "lyrics")).also { c ->
                cache = c
                // Provider toggles changed what a lookup may consult: drop everything.
                // (Entries also carry their config fingerprint, so this is belt-and-braces
                // plus space reclamation — off the main thread the toggle runs on.)
                LyricSettings.onUserChange = { thread(name = "lyric-cache-clear") { c.clear() } }
            }
        }

    /** Resolve lyrics for [song]: cache first, then the chain. Cancellable. */
    suspend fun resolve(context: Context, song: Song): LyricResult {
        Log.i(TAG, "resolve start: title='${song.title}' artist='${song.artist}' dur=${song.durationSec}s path='${song.sourcePath}'")
        val c = cacheFor(context)
        val key = LyricCache.keyFor(song)
        val fp = LyricSettings.fingerprint()
        withContext(Dispatchers.IO) { c.get(key, fp) }?.let {
            Log.i(TAG, "cache hit: ${it.javaClass.simpleName}")
            return it
        }
        // Off the caller's (main) dispatcher: providers parse JSON / HTML where their HTTP
        // call resumes. Still cancellable — withContext propagates the caller's cancel.
        val res = withContext(Dispatchers.IO) { resolveUncached(song, DefaultSources(context.applicationContext)) }
        if (res.cacheable) withContext(Dispatchers.IO) { c.put(key, fp, res.result, persist = res.persist) }
        Log.i(TAG, "resolved ${res.result.javaClass.simpleName} cacheable=${res.cacheable}")
        return res.result
    }

    private class DefaultSources(private val context: Context) : LyricSources {
        override fun sidecarDir(song: Song) = SidecarLyrics.dirFor(song)
        override suspend fun embedded(song: Song) = EmbeddedLyrics.load(context, song)
        override fun providers() = LyricSettings.activeProviders()
    }

    /**
     * [cacheable] = false when something failed transiently (NAS unreachable, a provider
     * timed out) and the answer is a miss — remembering it would hide lyrics that exist.
     * [persist] = false keeps a result in memory only: a miss found without asking any
     * online provider is cheap to recompute next session and would otherwise hide a
     * sidecar the user adds later for a week.
     */
    internal data class Resolution(val result: LyricResult, val cacheable: Boolean, val persist: Boolean = true)

    internal suspend fun resolveUncached(
        song: Song,
        sources: LyricSources,
        graceMs: Long = GRACE_MS,
        deadlineMs: Long = DEADLINE_MS,
        clock: () -> Long = System::currentTimeMillis,
    ): Resolution = coroutineScope {
        // Set from the embedded-read child too, which may run on another IO thread.
        val transient = AtomicBoolean(false)
        fun noteFailure(what: String, t: Throwable) {
            if (t is CancellationException) throw t
            transient.set(true)
            Log.w(TAG, "$what failed: ${t.javaClass.simpleName}: ${t.message}")
        }

        // 1. Sidecars — ONE directory listing, then only the matched file is read.
        val dir = sources.sidecarDir(song)
        var names = SidecarNames(null, null)
        if (dir != null) {
            try {
                names = SidecarLyrics.locate(dir.list(), dir.audioName, song.title, song.artist)
            } catch (t: Throwable) {
                noteFailure("sidecar list", t)
            }
        }
        var lrcFlat: List<LyricLine> = emptyList()
        val lrcName = names.lrc
        if (dir != null && lrcName != null) {
            val lines = try {
                dir.read(lrcName)?.let { LrcParser.parse(LrcParser.decode(it)) }.orEmpty()
            } catch (t: Throwable) {
                noteFailure("sidecar .lrc read", t); emptyList()
            }
            if (lines.any { it.timeSec > 0f }) {
                Log.i(TAG, "sidecar .lrc hit '$lrcName' (${lines.size} lines)")
                return@coroutineScope Resolution(LyricResult.Synced(lines), cacheable = true)
            }
            lrcFlat = lines
        }
        ensureActive()

        // 2 + 3. Online synced tier in parallel; the embedded read runs alongside it.
        val providers = sources.providers()
        val onlineStart = clock()
        val embedded = async {
            try {
                sources.embedded(song)
            } catch (t: Throwable) {
                noteFailure("embedded read", t); LyricResult.None
            }
        }
        val syncedTier = providers.filter { it.syncedCapable }
        val tier = if (syncedTier.isEmpty()) TierResult() else querySyncedTier(song, syncedTier, graceMs, deadlineMs)
        if (tier.transient) transient.set(true)
        tier.winner?.takeIf { it.synced.isNotEmpty() }?.let {
            embedded.cancel()
            Log.i(TAG, "online synced hit from ${tier.winnerId} (${it.synced.size} lines)")
            return@coroutineScope Resolution(LyricResult.Synced(it.synced), cacheable = true)
        }
        val emb = embedded.await()
        if (emb !is LyricResult.None) {
            Log.i(TAG, "embedded hit: ${emb.javaClass.simpleName}")
            return@coroutineScope Resolution(emb, cacheable = true)
        }
        ensureActive()

        // 4. User-curated plain text next to the track.
        val txtName = names.txt
        if (dir != null && txtName != null) {
            val lines = try {
                dir.read(txtName)?.let { plainToLines(LrcParser.decode(it)) }.orEmpty()
            } catch (t: Throwable) {
                noteFailure("sidecar .txt read", t); emptyList()
            }
            if (lines.isNotEmpty()) return@coroutineScope Resolution(LyricResult.Plain(lines), cacheable = true)
        }
        if (lrcFlat.isNotEmpty()) return@coroutineScope Resolution(LyricResult.Plain(lrcFlat), cacheable = true)

        // 5. A provider says there are no lyrics — don't go scraping plain sites for them.
        if (tier.winner?.instrumental == true) {
            Log.i(TAG, "instrumental per ${tier.winnerId}")
            return@coroutineScope Resolution(LyricResult.Instrumental, cacheable = true)
        }

        // 6. Online plain: the synced tier's plain first; plain-only scrapers only when
        //    nothing plain exists yet, one at a time, stopping at the first hit (LYR-7).
        if (tier.plain.isNotEmpty()) return@coroutineScope Resolution(LyricResult.Plain(tier.plain), cacheable = true)
        for (p in providers.filterNot { it.syncedCapable }) {
            val remaining = deadlineMs - (clock() - onlineStart)
            if (remaining <= 0) { transient.set(true); break }
            val r = try {
                withTimeoutOrNull(remaining) { p.fetch(song) } ?: run { transient.set(true); null }
            } catch (t: Throwable) {
                noteFailure(p.id, t); null
            }
            Log.i(TAG, "${p.id}('${song.title}'): synced=${r?.synced?.size ?: 0} plain=${r?.plain?.size ?: 0}")
            if (r == null) continue
            if (r.synced.isNotEmpty()) return@coroutineScope Resolution(LyricResult.Synced(r.synced), cacheable = true)
            if (r.plain.isNotEmpty()) return@coroutineScope Resolution(LyricResult.Plain(r.plain), cacheable = true)
        }

        if (providers.isEmpty()) Log.i(TAG, "online lyrics off (master=${LyricSettings.config().online}) — skipped network")
        Log.i(TAG, "no lyrics resolved (transient=${transient.get()})")
        Resolution(LyricResult.None, cacheable = !transient.get(), persist = providers.isNotEmpty())
    }

    /** Outcome of the parallel synced tier. [winner] = best-priority answer (synced lyrics
     *  or an instrumental verdict); [plain] = best-priority plain text seen. */
    internal data class TierResult(
        val winner: OnlineLyrics? = null,
        val winnerId: String? = null,
        val plain: List<LyricLine> = emptyList(),
        val transient: Boolean = false,
    )

    private sealed interface TierMsg {
        class Done(val index: Int, val result: OnlineLyrics?) : TierMsg
        data object GraceOver : TierMsg
        data object Deadline : TierMsg
    }

    private fun OnlineLyrics?.isAnswer() = this != null && (synced.isNotEmpty() || instrumental)

    /**
     * Query [providers] (priority order) concurrently. The best-priority answer wins as soon
     * as every higher-priority provider has finished, or [graceMs] after the first answer
     * arrived, or at [deadlineMs] — whichever is first. Everything still in flight is then
     * cancelled (cancelling its HTTP call).
     */
    internal suspend fun querySyncedTier(
        song: Song,
        providers: List<OnlineLyricProvider>,
        graceMs: Long,
        deadlineMs: Long,
    ): TierResult = coroutineScope {
        val n = providers.size
        val results = arrayOfNulls<OnlineLyrics>(n)
        val finished = BooleanArray(n)
        var failed = false
        val ch = Channel<TierMsg>(Channel.UNLIMITED)
        providers.forEachIndexed { i, p ->
            launch {
                val r = try {
                    p.fetch(song)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w(TAG, "${p.id} threw: ${t.javaClass.simpleName}: ${t.message}")
                    null
                }
                Log.i(TAG, "${p.id}('${song.title}'): synced=${r?.synced?.size ?: 0} plain=${r?.plain?.size ?: 0} instrumental=${r?.instrumental ?: false}")
                ch.send(TierMsg.Done(i, r))
            }
        }
        launch { delay(deadlineMs); ch.send(TierMsg.Deadline) }
        var done = 0
        var graceStarted = false
        var graceOver = false
        var deadline = false
        var best = -1
        while (true) {
            best = (0 until n).firstOrNull { results[it].isAnswer() } ?: -1
            if (best >= 0 && (graceOver || deadline || (0 until best).all { finished[it] })) break
            if (done == n || deadline) break
            when (val m = ch.receive()) {
                is TierMsg.Done -> {
                    finished[m.index] = true
                    results[m.index] = m.result
                    if (m.result == null) failed = true
                    done++
                    if (!graceStarted && m.result.isAnswer()) {
                        graceStarted = true
                        launch { delay(graceMs); ch.send(TierMsg.GraceOver) }
                    }
                }
                TierMsg.GraceOver -> graceOver = true
                TierMsg.Deadline -> deadline = true
            }
        }
        coroutineContext.cancelChildren()
        val plain = results.firstOrNull { it != null && it.plain.isNotEmpty() }?.plain.orEmpty()
        TierResult(
            winner = if (best >= 0) results[best] else null,
            winnerId = if (best >= 0) providers[best].id else null,
            plain = plain,
            // A miss is only conclusive if every provider answered.
            transient = failed || (0 until n).any { !finished[it] },
        )
    }
}

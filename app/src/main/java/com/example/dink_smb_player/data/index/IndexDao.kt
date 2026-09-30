package com.example.dink_smb_player.data.index

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import com.example.dink_smb_player.data.source.SourceLocks

/**
 * In-memory implementation of the index data access surface. Mirrors what a Room DAO
 * would expose so screens and ViewModels can be wired against this stable API; when
 * Room codegen works with AGP 9, drop in an @Dao-annotated interface with the same
 * method signatures and the rest of the app keeps compiling.
 *
 * Persistence note: tracks survive process death only via [SourceSink] → SharePrefs
 * snapshots written at sync boundaries. Live in-memory state is the source of truth
 * during a session.
 */
class IndexDao internal constructor(
    private val tracksState: MutableStateFlow<List<TrackEntity>>,
    private val sourcesState: MutableStateFlow<List<SourceEntity>>,
    // LIB-15: live play stats by track id, kept OUT of [tracksState] so a play doesn't replace
    // the 25k-row list (which re-maps songs(), and misses GroupMemo / Search / Songs caches).
    // In memory the rows' playCount/lastPlayedMs are 0/null (stripped at [restoreTracks]); the
    // on-disk snapshot carries them again via [persistSnapshot].
    private val playStatsState: MutableStateFlow<Map<String, PlayStat>> = MutableStateFlow(emptyMap()),
    // LIB-5 backstop: ids of sources deleted this process (SourceLocks tombstones). Writes for
    // them are dropped INSIDE each atomic update, so a writer that snapshotted a source before
    // it was removed (a retag chunk, a walk that outlived its cancel) can't bring its rows or
    // source row back. Injected for tests.
    private val isRemoved: (String) -> Boolean = { SourceLocks.isRemoved(it) },
) {

    // ---------- Tracks ----------

    fun observeAllTracks(): Flow<List<TrackEntity>> = tracksState.map { it.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { t -> t.title }) }

    fun observeTracksFor(type: SourceType, id: String): Flow<List<TrackEntity>> =
        tracksState.map { tracks ->
            tracks.filter { it.sourceType == type && it.sourceId == id }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        }

    fun observeAlbumTracks(albumId: String): Flow<List<TrackEntity>> =
        tracksState.map { tracks ->
            tracks.filter { it.albumId == albumId }
                .sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }, { it.title.lowercase() }))
        }

    /** Live play stats by track id (see [playStatsState]). Stats of ids no longer indexed are
     *  harmless: every reader joins against the current rows. */
    val playStats: StateFlow<Map<String, PlayStat>> = playStatsState.asStateFlow()

    fun observeRecentlyPlayed(limit: Int = 20): Flow<List<TrackEntity>> =
        combine(tracksState, playStatsState) { tracks, stats -> recentlyPlayed(tracks, stats, limit) }

    fun observeRecentlyAdded(limit: Int = 30): Flow<List<TrackEntity>> =
        tracksState.map { tracks ->
            tracks.sortedByDescending { it.addedAtMs }.take(limit)
        }

    suspend fun trackById(id: String): TrackEntity? =
        tracksState.value.firstOrNull { it.id == id }

    /** Current in-memory contents. Rows carry no play stats here — see [persistSnapshot]. */
    fun snapshot(): Pair<List<TrackEntity>, List<SourceEntity>> =
        tracksState.value to sourcesState.value

    /** [snapshot] with each row's playCount/lastPlayedMs filled from [playStats] — what goes to
     *  disk (see LibraryStore), so the file keeps the per-track fields older builds read. Only
     *  played rows are copied. */
    fun persistSnapshot(): Pair<List<TrackEntity>, List<SourceEntity>> {
        val stats = playStatsState.value
        val tracks = tracksState.value
        val out = if (stats.isEmpty()) tracks else tracks.map { t ->
            val s = stats[t.id] ?: return@map t
            t.copy(playCount = s.count, lastPlayedMs = s.lastPlayedMs)
        }
        return out to sourcesState.value
    }

    /**
     * Load rows read from disk at boot: their play stats seed [playStats] and the rows go into
     * the index stripped of them ([TrackMerges.Restore]: fills missing rows, keeps rows a writer
     * already put there). Stats recorded this process before the restore (a local track played
     * while the file was still loading) started from 0, so they are added to the stored count,
     * never replaced by it. Returns the upsert's change count.
     */
    suspend fun restoreTracks(tracks: List<TrackEntity>): Int {
        val seeded = HashMap<String, PlayStat>()
        for (t in tracks) {
            if (t.playCount != 0 || t.lastPlayedMs != null) seeded[t.id] = PlayStat(t.playCount, t.lastPlayedMs)
        }
        if (seeded.isNotEmpty()) {
            playStatsState.update { live ->
                val next = HashMap(live)
                for ((id, disk) in seeded) {
                    val now = live[id]
                    next[id] = if (now == null) disk else PlayStat(
                        count = disk.count + now.count,
                        lastPlayedMs = maxOfNullable(disk.lastPlayedMs, now.lastPlayedMs),
                    )
                }
                next
            }
        }
        val stripped = tracks.map { if (it.playCount != 0 || it.lastPlayedMs != null) it.copy(playCount = 0, lastPlayedMs = null) else it }
        return upsertTracks(stripped, TrackMerges.Restore)
    }

    /**
     * Upsert [tracks], each combined with the row CURRENTLY in the index by [merge] inside the
     * atomic update (see [TrackMerge]) — never a blind replace with a writer's stale copy.
     * Rows the merge leaves equal to the current one aren't rewritten; when nothing changes the
     * list isn't replaced at all, so observers don't re-emit. Rows of a removed source are
     * dropped. Returns how many rows were inserted or changed.
     */
    suspend fun upsertTracks(tracks: List<TrackEntity>, merge: TrackMerge): Int {
        if (tracks.isEmpty()) return 0
        var changed = 0
        tracksState.update { current ->
            val (next, n) = applyUpserts(current, tracks, merge)
            changed = n
            next
        }
        return changed
    }

    private fun applyUpserts(
        current: List<TrackEntity>,
        tracks: List<TrackEntity>,
        merge: TrackMerge,
    ): Pair<List<TrackEntity>, Int> {
        val index = HashMap<String, Int>(current.size * 2)
        current.forEachIndexed { i, t -> index[t.id] = i }
        var out: ArrayList<TrackEntity>? = null
        var changed = 0
        for (incoming in tracks) {
            if (isRemoved(incoming.sourceId)) continue
            val list = out ?: current
            val pos = index[incoming.id]
            val existing = pos?.let { list[it] }
            val merged = merge.merge(existing, incoming) ?: continue
            if (merged == existing) continue
            val o = out ?: ArrayList(current).also { out = it }
            if (pos != null) {
                o[pos] = merged
            } else {
                index[merged.id] = o.size
                o.add(merged)
            }
            changed++
        }
        return (out ?: current) to changed
    }

    /** Remove [id]'s rows whose uri isn't in [keepUris]. Returns how many were removed; the list
     *  isn't replaced when none are. */
    suspend fun pruneSource(type: SourceType, id: String, keepUris: List<String>): Int {
        val keepSet = keepUris.toHashSet()
        var removed = 0
        tracksState.update { current ->
            // A removed source has no rows left to prune (and a stale caller shouldn't act on it).
            val next = if (isRemoved(id)) current
            else current.filterNot { it.sourceType == type && it.sourceId == id && it.uri !in keepSet }
            removed = current.size - next.size
            if (removed == 0) current else next
        }
        return removed
    }

    suspend fun deleteSourceTracks(type: SourceType, id: String) {
        tracksState.update { current ->
            current.filterNot { it.sourceType == type && it.sourceId == id }
        }
    }

    /** Record one play of [id] at [ts]: a single-entry patch of [playStats]; the track list is
     *  untouched (LIB-15). Returns false (nothing recorded) when [id] isn't indexed. */
    suspend fun markPlayed(id: String, ts: Long): Boolean {
        val row = tracksState.value.firstOrNull { it.id == id } ?: return false
        playStatsState.update { current ->
            // A row that never went through [restoreTracks] may still carry its own counts.
            val prevCount = current[id]?.count ?: row.playCount
            current + (id to PlayStat(count = prevCount + 1, lastPlayedMs = ts))
        }
        return true
    }

    /** False once [sourceId] was removed this process: its writes are dropped. */
    fun acceptsWrites(sourceId: String): Boolean = !isRemoved(sourceId)

    // ---------- Sources ----------

    fun observeSources(): Flow<List<SourceEntity>> =
        sourcesState.map { it.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { s -> s.displayName }) }

    fun observeSourcesOfType(type: SourceType): Flow<List<SourceEntity>> =
        sourcesState.map { srcs ->
            srcs.filter { it.type == type }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
        }

    /** Upsert [source] unless it was removed this process (see [isRemoved]). */
    suspend fun upsertSource(source: SourceEntity) {
        sourcesState.update { current ->
            if (isRemoved(source.id)) current
            else current.filterNot { it.id == source.id } + source
        }
    }

    suspend fun deleteSource(id: String) {
        sourcesState.update { current -> current.filterNot { it.id == id } }
    }

    suspend fun updateSourceStats(id: String, ts: Long, count: Int, size: Long, statusJson: String?) {
        sourcesState.update { current ->
            if (isRemoved(id)) return@update current
            current.map { s ->
                if (s.id == id) s.copy(lastSyncMs = ts, trackCount = count, sizeBytes = size, statusJson = statusJson)
                else s
            }
        }
    }
}

/** Up to [limit] indexed rows by most recent play, newest first. Only rows present in [tracks]
 *  count, so a removed source's history doesn't surface. One pass over the rows. */
internal fun recentlyPlayed(tracks: List<TrackEntity>, stats: Map<String, PlayStat>, limit: Int): List<TrackEntity> {
    if (stats.isEmpty() || limit <= 0) return emptyList()
    val played = HashMap<String, TrackEntity>()
    for (t in tracks) if (stats[t.id]?.lastPlayedMs != null) played[t.id] = t
    return played.values
        .sortedByDescending { stats.getValue(it.id).lastPlayedMs }
        .take(limit)
}

private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}

private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    while (true) {
        val prev = value
        val next = transform(prev)
        if (compareAndSet(prev, next)) return
    }
}

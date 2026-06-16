package com.example.dink_smb_player.data.index

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

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
) {

    // ---------- Tracks ----------

    fun observeAllTracks(): Flow<List<TrackEntity>> = tracksState.map { it.sortedBy { t -> t.title.lowercase() } }

    fun observeTracksFor(type: SourceType, id: String): Flow<List<TrackEntity>> =
        tracksState.map { tracks ->
            tracks.filter { it.sourceType == type && it.sourceId == id }
                .sortedBy { it.title.lowercase() }
        }

    fun observeAlbumTracks(albumId: String): Flow<List<TrackEntity>> =
        tracksState.map { tracks ->
            tracks.filter { it.albumId == albumId }
                .sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }, { it.title.lowercase() }))
        }

    fun observeRecentlyPlayed(limit: Int = 20): Flow<List<TrackEntity>> =
        tracksState.map { tracks ->
            tracks.filter { it.lastPlayedMs != null }
                .sortedByDescending { it.lastPlayedMs }
                .take(limit)
        }

    fun observeRecentlyAdded(limit: Int = 30): Flow<List<TrackEntity>> =
        tracksState.map { tracks ->
            tracks.sortedByDescending { it.addedAtMs }.take(limit)
        }

    suspend fun trackById(id: String): TrackEntity? =
        tracksState.value.firstOrNull { it.id == id }

    /** Current in-memory contents, for snapshotting to disk (see LibraryStore). */
    fun snapshot(): Pair<List<TrackEntity>, List<SourceEntity>> =
        tracksState.value to sourcesState.value

    suspend fun upsertTracks(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        val byId = tracks.associateBy { it.id }
        tracksState.update { current ->
            val keep = current.filterNot { it.id in byId }
            keep + tracks
        }
    }

    suspend fun pruneSource(type: SourceType, id: String, keepUris: List<String>) {
        val keepSet = keepUris.toHashSet()
        tracksState.update { current ->
            current.filterNot { it.sourceType == type && it.sourceId == id && it.uri !in keepSet }
        }
    }

    suspend fun deleteSourceTracks(type: SourceType, id: String) {
        tracksState.update { current ->
            current.filterNot { it.sourceType == type && it.sourceId == id }
        }
    }

    suspend fun markPlayed(id: String, ts: Long) {
        tracksState.update { current ->
            current.map { t ->
                if (t.id == id) t.copy(lastPlayedMs = ts, playCount = t.playCount + 1) else t
            }
        }
    }

    // ---------- Sources ----------

    fun observeSources(): Flow<List<SourceEntity>> =
        sourcesState.map { it.sortedBy { s -> s.displayName.lowercase() } }

    fun observeSourcesOfType(type: SourceType): Flow<List<SourceEntity>> =
        sourcesState.map { srcs ->
            srcs.filter { it.type == type }.sortedBy { it.displayName.lowercase() }
        }

    suspend fun upsertSource(source: SourceEntity) {
        sourcesState.update { current ->
            current.filterNot { it.id == source.id } + source
        }
    }

    suspend fun deleteSource(id: String) {
        sourcesState.update { current -> current.filterNot { it.id == id } }
    }

    suspend fun updateSourceStats(id: String, ts: Long, count: Int, size: Long, statusJson: String?) {
        sourcesState.update { current ->
            current.map { s ->
                if (s.id == id) s.copy(lastSyncMs = ts, trackCount = count, sizeBytes = size, statusJson = statusJson)
                else s
            }
        }
    }
}

private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    while (true) {
        val prev = value
        val next = transform(prev)
        if (compareAndSet(prev, next)) return
    }
}

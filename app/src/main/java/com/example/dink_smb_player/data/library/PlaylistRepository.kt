package com.example.dink_smb_player.data.library

import android.content.Context
import com.example.dink_smb_player.data.model.Playlist
import com.example.dink_smb_player.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Single source of truth for user playlists. In-memory [StateFlow] backed by
 * [PlaylistStore] on disk; every mutation persists. Mirrors [LibraryRepository]'s
 * shape (process-level restore guard + [PersistGate]) but is simpler — playlists are
 * small and never pruned automatically.
 *
 * Invariants (playlists are the user's own, unrecoverable data):
 * - Every mutator restores first. Mutating before the restore and then persisting would
 *   overwrite playlists.json with only the new playlist.
 * - A corrupt load (main and backup both unreadable) disables persistence for the
 *   process: mutations still apply in memory but report "not saved", and nothing is
 *   written over the preserved file. A transient load failure refuses mutations until a
 *   retry succeeds.
 * - Mutators return whether the change is durably on disk, so the UI confirms only then.
 */
object PlaylistRepository {

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    @Volatile private var restored = false
    private val restoreMutex = Mutex()
    private val gate = PersistGate()

    /** Returns true once the on-disk playlists are loaded (or known absent/corrupt).
     *  False = a transient load failure; the next call retries. */
    suspend fun ensureRestored(context: Context): Boolean {
        if (restored) return true
        return restoreMutex.withLock {
            if (restored) return@withLock true
            when (val r = PlaylistStore.load(context)) {
                is PlaylistStore.LoadResult.Ok -> { _playlists.value = r.playlists; gate.onLoaded() }
                PlaylistStore.LoadResult.Missing -> gate.onLoaded()
                is PlaylistStore.LoadResult.Corrupt -> {
                    gate.onCorrupt()
                    android.util.Log.e("PlaylistRepository", "playlists unreadable — saving disabled this session", r.error)
                }
                is PlaylistStore.LoadResult.Transient -> {
                    gate.onTransientFailure()
                    android.util.Log.w("PlaylistRepository", "playlists load failed transiently — will retry", r.error)
                    return@withLock false
                }
            }
            restored = true
            true
        }
    }

    /** Test hook: back to a fresh-process state. */
    internal fun resetForTest() {
        restored = false
        _playlists.value = emptyList()
        gate.onLoaded()
    }

    private suspend fun persist(context: Context): Boolean {
        if (!gate.canPersist) {
            android.util.Log.w("PlaylistRepository", "persist skipped (${gate.state}) — not overwriting on-disk playlists")
            return false
        }
        return PlaylistStore.save(context, _playlists.value)
    }

    /** Create a playlist, optionally seeding it with one track. Returns the new id once
     *  it's saved, or null if it couldn't be (see class invariants). */
    suspend fun create(context: Context, name: String, seedSongId: String? = null): String? {
        if (!ensureRestored(context)) return null
        val now = System.currentTimeMillis()
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Untitled playlist" },
            songIds = listOfNotNull(seedSongId),
            createdMs = now,
            updatedMs = now,
        )
        _playlists.update { it + playlist }
        return if (persist(context)) playlist.id else null
    }

    suspend fun rename(context: Context, id: String, name: String): Boolean {
        if (!ensureRestored(context)) return false
        _playlists.update { list ->
            list.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }, updatedMs = System.currentTimeMillis()) else it }
        }
        return persist(context)
    }

    suspend fun delete(context: Context, id: String): Boolean {
        if (!ensureRestored(context)) return false
        _playlists.update { list -> list.filterNot { it.id == id } }
        return persist(context)
    }

    /** Append [songId] unless already present (no duplicates). */
    suspend fun addSong(context: Context, playlistId: String, songId: String): Boolean {
        if (!ensureRestored(context)) return false
        _playlists.update { list ->
            list.map { p ->
                if (p.id == playlistId && songId !in p.songIds)
                    p.copy(songIds = p.songIds + songId, updatedMs = System.currentTimeMillis())
                else p
            }
        }
        return persist(context)
    }

    suspend fun removeSong(context: Context, playlistId: String, songId: String): Boolean {
        if (!ensureRestored(context)) return false
        _playlists.update { list ->
            list.map { p ->
                if (p.id == playlistId)
                    p.copy(songIds = p.songIds - songId, updatedMs = System.currentTimeMillis())
                else p
            }
        }
        return persist(context)
    }

    /** Resolve a playlist's ids to playable [Song]s in playlist order, skipping ids
     *  that don't currently resolve (e.g. an offline source). */
    fun songsOf(playlist: Playlist, library: List<Song>): List<Song> {
        val byId = library.associateBy { it.id }
        return playlist.songIds.mapNotNull { byId[it] }
    }
}

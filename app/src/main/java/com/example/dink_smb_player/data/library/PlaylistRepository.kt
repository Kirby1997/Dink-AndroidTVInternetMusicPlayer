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
 * shape (process-level restore guard) but is simpler — playlists are small and never
 * pruned automatically.
 */
object PlaylistRepository {

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    @Volatile private var restored = false
    private val restoreMutex = Mutex()

    suspend fun ensureRestored(context: Context) {
        if (restored) return
        restoreMutex.withLock {
            if (restored) return
            PlaylistStore.load(context)?.let { _playlists.value = it }
            restored = true
        }
    }

    private suspend fun persist(context: Context) = PlaylistStore.save(context, _playlists.value)

    /** Create a playlist, optionally seeding it with one track. Returns the new id. */
    suspend fun create(context: Context, name: String, seedSongId: String? = null): String {
        val now = System.currentTimeMillis()
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Untitled playlist" },
            songIds = listOfNotNull(seedSongId),
            createdMs = now,
            updatedMs = now,
        )
        _playlists.update { it + playlist }
        persist(context)
        return playlist.id
    }

    suspend fun rename(context: Context, id: String, name: String) {
        _playlists.update { list ->
            list.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }, updatedMs = System.currentTimeMillis()) else it }
        }
        persist(context)
    }

    suspend fun delete(context: Context, id: String) {
        _playlists.update { list -> list.filterNot { it.id == id } }
        persist(context)
    }

    /** Append [songId] unless already present (no duplicates). */
    suspend fun addSong(context: Context, playlistId: String, songId: String) {
        _playlists.update { list ->
            list.map { p ->
                if (p.id == playlistId && songId !in p.songIds)
                    p.copy(songIds = p.songIds + songId, updatedMs = System.currentTimeMillis())
                else p
            }
        }
        persist(context)
    }

    suspend fun removeSong(context: Context, playlistId: String, songId: String) {
        _playlists.update { list ->
            list.map { p ->
                if (p.id == playlistId)
                    p.copy(songIds = p.songIds - songId, updatedMs = System.currentTimeMillis())
                else p
            }
        }
        persist(context)
    }

    /** Resolve a playlist's ids to playable [Song]s in playlist order, skipping ids
     *  that don't currently resolve (e.g. an offline source). */
    fun songsOf(playlist: Playlist, library: List<Song>): List<Song> {
        val byId = library.associateBy { it.id }
        return playlist.songIds.mapNotNull { byId[it] }
    }
}

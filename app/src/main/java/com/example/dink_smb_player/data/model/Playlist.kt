package com.example.dink_smb_player.data.model

import kotlinx.serialization.Serializable

/**
 * A user-curated, ordered list of tracks. [songIds] reference [TrackEntity.id] /
 * [Song.id] — which is `sha1(type+source+path)`, stable across re-import — so a
 * playlist survives a re-scan of its source. Order is meaningful (insertion order;
 * playback follows it). A song id that no longer resolves to a library track is just
 * skipped at play time, never auto-pruned, so a temporarily-offline source (SMB down)
 * doesn't silently empty a playlist.
 */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val songIds: List<String> = emptyList(),
    val createdMs: Long,
    val updatedMs: Long,
)

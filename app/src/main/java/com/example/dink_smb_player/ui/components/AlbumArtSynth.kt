package com.example.dink_smb_player.ui.components

import com.example.dink_smb_player.data.model.Album
import com.example.dink_smb_player.data.model.AlbumArtShape
import com.example.dink_smb_player.data.model.ArtPalette
import com.example.dink_smb_player.data.model.Song

/**
 * Deterministic procedural album art for an indexed track. Real SMB/cloud/local tracks
 * carry no embedded artwork yet (TagReader reads text tags + duration, not pictures), so
 * the UI derives a stable cover from a hash. Keyed on album when known so a whole album
 * shares one cover; same seeding scheme NowPlaying + Home use, so a song looks identical
 * wherever it appears.
 */
private val SYNTH_PALETTES = listOf(
    ArtPalette(0xFF6B7FFFL, 0xFF8B5CF6L, 0xFFEC4899L),
    ArtPalette(0xFF22D3EEL, 0xFF2563EBL, 0xFF7C3AEDL),
    ArtPalette(0xFFF59E0BL, 0xFFEF4444L, 0xFFEC4899L),
    ArtPalette(0xFF10B981L, 0xFF06B6D4L, 0xFF3B82F6L),
    ArtPalette(0xFFE11D48L, 0xFFEC4899L, 0xFF8B5CF6L),
    ArtPalette(0xFF0EA5E9L, 0xFF14B8A6L, 0xFF22C55EL),
)

fun synthAlbumFor(song: Song): Album {
    val seedKey = song.albumTitle?.takeIf { it.isNotBlank() } ?: song.id
    val seed = seedKey.hashCode() and 0x7FFFFFFF
    val palette = SYNTH_PALETTES[seed % SYNTH_PALETTES.size]
    val shapes = AlbumArtShape.values()
    val shape = shapes[(seed ushr 8) % shapes.size]
    return Album(
        id = "synth-$seedKey",
        title = song.albumTitle.orEmpty(),
        artist = song.artist,
        year = null,
        palette = palette,
        shape = shape,
        tag = song.bitrate.uppercase(),
    )
}

package com.example.dink_smb_player.data.model

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

/** Shape kinds used by the procedural album-art painter (see ui/components/AlbumArt.kt, Phase 4). */
enum class AlbumArtShape { Orbits, Horizon, Wave, Grid, Rings, Diag, Paper }

/** Three-stop palette used to drive procedural album art. Stored as ARGB longs so it survives serialization. */
@Serializable
data class ArtPalette(val c1: Long, val c2: Long, val c3: Long) {
    fun colors(): Triple<Color, Color, Color> = Triple(Color(c1), Color(c2), Color(c3))
}

@Serializable
data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val palette: ArtPalette,
    val shape: AlbumArtShape,
    val tag: String,
)

@Serializable
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val albumId: String?,
    val albumTitle: String?,
    val durationSec: Int,
    val playCount: Int,
    val sourcePath: String,
    val bitrate: String,
    /** Real playable URI (SAF / file:// / smb://). When null, ExoPlayer can't play this
     *  song — the synthetic clock falls back so mock data still ticks the UI. */
    val mediaUri: String? = null,
)

/** One line of synchronised lyrics. timeSec is the start time of this line in the track. */
@Serializable
data class LyricLine(
    val timeSec: Float,
    val text: String,
    val words: List<WordTiming> = emptyList(),
)

/** Optional per-word timing for A2-enhanced LRC. Offsets are within-line, in seconds from line start. */
@Serializable
data class WordTiming(val offsetSec: Float, val text: String)

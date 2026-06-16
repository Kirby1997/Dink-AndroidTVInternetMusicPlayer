package com.example.dink_smb_player.data

import com.example.dink_smb_player.BuildConfig
import com.example.dink_smb_player.data.model.Album
import com.example.dink_smb_player.data.model.AlbumArtShape
import com.example.dink_smb_player.data.model.ArtPalette
import com.example.dink_smb_player.data.model.AuthMethod
import com.example.dink_smb_player.data.model.CloudProvider
import com.example.dink_smb_player.data.model.ConnectionStatus
import com.example.dink_smb_player.data.model.LocalVolume
import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.ProviderGlyph
import com.example.dink_smb_player.data.model.SmbProtocol
import com.example.dink_smb_player.data.model.SmbShare
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.data.model.SyncSchedule

/**
 * Preview / @Preview Composable seed data. **Never seeded into the live MediaIndex.**
 * Phase 10 strips this from release builds via [BuildConfig.DEBUG] gating at call sites.
 *
 * Replace any UI placeholder with mock objects from this file during Phase 4 & 5 development.
 */
object PreviewMockData {

    val enabled: Boolean get() = BuildConfig.DEBUG

    val albumIxion = Album(
        id = "album-ixion",
        title = "Ixion",
        artist = "Lateral Halo",
        year = 2024,
        palette = ArtPalette(0xFF1B2238, 0xFF5B8DFF, 0xFF9B6DFF),
        shape = AlbumArtShape.Orbits,
        tag = "AMBIENT / 24-96",
    )

    val albumBossa = Album(
        id = "album-bossa",
        title = "Cafezinho",
        artist = "Mar de Espelhos",
        year = 2019,
        palette = ArtPalette(0xFF3A2A12, 0xFFF0A23A, 0xFFF4E5C2),
        shape = AlbumArtShape.Wave,
        tag = "BOSSA NOVA",
    )

    val songIxion = Song(
        id = "song-ixion-1",
        title = "Argent Dawn",
        artist = "Lateral Halo",
        albumId = albumIxion.id,
        albumTitle = albumIxion.title,
        durationSec = 242,
        playCount = 12,
        sourcePath = "attic-nas/music/ambient",
        bitrate = "24/96 FLAC",
    )

    val songBossa = Song(
        id = "song-bossa-1",
        title = "Sereia",
        artist = "Mar de Espelhos",
        albumId = albumBossa.id,
        albumTitle = albumBossa.title,
        durationSec = 211,
        playCount = 88,
        sourcePath = "attic-nas/music",
        bitrate = "MP3 320",
    )

    val albumNorthLight = Album(
        id = "album-northlight",
        title = "North Light",
        artist = "Slow Reactor",
        year = 2022,
        palette = ArtPalette(0xFF0E1C2A, 0xFF3FB1D8, 0xFFE7F4FA),
        shape = AlbumArtShape.Horizon,
        tag = "DOWNTEMPO / 24-48",
    )

    val albumRiverGrid = Album(
        id = "album-rivergrid",
        title = "River Grid",
        artist = "Quiet Engine",
        year = 2021,
        palette = ArtPalette(0xFF1B1F22, 0xFF6F7A82, 0xFFC8D2DA),
        shape = AlbumArtShape.Grid,
        tag = "IDM",
    )

    val albumParlour = Album(
        id = "album-parlour",
        title = "Parlour Hours",
        artist = "Vera Linde",
        year = 2018,
        palette = ArtPalette(0xFF2A1410, 0xFFC0533A, 0xFFF2D7B5),
        shape = AlbumArtShape.Rings,
        tag = "JAZZ TRIO",
    )

    val albumSubmerge = Album(
        id = "album-submerge",
        title = "Submerge",
        artist = "Field Pattern",
        year = 2025,
        palette = ArtPalette(0xFF06121A, 0xFF1F6C82, 0xFF7CC8C0),
        shape = AlbumArtShape.Diag,
        tag = "AMBIENT / 24-192",
    )

    val albumKitePages = Album(
        id = "album-kitepages",
        title = "Kite Pages",
        artist = "Aster & Bow",
        year = 2020,
        palette = ArtPalette(0xFFEFE8DC, 0xFFB89F73, 0xFF6A4F2A),
        shape = AlbumArtShape.Paper,
        tag = "FOLK",
    )

    val songNorthLight1 = Song(
        id = "song-northlight-1",
        title = "Polar Drift",
        artist = "Slow Reactor",
        albumId = albumNorthLight.id,
        albumTitle = albumNorthLight.title,
        durationSec = 288,
        playCount = 41,
        sourcePath = "attic-nas/music/downtempo",
        bitrate = "24/48 FLAC",
    )

    val songNorthLight2 = Song(
        id = "song-northlight-2",
        title = "Halogen",
        artist = "Slow Reactor",
        albumId = albumNorthLight.id,
        albumTitle = albumNorthLight.title,
        durationSec = 256,
        playCount = 23,
        sourcePath = "attic-nas/music/downtempo",
        bitrate = "24/48 FLAC",
    )

    val songRiverGrid1 = Song(
        id = "song-rivergrid-1",
        title = "Pulse Map",
        artist = "Quiet Engine",
        albumId = albumRiverGrid.id,
        albumTitle = albumRiverGrid.title,
        durationSec = 198,
        playCount = 7,
        sourcePath = "gdrive/curated/idm",
        bitrate = "FLAC",
    )

    val songRiverGrid2 = Song(
        id = "song-rivergrid-2",
        title = "Subroutine",
        artist = "Quiet Engine",
        albumId = albumRiverGrid.id,
        albumTitle = albumRiverGrid.title,
        durationSec = 175,
        playCount = 134,
        sourcePath = "gdrive/curated/idm",
        bitrate = "FLAC",
    )

    val songParlour1 = Song(
        id = "song-parlour-1",
        title = "Half-Lit Room",
        artist = "Vera Linde",
        albumId = albumParlour.id,
        albumTitle = albumParlour.title,
        durationSec = 312,
        playCount = 67,
        sourcePath = "usb-drive/jazz",
        bitrate = "MP3 320",
    )

    val songParlour2 = Song(
        id = "song-parlour-2",
        title = "Velvet Hour",
        artist = "Vera Linde",
        albumId = albumParlour.id,
        albumTitle = albumParlour.title,
        durationSec = 268,
        playCount = 19,
        sourcePath = "usb-drive/jazz",
        bitrate = "MP3 320",
    )

    val songSubmerge1 = Song(
        id = "song-submerge-1",
        title = "Lower Channel",
        artist = "Field Pattern",
        albumId = albumSubmerge.id,
        albumTitle = albumSubmerge.title,
        durationSec = 421,
        playCount = 3,
        sourcePath = "attic-nas/music/ambient",
        bitrate = "24/192 FLAC",
    )

    val songSubmerge2 = Song(
        id = "song-submerge-2",
        title = "Brine Static",
        artist = "Field Pattern",
        albumId = albumSubmerge.id,
        albumTitle = albumSubmerge.title,
        durationSec = 384,
        playCount = 0,
        sourcePath = "attic-nas/music/ambient",
        bitrate = "24/192 FLAC",
    )

    val songKitePages1 = Song(
        id = "song-kitepages-1",
        title = "Letter from a Field",
        artist = "Aster & Bow",
        albumId = albumKitePages.id,
        albumTitle = albumKitePages.title,
        durationSec = 224,
        playCount = 52,
        sourcePath = "internal/music",
        bitrate = "MP3 256",
    )

    val songKitePages2 = Song(
        id = "song-kitepages-2",
        title = "Paper Kite",
        artist = "Aster & Bow",
        albumId = albumKitePages.id,
        albumTitle = albumKitePages.title,
        durationSec = 198,
        playCount = 28,
        sourcePath = "internal/music",
        bitrate = "MP3 256",
    )

    val shareAttic = SmbShare(
        id = "smb-attic",
        name = "Attic NAS",
        host = "192.168.1.42",
        port = 445,
        shareName = "music",
        mountPath = "//attic-nas/music",
        user = "jacob",
        protocol = SmbProtocol.Smb3,
        status = ConnectionStatus.Connected,
        trackCount = 16_507,
        sizeBytes = 412L * 1024L * 1024L * 1024L,
        lastSyncMs = System.currentTimeMillis() - 120_000L,
        signal = 0.92f,
        syncSchedule = SyncSchedule.Auto,
    )

    val providerGDrive = CloudProvider(
        id = "cloud-gdrive",
        name = "Google Drive",
        auth = AuthMethod.OAuthDeviceFlow,
        status = ConnectionStatus.Connected,
        account = "jj@wilkinsons.me.uk",
        trackCount = 2_140,
        cacheSize = "12.4 GB / 64 GB",
        lastSyncMs = System.currentTimeMillis() - 600_000L,
        glyph = ProviderGlyph.Triangle,
        syncSchedule = SyncSchedule.Hourly,
    )

    val providerDropboxExpired = CloudProvider(
        id = "cloud-dropbox",
        name = "Dropbox",
        auth = AuthMethod.OAuthPkce,
        status = ConnectionStatus.Expired,
        account = "jacob",
        trackCount = 412,
        cacheSize = "2.1 GB / 64 GB",
        lastSyncMs = System.currentTimeMillis() - 86_400_000L,
        glyph = ProviderGlyph.Diamond,
        syncSchedule = SyncSchedule.Daily,
    )

    val volumeInternal = LocalVolume(
        id = "local-internal",
        label = "Internal",
        path = "/storage/emulated/0",
        isRemovable = false,
        isPrimary = true,
        totalBytes = 32L * 1024L * 1024L * 1024L,
        freeBytes = 12L * 1024L * 1024L * 1024L,
        state = ConnectionStatus.Connected,
        trackCount = 0,
        lastSyncMs = null,
    )

    val volumeUsb = LocalVolume(
        id = "local-usb",
        label = "USB Drive",
        path = "/mnt/media_rw/A1B2-C3D4",
        isRemovable = true,
        isPrimary = false,
        totalBytes = 128L * 1024L * 1024L * 1024L,
        freeBytes = 60L * 1024L * 1024L * 1024L,
        state = ConnectionStatus.Connected,
        trackCount = 1_204,
        lastSyncMs = System.currentTimeMillis() - 60_000L,
    )

    val sampleLyrics = listOf(
        LyricLine(0f,    "Silver morning, slow and wide"),
        LyricLine(6.5f,  "Argent dawn across the tide"),
        LyricLine(13f,   "Halo turning, lateral light"),
        LyricLine(19.5f, "Carry me through the night"),
    )

    val songs: List<Song> = listOf(
        songIxion, songBossa,
        songNorthLight1, songNorthLight2,
        songRiverGrid1, songRiverGrid2,
        songParlour1, songParlour2,
        songSubmerge1, songSubmerge2,
        songKitePages1, songKitePages2,
    )
    val albums: List<Album> = listOf(
        albumIxion, albumBossa,
        albumNorthLight, albumRiverGrid,
        albumParlour, albumSubmerge,
        albumKitePages,
    )
    val shares: List<SmbShare> = listOf(shareAttic)
    val providers: List<CloudProvider> = listOf(providerGDrive, providerDropboxExpired)
    val volumes: List<LocalVolume> = listOf(volumeInternal, volumeUsb)
}

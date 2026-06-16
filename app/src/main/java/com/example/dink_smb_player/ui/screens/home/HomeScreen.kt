package com.example.dink_smb_player.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.example.dink_smb_player.LocalContentFocus
import com.example.dink_smb_player.LocalRailFocusRequester
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.model.Album
import com.example.dink_smb_player.data.model.AlbumArtShape
import com.example.dink_smb_player.data.model.ArtPalette
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.player.PlayerState
import com.example.dink_smb_player.ui.components.CoverArt
import com.example.dink_smb_player.ui.components.AlbumCard
import com.example.dink_smb_player.ui.components.GhostButton
import com.example.dink_smb_player.ui.components.GradientButton
import com.example.dink_smb_player.ui.components.ShelfRow
import com.example.dink_smb_player.ui.components.SongCard
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType

private data class HomeFeed(
    val resumeSong: Song,
    val resumeAlbum: Album,
    val recentlyPlayed: List<Pair<Song, Album?>>,
    val newOnShares: List<Album>,
    val acrossShares: List<Pair<Song, Album?>>,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    player: PlayerState,
    onNavigate: (ScreenId) -> Unit,
    onToast: (String) -> Unit,
) {
    val context = LocalContext.current
    val railRequester = LocalRailFocusRequester.current

    // Index-backed feeds (Phase 9): every imported track surfaces here regardless of
    // which source scanned it. Initial = synchronous snapshot so Home doesn't flash a
    // frame of empty content on (re)composition.
    val allSongs by remember(context) { LibraryRepository.songs(context) }.collectAsState()
    val recentPlayed by remember(context) { LibraryRepository.recentlyPlayed(context, 12) }
        .collectAsState(initial = emptyList())
    val recentAdded by remember(context) { LibraryRepository.recentlyAdded(context, 12) }
        .collectAsState(initial = emptyList())

    val feed = remember(allSongs, recentPlayed, recentAdded) {
        buildHomeFeed(allSongs, recentPlayed, recentAdded)
    }

    // Empty library → an invitation, not a barren hero. Keeps a focusable so the drawer
    // can collapse onto content.
    if (feed == null) {
        EmptyHome(onNavigate = onNavigate)
        return
    }

    // One requester per shelf, attached to the shelf's first card. Hero Down →
    // recent; recent Down → new; new Down → across. Same chain in reverse for Up.
    val recentFirstRequester = remember { FocusRequester() }
    val newFirstRequester = remember { FocusRequester() }
    val acrossFirstRequester = remember { FocusRequester() }
    val heroRequester = remember { FocusRequester() }

    LaunchedEffect(feed.resumeSong.id) {
        // Preload the resume track so the mini player + Now Playing have data before the
        // user lifts a finger. Lyrics resolve via DinkApp's currentSong effect.
        if (player.currentSong == null) {
            player.load(feed.resumeSong, feed.resumeAlbum, emptyList(), autoplay = false)
        }
    }

    // Column + verticalScroll so all four sections (Hero + 3 shelves) stay composed.
    // The per-shelf FocusRequester chain breaks if a target shelf is disposed
    // (LazyColumn would do that); spatial-driven page scroll just follows focus.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Hero(
            song = feed.resumeSong,
            album = feed.resumeAlbum,
            railRequester = railRequester,
            heroRequester = heroRequester,
            downTarget = recentFirstRequester,
            onContinue = {
                player.load(feed.resumeSong, feed.resumeAlbum, emptyList())
                onNavigate(ScreenId.NowPlaying)
            },
            onAddToQueue = {
                player.addToQueue(feed.resumeSong)
                onToast("Added ${feed.resumeSong.title} to queue")
            },
            onViewAlbum = { onNavigate(ScreenId.Albums) },
        )
        Spacer(Modifier.height(40.dp))
        if (feed.recentlyPlayed.isNotEmpty()) {
            ShelfRow(
                title = "Recently played",
                eyebrow = "Across all your sources",
                onViewAll = { onNavigate(ScreenId.Songs) },
                onEnterRequester = recentFirstRequester,
            ) {
                itemsIndexed(feed.recentlyPlayed) { idx, (song, album) ->
                    SongCard(
                        song = song,
                        album = album,
                        onClick = {
                            player.playFrom(feed.recentlyPlayed.map { it.first }, idx)
                            onNavigate(ScreenId.NowPlaying)
                        },
                        modifier = cardFocus(
                            railRequester = railRequester,
                            isLeftEdge = idx == 0,
                            upTarget = heroRequester,
                            downTarget = newFirstRequester,
                        ).let { if (idx == 0) it.focusRequester(recentFirstRequester) else it },
                    )
                }
            }
        }
        if (feed.newOnShares.isNotEmpty()) {
            ShelfRow(
                title = "New in your library",
                eyebrow = "Recently imported",
                onViewAll = { onNavigate(ScreenId.Albums) },
                onEnterRequester = newFirstRequester,
            ) {
                itemsIndexed(feed.newOnShares) { idx, album ->
                    AlbumCard(
                        album = album,
                        onClick = { onNavigate(ScreenId.Albums) },
                        modifier = cardFocus(
                            railRequester = railRequester,
                            isLeftEdge = idx == 0,
                            upTarget = recentFirstRequester,
                            downTarget = acrossFirstRequester,
                        ).let { if (idx == 0) it.focusRequester(newFirstRequester) else it },
                    )
                }
            }
        }
        if (feed.acrossShares.isNotEmpty()) {
            ShelfRow(
                title = "Across your sources",
                eyebrow = "A spin through everything you've added",
                onViewAll = { onNavigate(ScreenId.Songs) },
                onEnterRequester = acrossFirstRequester,
            ) {
                itemsIndexed(feed.acrossShares) { idx, (song, album) ->
                    SongCard(
                        song = song,
                        album = album,
                        onClick = {
                            player.playFrom(feed.acrossShares.map { it.first }, idx)
                            onNavigate(ScreenId.NowPlaying)
                        },
                        modifier = cardFocus(
                            railRequester = railRequester,
                            isLeftEdge = idx == 0,
                            upTarget = newFirstRequester,
                        ).let { if (idx == 0) it.focusRequester(acrossFirstRequester) else it },
                    )
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun cardFocus(
    railRequester: FocusRequester,
    isLeftEdge: Boolean,
    upTarget: FocusRequester? = null,
    downTarget: FocusRequester? = null,
): Modifier = Modifier.focusProperties {
    if (isLeftEdge) left = railRequester
    upTarget?.let { up = it }
    downTarget?.let { down = it }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun EmptyHome(onNavigate: (ScreenId) -> Unit) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val contentFocus = LocalContentFocus.current
    val railRequester = LocalRailFocusRequester.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg0)
            .padding(horizontal = 64.dp, vertical = 48.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.CenterStart).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("WELCOME TO DINK", style = type.monoSmall.copy(color = palette.ink3))
            Text("Nothing playing yet", style = type.heroTitle.copy(color = palette.ink0))
            Text(
                text = "Add a NAS over SMB, or plug in USB / SD storage, then import a folder. " +
                    "Your music shows up here — recently played, recently added and a spin " +
                    "through everything.",
                style = type.body.copy(color = palette.ink1),
            )
            Spacer(Modifier.height(8.dp))
            GradientButton(
                label = "Add a source",
                leadingIcon = Icons.Outlined.Storage,
                onClick = { onNavigate(ScreenId.SmbShares) },
                modifier = Modifier
                    .focusRequester(contentFocus)
                    .focusProperties { left = railRequester }
                    .focusable(),
            )
        }
    }
}

@Composable
private fun Hero(
    song: Song,
    album: Album,
    railRequester: FocusRequester,
    heroRequester: FocusRequester,
    downTarget: FocusRequester,
    onContinue: () -> Unit,
    onAddToQueue: () -> Unit,
    onViewAlbum: () -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(620.dp),
    ) {
        // Blurred album-art background, scaled past the bounds so the blur edge doesn't show.
        CoverArt(
            song = song,
            palette = album.palette,
            shape = album.shape,
            cornerRadius = 0.dp,
            modifier = Modifier
                .fillMaxSize()
                .blur(60.dp),
        )
        // Horizontal veil — readable on the left, transparent on the right.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to palette.bg0,
                        0.4f to palette.bg0.copy(alpha = 0.7f),
                        0.75f to Color.Transparent,
                    ),
                ),
        )
        // Vertical fade into the page background.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.75f to Color.Transparent,
                        1f to palette.bg0,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 64.dp, end = 64.dp)
                .widthIn(max = 1180.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ResumePill()
                Text(
                    text = "FROM ${song.sourcePath.uppercase()} · ${song.bitrate.uppercase()}",
                    style = type.monoSmall.copy(color = palette.ink2),
                )
            }
            Text(
                text = "${album.tag}  ·  ${album.year ?: ""}".trim().trimEnd('·', ' '),
                style = type.monoSmall.copy(color = palette.ink3),
            )
            Text(
                text = song.title,
                style = type.heroTitle.copy(color = palette.ink0),
            )
            Text(
                text = buildString {
                    append(song.artist)
                    album.title.takeIf { it.isNotBlank() }?.let { append(" · "); append(it) }
                    if (song.durationSec > 0) { append(" · "); append(formatDuration(song.durationSec)) }
                },
                style = type.body.copy(color = palette.ink1),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GradientButton(
                    label = "Continue Playing",
                    leadingIcon = Icons.Filled.PlayArrow,
                    onClick = onContinue,
                    modifier = cardFocus(
                        railRequester = railRequester,
                        isLeftEdge = true,
                        downTarget = downTarget,
                    )
                        .focusRequester(LocalContentFocus.current)
                        .focusRequester(heroRequester),
                )
                GhostButton(
                    label = "Add to Queue",
                    leadingIcon = Icons.Filled.Add,
                    onClick = onAddToQueue,
                    modifier = cardFocus(
                        railRequester = railRequester,
                        isLeftEdge = false,
                        downTarget = downTarget,
                    ),
                )
                GhostButton(
                    label = "View Album",
                    leadingIcon = Icons.Outlined.QueueMusic,
                    onClick = onViewAlbum,
                    modifier = cardFocus(
                        railRequester = railRequester,
                        isLeftEdge = false,
                        downTarget = downTarget,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ResumePill() {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    Row(
        modifier = Modifier
            .background(palette.good.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(palette.good, RoundedCornerShape(50)),
        )
        Text("RESUME", style = type.monoSmall.copy(color = palette.good))
    }
}

private fun formatDuration(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}

// ---- Index → feed ----

/** Build the Home feed from the live index. Returns null when the library is empty so
 *  the screen can show an invitation instead of a barren hero. Resume = most recently
 *  played (else the first indexed track). Shelves fall back to library slices so a fresh
 *  import with no play history still looks alive. */
private fun buildHomeFeed(
    library: List<Song>,
    recentPlayed: List<Song>,
    recentAdded: List<Song>,
): HomeFeed? {
    val resumeSong = recentPlayed.firstOrNull() ?: library.firstOrNull() ?: return null
    val resumeAlbum = synthAlbumFor(resumeSong)

    fun withArt(songs: List<Song>): List<Pair<Song, Album?>> = songs.map { it to synthAlbumFor(it) }

    val recently = (recentPlayed.ifEmpty { library.take(8) }).take(10)

    // "New in your library" — one card per distinct recently-added album (dedupe so a
    // freshly-imported 12-track album isn't 12 identical cards). Falls back to the tail
    // of the library (newest ids last) when play/import history is thin.
    val addedPool = recentAdded.ifEmpty { library.takeLast(12).reversed() }
    val newAlbums = addedPool
        .distinctBy { it.albumTitle ?: it.id }
        .take(10)
        .map { synthAlbumFor(it) }

    // A stable shuffle: keyed on the call's remember above, so it doesn't reshuffle on
    // every recomposition. Spread across the library, not just the head.
    val across = library.shuffled().take(10)

    return HomeFeed(
        resumeSong = resumeSong,
        resumeAlbum = resumeAlbum,
        recentlyPlayed = withArt(recently),
        newOnShares = newAlbums,
        acrossShares = withArt(across),
    )
}

private val FALLBACK_PALETTES = listOf(
    ArtPalette(0xFF6B7FFFL, 0xFF8B5CF6L, 0xFFEC4899L),
    ArtPalette(0xFF22D3EEL, 0xFF2563EBL, 0xFF7C3AEDL),
    ArtPalette(0xFFF59E0BL, 0xFFEF4444L, 0xFFEC4899L),
    ArtPalette(0xFF10B981L, 0xFF06B6D4L, 0xFF3B82F6L),
    ArtPalette(0xFFE11D48L, 0xFFEC4899L, 0xFF8B5CF6L),
    ArtPalette(0xFF0EA5E9L, 0xFF14B8A6L, 0xFF22C55EL),
)

/** Deterministic procedural album art for an indexed track (real tracks carry no art).
 *  Same seeding scheme as NowPlayingScreen's fallback so a song looks identical wherever
 *  it appears. Keyed on album when known so a whole album shares one cover. */
private fun synthAlbumFor(song: Song): Album {
    val seedKey = song.albumTitle?.takeIf { it.isNotBlank() } ?: song.id
    val seed = seedKey.hashCode() and 0x7FFFFFFF
    val palette = FALLBACK_PALETTES[seed % FALLBACK_PALETTES.size]
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

package com.example.dink_smb_player.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.example.dink_smb_player.LocalContentFocus
import com.example.dink_smb_player.LocalRailFocusRequester
import com.example.dink_smb_player.data.index.LibraryGrouping
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
import com.example.dink_smb_player.ui.components.ThinLoadingBar
import com.example.dink_smb_player.ui.screens.library.LibraryDetailNav
import com.example.dink_smb_player.ui.screens.library.albumGroups
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private data class HomeFeed(
    val resumeSong: Song,
    val resumeAlbum: Album,
    val recentlyPlayed: List<Pair<Song, Album?>>,
    /** Album card + a representative song from it, so a click can resolve the album's
     *  track list (the synth Album alone can't — its id is a synthetic art seed). */
    val newOnShares: List<Pair<Album, Song>>,
    val acrossShares: List<Pair<Song, Album?>>,
)

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
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
    // True until the on-disk index finishes loading at boot. Distinguishes a genuinely
    // empty library from one that just hasn't been restored yet — see the feed == null branch.
    val restored by LibraryRepository.restoredState.collectAsState()
    // initial = null (not emptyList) so "the DB hasn't answered yet" is distinguishable
    // from "genuinely no play history". With an emptyList initial the feed fell back to
    // library.firstOrNull() — alphabetically-first track ("!…") — and the hero flashed a
    // wrong song on every cold launch until the real recently-played rows arrived.
    val recentPlayed by remember(context) { LibraryRepository.recentlyPlayed(context, 12) }
        .collectAsState(initial = null)
    val recentAdded by remember(context) { LibraryRepository.recentlyAdded(context, 12) }
        .collectAsState(initial = emptyList())

    // Per-composition seed for the "Across your sources" sample: fresh picks each visit,
    // stable across feed rebuilds (index updates during an import walk used to reshuffle
    // the cards mid-browse because selection was keyed on the list instance).
    val acrossSeed = remember { kotlin.random.Random.nextInt() }
    val feed = remember(allSongs, recentPlayed, recentAdded) {
        recentPlayed?.let { buildHomeFeed(allSongs, it, recentAdded, acrossSeed) }
    }

    // Empty library → an invitation, not a barren hero. Keeps a focusable so the drawer
    // can collapse onto content. But feed == null also happens transiently at cold boot,
    // in two stages we must NOT show EmptyHome through:
    //   1. restore() still parsing library_index.json (restored == false).
    //   2. restore() done (restored == true) but the async songs() flow — which sorts on
    //      Dispatchers.Default — hasn't re-emitted yet, so allSongs is still empty for a
    //      frame even though the index already holds tracks.
    // Gating on `restored` alone let stage 2 flash "nothing loaded" between the loading bar
    // and the hero. So consult the SYNCHRONOUS index count, which reflects restore the
    // instant it upserts: show EmptyHome only when restore is done AND the index is truly
    // empty; otherwise we're still settling — show loading.
    // Also hold the loading screen until the launch session-restore has finished:
    // rendering the hero earlier made "Continue Playing" act on a player whose queue and
    // engine weren't populated yet (press → nothing plays), and showed the index's stale
    // resume guess instead of the actually-restored track.
    if (feed == null || !player.sessionRestoreDone) {
        val indexEmpty = restored && player.sessionRestoreDone &&
            LibraryRepository.trackCountNow(context) == 0
        if (indexEmpty) EmptyHome(onNavigate = onNavigate) else HomeLoading()
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
        // user lifts a finger. Lyrics resolve via DinkApp's currentSong effect. The gate
        // above guarantees the launch session-restore already ran, so a null currentSong
        // here really means "no saved session" — a restored one can't be clobbered.
        if (player.currentSong == null) {
            player.load(feed.resumeSong, feed.resumeAlbum, emptyList(), autoplay = false)
        }
    }

    // Open one album's track list (the LibraryDetail screen), resolved from a
    // representative song by the same key normalisation the Albums screen groups with.
    // Parent = Home so the rail highlights Home and Back lands back here, not on Albums.
    val openAlbumDetail: (Song) -> Unit = { rep ->
        val key = rep.albumKey ?: LibraryGrouping.normKey(rep.albumTitle ?: "Unknown album")
        val albumSongs = allSongs.filter {
            (it.albumKey ?: LibraryGrouping.normKey(it.albumTitle ?: "Unknown album")) == key
        }.ifEmpty { listOf(rep) }
        albumGroups(albumSongs).firstOrNull()?.let { group ->
            LibraryDetailNav.open(group, "Album", ScreenId.Home)
            onNavigate(ScreenId.LibraryDetail)
        }
    }

    // The Hero always reflects what's ACTUALLY loaded/last-played: the live player track
    // (restored session or whatever's playing now) wins over the index's lastPlayed guess,
    // which was going stale and pinning the hero to an old track.
    val resumeSong = player.currentSong ?: feed.resumeSong
    val resumeAlbum = remember(resumeSong.id) { synthAlbumFor(resumeSong) }
    // Continue = resume the already-loaded track at its position; only (re)load from the
    // start when the hero is a different track than the one the player holds.
    val onContinue: () -> Unit = {
        if (player.currentSong?.id == resumeSong.id) {
            if (!player.isPlaying) player.togglePlayPause()
        } else {
            player.load(resumeSong, resumeAlbum, emptyList())
        }
        onNavigate(ScreenId.NowPlaying)
    }

    // Column + verticalScroll so all four sections (Hero + 3 shelves) stay composed.
    // The per-shelf FocusRequester chain breaks if a target shelf is disposed
    // (LazyColumn would do that); spatial-driven page scroll just follows focus.
    // BoxWithConstraints sits OUTSIDE the scroll so maxHeight is the real viewport:
    // the hero must fit it entirely, otherwise focusing a hero button makes
    // bringIntoView scroll the title's top edge off screen.
    // Shelves compose two frames after the hero: first-composing the hero plus ~35
    // shelf cards in one pass was a single ~1.7s main-thread block on this TV. The
    // hero (what the user actually looks at first) now lands immediately and the
    // shelves follow within a blink. Focus-safe: the hero's Down target stays null
    // until the shelf targets exist.
    var shelvesReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        shelvesReady = true
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Leave a strip below the hero so the first shelf's header peeks above the fold;
        // a hero that exactly fills the viewport hid that there is anything to scroll to.
        val heroHeight = min(620.dp, (maxHeight - 56.dp).coerceAtLeast(240.dp))
        val scrollState = rememberScrollState()
        var heroFocused by remember { mutableStateOf(false) }
        // The platform TV bring-into-view spec scrolls a focused child toward the
        // viewport's pivot even when it's already fully visible. On this page that meant
        // every hero-button focus change kicked off a pivot scroll which the pin-to-top
        // loop below then animated back — a visible bounce on each Left/Right press.
        // Vertically we only ever want "reveal the item": scroll zero when it's already
        // fully on screen, else the minimum distance to its nearest edge.
        val revealOnlySpec = remember {
            object : BringIntoViewSpec {
                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float,
                ): Float {
                    val trailing = offset + size
                    return when {
                        offset >= 0f && trailing <= containerSize -> 0f
                        offset < 0f && trailing <= containerSize -> offset
                        trailing > containerSize && offset >= 0f -> trailing - containerSize
                        else -> 0f // taller than the viewport — no scroll fully reveals it
                    }
                }
            }
        }
        // Shelf rows keep the TV pivot spec for their HORIZONTAL card browsing — the
        // look-ahead of upcoming cards is what makes D-pad row scrolling feel right.
        val pivotSpec = LocalBringIntoViewSpec.current
        // With the reveal-only spec, entering the hero from a shelf scrolls just enough
        // to expose the focused button — the title above it could stay clipped. The hero
        // fits the viewport, so pull the page to the top while focus is inside it. The
        // spec no longer re-scrolls fully-visible items, so nothing fights this back.
        LaunchedEffect(heroFocused) {
            if (!heroFocused) return@LaunchedEffect
            snapshotFlow { scrollState.value }.collect { offset ->
                if (offset != 0) {
                    try {
                        scrollState.animateScrollTo(0)
                    } catch (e: CancellationException) {
                        // Mutex steal by a bring-into-view scroll, not our own
                        // cancellation — stay alive and re-assert on the next change.
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides revealOnlySpec) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            Hero(
                song = resumeSong,
                album = resumeAlbum,
                height = heroHeight,
                railRequester = railRequester,
                heroRequester = heroRequester,
                downTarget = if (shelvesReady) recentFirstRequester else null,
                onFocusedChange = { heroFocused = it },
                onContinue = onContinue,
                onAddToQueue = {
                    player.addToQueue(resumeSong)
                    onToast("Added ${resumeSong.title} to queue")
                },
                onViewAlbum = { openAlbumDetail(resumeSong) },
            )
            Spacer(Modifier.height(40.dp))
            if (shelvesReady) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides pivotSpec) {
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
                    itemsIndexed(feed.newOnShares) { idx, (album, repSong) ->
                        AlbumCard(
                            album = album,
                            onClick = { openAlbumDetail(repSong) },
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
            } // pivotSpec provider (shelves)
            } // shelvesReady
            Spacer(Modifier.height(48.dp))
        }
        } // revealOnlySpec provider (page)
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
private fun HomeLoading() {
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
            // Keep a focusable bound to contentFocus so the drawer can collapse onto
            // content during the restore window, exactly as EmptyHome does.
            modifier = Modifier
                .align(Alignment.CenterStart)
                .widthIn(max = 640.dp)
                .focusRequester(contentFocus)
                .focusProperties { left = railRequester }
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("LOADING YOUR LIBRARY", style = type.monoSmall.copy(color = palette.ink3))
            ThinLoadingBar(modifier = Modifier.widthIn(max = 360.dp))
        }
    }
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
    height: Dp,
    railRequester: FocusRequester,
    heroRequester: FocusRequester,
    /** Null while the shelves below are not composed yet (staggered first frame). */
    downTarget: FocusRequester?,
    onFocusedChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onAddToQueue: () -> Unit,
    onViewAlbum: () -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current

    // Height is capped to the visible viewport by the caller. If the hero exceeded it,
    // focusing a hero button would bring-into-view scroll the title off the top edge.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .onFocusChanged { onFocusedChange(it.hasFocus) },
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
                    // Parent folder(s) only — the full //host/share/…/file.mp3 path wrapped
                    // two lines and read like a stack trace. Format (MP3 etc.) already
                    // appears on the tag line below, so don't repeat the bitrate here.
                    text = "FROM ${sourceCrumb(song.sourcePath).uppercase()}",
                    style = type.monoSmall.copy(color = palette.ink2),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${album.tag}  ·  ${album.year ?: ""}".trim().trimEnd('·', ' '),
                style = type.monoSmall.copy(color = palette.ink3),
            )
            Text(
                text = song.title,
                // On a short viewport (hero capped well under the 620dp design height,
                // now minus the shelf-peek strip) the 84sp title can't co-fit with the
                // pill, artist line and buttons at 2 lines — step it down.
                style = if (height < 560.dp) {
                    type.heroTitle.copy(color = palette.ink0, fontSize = 60.sp, lineHeight = 64.sp)
                } else {
                    type.heroTitle.copy(color = palette.ink0)
                },
                // Long titles wrapped to 3+ lines make the hero taller than the viewport,
                // which re-clips the title top when a hero button takes focus.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(song.artist)
                    album.title.takeIf { it.isNotBlank() }?.let { append(" · "); append(it) }
                    if (song.durationSec > 0) { append(" · "); append(formatDuration(song.durationSec)) }
                },
                style = type.body.copy(color = palette.ink1),
            )
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

/** Pseudo-random sample of [n] songs: the n smallest by a seeded hash of the song id.
 *  One O(n) pass with a tiny heap — no whole-library copy/shuffle — and, because rank
 *  depends only on (id, seed), the picks hold steady while imports mutate the list. */
private fun sampleStable(songs: List<Song>, n: Int, seed: Int): List<Song> {
    if (songs.size <= n) return songs
    fun rank(song: Song): Int {
        val h = (song.id.hashCode() xor seed) * -0x61c88647 // Fibonacci hash mix
        return h xor (h ushr 16)
    }
    val worstFirst = java.util.PriorityQueue<Pair<Int, Song>>(n + 1, compareByDescending { it.first })
    for (song in songs) {
        val r = rank(song)
        if (worstFirst.size < n) worstFirst.add(r to song)
        else if (r < worstFirst.peek().first) { worstFirst.poll(); worstFirst.add(r to song) }
    }
    return worstFirst.sortedBy { it.first }.map { it.second }
}

/** Compact origin for the hero eyebrow: the file's last two parent folders, e.g.
 *  "MUSE / NEW BORN (2001)" instead of the whole //host/share/…/file.mp3 path. */
private fun sourceCrumb(path: String): String =
    path.replace('\\', '/').split('/').filter { it.isNotBlank() }
        .dropLast(1).takeLast(2).joinToString(" / ")
        .ifBlank { path }

// ---- Index → feed ----

/** Build the Home feed from the live index. Returns null when the library is empty so
 *  the screen can show an invitation instead of a barren hero. Resume = most recently
 *  played (else the first indexed track). Shelves fall back to library slices so a fresh
 *  import with no play history still looks alive. */
private fun buildHomeFeed(
    library: List<Song>,
    recentPlayed: List<Song>,
    recentAdded: List<Song>,
    acrossSeed: Int,
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
        .map { synthAlbumFor(it) to it }

    // Seeded sample instead of library.shuffled(): shuffling copies + permutes the whole
    // (possibly 25k+) library on the main thread every feed rebuild, and its picks churned
    // whenever the index re-emitted during an import walk. Selection keyed on song id +
    // per-visit seed is O(n), stable across rebuilds, fresh per Home visit.
    val across = sampleStable(library, 10, acrossSeed)

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

@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.dink_smb_player.ui.screens.nowplaying

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.LocalContentFocus
import com.example.dink_smb_player.LocalRailFocusRequester
import com.example.dink_smb_player.data.model.Album
import com.example.dink_smb_player.data.model.AlbumArtShape
import com.example.dink_smb_player.data.model.ArtPalette
import com.example.dink_smb_player.data.model.LyricLine
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.player.PlayerState
import com.example.dink_smb_player.player.RepeatMode as PlayerRepeatMode
import com.example.dink_smb_player.ui.screens.settings.SettingsNav
import com.example.dink_smb_player.ui.components.AlbumArt
import com.example.dink_smb_player.ui.components.CoverArt
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType

// V5 design tokens. Constant — does NOT key off the playing album.
// oklch values from the handoff approximated to sRGB.
private val V5BgBase = Color(0xFF0F1018)
private val V5BgGlow1 = Color(0xFF252544)
private val V5BgGlow2 = Color(0xFF1A2235)
private val V5GoodGreen = Color(0xFF3DDC97)
private val V5HaloBlue = Color(0x267FA0FF)
private val V5White08 = Color(0x14FFFFFF)
private val V5White16 = Color(0x29FFFFFF)
private val V5White28 = Color(0x47FFFFFF)
private val V5White14 = Color(0x24FFFFFF)
private val V5White32 = Color(0x52FFFFFF)
private val V5White40 = Color(0x66FFFFFF)
private val V5White50 = Color(0x80FFFFFF)
private val V5White60 = Color(0x99FFFFFF)
private val V5White80 = Color(0xCCFFFFFF)
private val V5QueueBg = Color(0x38000000)
private val V5CardLine = Color(0x0DFFFFFF)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NowPlayingScreen(player: PlayerState, onNavigate: (ScreenId) -> Unit = {}) {
    val song = player.currentSong
    if (song == null) {
        EmptyState()
        return
    }
    val album = player.currentAlbum ?: remember(song.id) { fallbackAlbumFor(song) }

    val playFocus = LocalContentFocus.current
    val lyricsFocus = remember { FocusRequester() }
    // EQ shortcut: jump straight to Settings → Audio tab.
    val onOpenEq: () -> Unit = {
        SettingsNav.initialTab = 1
        onNavigate(ScreenId.Settings)
    }

    // Two-phase mount. The full 3-column V5 layout (lyrics + queue LazyColumn with
    // per-row album-art gradients + Eq animation + screen radial gradients) is too
    // heavy to build on the frame that mounts this screen — previewing NowPlaying
    // on rail-focus then janks and drops the queued D-pad press. So a cheap
    // skeleton renders first and the full layout fills in one beat later. If you
    // arrow away before then, this composable leaves composition and the effect is
    // cancelled, so the heavy layout never builds during a quick pass.
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(180)
        ready = true
    }

    Box(modifier = Modifier.fillMaxSize().v5Background()) {
        if (ready) {
            V5Layout(
                song = song,
                album = album,
                player = player,
                playFocus = playFocus,
                lyricsFocus = lyricsFocus,
                onOpenEq = onOpenEq,
            )
        } else {
            V5Skeleton(song = song)
        }
    }
}

/** Cheap first-frame stand-in for [V5Layout] — just the eyebrow + title/artist,
 *  no lyrics column, no queue list, no album-art gradients. Keeps the
 *  preview-mount off the critical D-pad frame. */
@Composable
private fun V5Skeleton(song: Song) {
    val type = LocalDinkType.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 12.dp, top = 24.dp, bottom = 24.dp),
    ) {
        NowPlayingPill()
        Spacer(Modifier.height(24.dp))
        Text(
            text = song.title,
            style = type.nowPlayingTitle.copy(color = Color.White, fontSize = 28.sp, lineHeight = 32.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = song.artist,
            style = type.body.copy(color = V5White80, fontSize = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * V5 — Tinted Ambience, 3-column. Constant dark background (NOT album-derived).
 *   ┌─────────┬─────────────────┬──────────┐
 *   │ 520.dp  │ flex            │ 460.dp   │
 *   │ art +   │ lyrics with     │ play     │
 *   │ meta +  │ 5-line state    │ queue    │
 *   │ transp. │ hierarchy       │          │
 *   └─────────┴─────────────────┴──────────┘
 */
@Composable
private fun V5Layout(
    song: Song,
    album: Album,
    player: PlayerState,
    playFocus: FocusRequester,
    lyricsFocus: FocusRequester,
    onOpenEq: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = maxWidth
        // Spec is 520dp/flex/460dp at 1920×1080. Below that, all three columns
        // share via weights so the queue is always visible.
        val useFixed = w >= 1400.dp
        Row(modifier = Modifier.fillMaxSize()) {
            V5LeftColumn(
                song = song,
                album = album,
                player = player,
                playFocus = playFocus,
                lyricsFocus = lyricsFocus,
                onOpenEq = onOpenEq,
                modifier = if (useFixed) {
                    Modifier.width(520.dp).fillMaxHeight()
                } else {
                    Modifier.weight(0.26f).fillMaxHeight()
                },
            )
            V5LyricsColumn(
                lyrics = player.lyrics,
                currentIndex = player.currentLyricIndex,
                modifier = Modifier.weight(if (useFixed) 1f else 0.42f).fillMaxHeight(),
            )
            V5QueueColumn(
                player = player,
                playFocus = playFocus,
                modifier = if (useFixed) {
                    Modifier.width(460.dp).fillMaxHeight()
                } else {
                    Modifier.weight(0.32f).fillMaxHeight()
                },
            )
        }
    }
}

// ----------------------------------------------------------------------------
// LEFT COLUMN — eyebrow row, album art, meta, progress, transport
// ----------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun V5LeftColumn(
    song: Song,
    album: Album,
    player: PlayerState,
    playFocus: FocusRequester,
    lyricsFocus: FocusRequester,
    onOpenEq: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = LocalDinkType.current
    Column(
        modifier = modifier.padding(start = 20.dp, end = 12.dp, top = 24.dp, bottom = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NowPlayingPill()
            Text(
                text = "FROM ${shortSource(song.sourcePath).uppercase()}",
                style = type.monoSmall.copy(color = V5White50, fontSize = 11.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(24.dp))
        CoverArt(
            song = song,
            palette = album.palette,
            shape = album.shape,
            cornerRadius = 16.dp,
            modifier = Modifier
                .widthIn(max = 140.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(12.dp), clip = false),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "${album.tag} · ${song.bitrate}".uppercase(),
            style = type.monoSmall.copy(color = V5White50, fontSize = 10.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = song.title,
            style = type.nowPlayingTitle.copy(color = Color.White, fontSize = 28.sp, lineHeight = 32.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = song.artist,
            style = type.body.copy(color = V5White80, fontSize = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = album.title.takeIf { it.isNotBlank() }?.let { "$it${album.year?.let { y -> " ($y)" }.orEmpty()}" }.orEmpty(),
            style = type.body.copy(color = V5White50, fontSize = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(20.dp))
        V5ProgressBar(player = player)
        Spacer(Modifier.height(16.dp))
        V5TransportRow(
            player = player,
            playFocus = playFocus,
            lyricsFocus = lyricsFocus,
            onOpenEq = onOpenEq,
        )
    }
}

@Composable
private fun NowPlayingPill() {
    val type = LocalDinkType.current
    Row(
        modifier = Modifier
            .background(V5White08, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .shadow(elevation = 6.dp, shape = CircleShape, clip = false, ambientColor = V5GoodGreen, spotColor = V5GoodGreen)
                .background(V5GoodGreen, CircleShape),
        )
        Text(
            text = "NOW PLAYING",
            style = type.monoSmall.copy(color = V5White50, fontSize = 11.sp),
        )
    }
}

@Composable
private fun V5ProgressBar(player: PlayerState) {
    val type = LocalDinkType.current
    val progress = player.progress.coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(V5White08),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(4.dp)
                    .background(Color.White, RoundedCornerShape(2.dp)),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(player.timeSec.toInt()),
                style = type.mono.copy(color = V5White60, fontSize = 12.sp),
            )
            Text(
                text = "−" + formatTime((player.durationSec - player.timeSec.toInt()).coerceAtLeast(0)),
                style = type.mono.copy(color = V5White40, fontSize = 12.sp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun V5TransportRow(
    player: PlayerState,
    playFocus: FocusRequester,
    lyricsFocus: FocusRequester,
    onOpenEq: () -> Unit,
) {
    val railReq = LocalRailFocusRequester.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        V5TransportButton(
            icon = Icons.Outlined.Shuffle,
            active = player.shuffle,
            // Each press re-randomises the upcoming queue (and turns shuffle on),
            // keeping the current track playing — a "shuffle again" action.
            onClick = player::reshuffle,
            modifier = Modifier.focusProperties { left = railReq },
        )
        V5TransportButton(
            icon = Icons.Outlined.SkipPrevious,
            onClick = player::prev,
            // lyricsFocus historically targeted the Lyrics btn (now removed).
            // Keep it bound to a stable, always-present btn so any leftover
            // requestFocus from external callers still lands inside the row.
            modifier = Modifier.focusRequester(lyricsFocus),
        )
        V5PlayButton(
            isPlaying = player.isPlaying,
            onClick = player::togglePlayPause,
            modifier = Modifier.focusRequester(playFocus),
        )
        V5TransportButton(icon = Icons.Outlined.SkipNext, onClick = player::next)
        V5TransportButton(
            icon = if (player.repeatMode == PlayerRepeatMode.One) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
            active = player.repeatMode != PlayerRepeatMode.Off,
            onClick = player::cycleRepeatMode,
        )
        // Quick jump to the equalizer (Settings → Audio).
        V5TransportButton(icon = Icons.Outlined.GraphicEq, onClick = onOpenEq)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun V5TransportButton(
    icon: ImageVector,
    onClick: () -> Unit,
    active: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = V5White08,
            contentColor = if (active) Color.White else V5White60,
            focusedContentColor = Color.White,
        ),
        interactionSource = interaction,
        modifier = modifier.size(32.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (active || focused) Color.White else V5White60,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun V5PlayButton(isPlaying: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = tween(160, easing = LinearEasing),
        label = "v5-play-scale",
    )
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White,
            focusedContainerColor = Color.White,
            contentColor = V5BgBase,
            focusedContentColor = V5BgBase,
        ),
        interactionSource = interaction,
        modifier = modifier.size(48.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = V5BgBase,
                modifier = Modifier.size((20 * scale).dp),
            )
        }
    }
}

// ----------------------------------------------------------------------------
// MIDDLE COLUMN — lyrics with state hierarchy
// ----------------------------------------------------------------------------

@Composable
private fun V5LyricsColumn(
    lyrics: List<LyricLine>,
    currentIndex: Int,
    modifier: Modifier = Modifier,
) {
    val type = LocalDinkType.current
    val visible = remember(lyrics, currentIndex) { computeVisibleLyrics(lyrics, currentIndex) }
    val nonBlankTotal = remember(lyrics) { lyrics.count { it.text.isNotBlank() } }
    val nonBlankCurrentIdx = remember(visible) {
        visible.indexOfFirst { it.state == V5LineState.Current }.let { if (it < 0) 0 else it } + 1
    }

    BoxWithConstraints(modifier = modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
        // V5 spec sizes (84/36/...) target a ≥800dp column. Below that, scale
        // down proportionally so lyrics don't blow out of a narrow column.
        val scale = (maxWidth / 800.dp).coerceIn(0.35f, 1f)
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "LYRICS",
                    style = type.monoSmall.copy(color = V5White50, fontSize = 10.sp),
                    maxLines = 1,
                )
                // No counter when there are no lyrics — "1 / 0" reads as a glitch.
                if (nonBlankTotal > 0) {
                    Text(
                        text = "$nonBlankCurrentIdx / $nonBlankTotal",
                        style = type.monoSmall.copy(color = V5White40, fontSize = 10.sp, letterSpacing = 0.08.em),
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (visible.isEmpty()) {
                    Text(
                        text = if (lyrics.isEmpty()) "No lyrics available for this track" else "Lyrics loading…",
                        style = type.body.copy(color = V5White40),
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy((16 * scale).dp),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        visible.forEach { line ->
                            V5LyricLine(text = line.text, state = line.state, scale = scale)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun V5LyricLine(text: String, state: V5LineState, scale: Float = 1f) {
    val type = LocalDinkType.current
    val (baseSize, color) = when (state) {
        V5LineState.PastFar -> 26f to V5White08
        V5LineState.Past -> 32f to V5White16
        V5LineState.Current -> 84f to Color.White
        V5LineState.Next -> 36f to V5White28
        V5LineState.NextFar -> 28f to V5White14
    }
    val size = (baseSize * scale).sp
    val style = type.nowPlayingTitle.copy(
        fontSize = size,
        lineHeight = (baseSize * scale * if (state == V5LineState.Current) 1.02f else 1.1f).sp,
        color = color,
        letterSpacing = (-0.01).em,
        shadow = if (state == V5LineState.Current) {
            Shadow(color = V5HaloBlue, blurRadius = 40f)
        } else null,
    )
    Text(
        text = text,
        style = style,
        maxLines = if (state == V5LineState.Current) 3 else 2,
        overflow = TextOverflow.Ellipsis,
    )
}

private enum class V5LineState { PastFar, Past, Current, Next, NextFar }

private data class V5VisibleLine(val text: String, val state: V5LineState, val d: Int)

/**
 * Filter blank lyric lines (LRC files often carry timed blanks for instrumental
 * gaps), then map each non-blank line to a [V5LineState] based on offset from
 * the current line index. Returns only the 6-line window around current
 * (d ∈ -2..+3) so the layout has a fixed maximum size.
 */
private fun computeVisibleLyrics(lyrics: List<LyricLine>, currentIdx: Int): List<V5VisibleLine> {
    if (lyrics.isEmpty()) return emptyList()
    val nonBlank = lyrics.withIndex().filter { it.value.text.isNotBlank() }
    if (nonBlank.isEmpty()) return emptyList()
    // Find current position in the non-blank list. If currentIdx is on a blank
    // line, pick the last non-blank with index <= currentIdx.
    val safeCi = lyrics.indexOfFirst { it === lyrics[currentIdx.coerceIn(0, lyrics.lastIndex)] }
    val ci = nonBlank.indexOfFirst { it.index == safeCi }.let {
        if (it >= 0) it else nonBlank.indexOfLast { e -> e.index <= safeCi }.coerceAtLeast(0)
    }
    return nonBlank.mapIndexedNotNull { j, entry ->
        val d = j - ci
        if (d !in -2..3) return@mapIndexedNotNull null
        val state = when {
            d == 0 -> V5LineState.Current
            d == -1 -> V5LineState.Past
            d < -1 -> V5LineState.PastFar
            d == 1 -> V5LineState.Next
            else -> V5LineState.NextFar
        }
        V5VisibleLine(text = entry.value.text, state = state, d = d)
    }
}

// ----------------------------------------------------------------------------
// RIGHT COLUMN — play queue
// ----------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun V5QueueColumn(player: PlayerState, playFocus: FocusRequester, modifier: Modifier = Modifier) {
    val type = LocalDinkType.current
    val queue = player.queue
    val currentIdx = player.currentIndex
    // The queue view purges already-played tracks: show the current track at the top,
    // then everything still to come. (The underlying queue is untouched so prev() works.)
    val start = currentIdx.coerceAtLeast(0)
    val upcoming = if (currentIdx in queue.indices) queue.drop(start) else queue

    // Selecting an upcoming track makes it the CURRENT track, which re-derives
    // `upcoming` (drop(start)) — the focused row's position vanishes or now shows a
    // different song, so D-pad focus dies where the row used to be and the list keeps
    // its old scroll offset. On an explicit selection (not auto-advance), snap scroll
    // and focus to row 0, where the chosen track just landed.
    val listState = rememberLazyListState()
    val topRowFocus = remember { FocusRequester() }
    var pendingFocusTop by remember { mutableStateOf(false) }
    LaunchedEffect(currentIdx) {
        if (pendingFocusTop) {
            pendingFocusTop = false
            listState.scrollToItem(0)
            runCatching { topRowFocus.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .background(V5QueueBg)
            // Left hairline border distinguishes the queue panel from the lyrics column.
            .padding(start = 1.dp)
            .background(V5CardLine.copy(alpha = 0.0f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp),
        ) {
            Text(
                text = "UP NEXT",
                style = type.monoSmall.copy(color = V5White50, fontSize = 10.sp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Play Queue",
                style = type.cardTitle.copy(color = Color.White, fontSize = 18.sp),
            )
            Spacer(Modifier.height(4.dp))
            // "UP NEXT" counts what's still to come — the current track (row 1)
            // isn't "up next", so it's excluded from the count and total time.
            val upNext = if (upcoming.isEmpty()) upcoming else upcoming.drop(1)
            Text(
                text = "${upNext.size} TRACKS · ${formatTime(upNext.sumOf { it.durationSec })}",
                style = type.monoSmall.copy(color = V5White50, fontSize = 10.sp, letterSpacing = 0.08.em),
            )
            Spacer(Modifier.height(16.dp))
            if (upcoming.isEmpty()) {
                Text("Queue is empty", style = type.body.copy(color = V5White40))
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(upcoming.size) { i ->
                        val actualIdx = start + i
                        val song = upcoming[i]
                        val album = player.albumFor(song) ?: fallbackAlbumFor(song)
                        // First row is the current track; the rest are upcoming.
                        val state = if (i == 0) V5QueueRowState.Current else V5QueueRowState.Next
                        V5QueueRow(
                            index = i + 1,
                            song = song,
                            album = album,
                            state = state,
                            onClick = {
                                // Row 0 restarts the current track — indices don't shift,
                                // so no focus snap is queued (currentIdx never changes and
                                // the flag would fire on a later auto-advance instead).
                                if (actualIdx != currentIdx) pendingFocusTop = true
                                player.jumpTo(actualIdx)
                            },
                            // Left out of the queue returns to the transport controls,
                            // not the lyrics gap → nav drawer (which opened with no
                            // selection). Right stays cancelled (queue is the edge).
                            modifier = Modifier
                                .then(if (i == 0) Modifier.focusRequester(topRowFocus) else Modifier)
                                .focusProperties {
                                    left = playFocus
                                    right = FocusRequester.Cancel
                                },
                        )
                    }
                }
            }
        }
    }
}

private enum class V5QueueRowState { Past, Current, Next }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun V5QueueRow(
    index: Int,
    song: Song,
    album: Album,
    state: V5QueueRowState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }
    val titleColor = when (state) {
        V5QueueRowState.Past -> V5White32
        V5QueueRowState.Current -> Color.White
        V5QueueRowState.Next -> V5White80
    }
    val subColor = if (state == V5QueueRowState.Past) V5White32 else Color(0x8CFFFFFF) // 55%
    val numColor = when (state) {
        V5QueueRowState.Past -> V5White32
        V5QueueRowState.Current -> Color.White
        V5QueueRowState.Next -> V5White40
    }
    val bg = if (state == V5QueueRowState.Current) V5White08 else Color.Transparent

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = bg,
            focusedContainerColor = V5White16,
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (state == V5QueueRowState.Current) {
                    EqBars(color = Color.White)
                } else {
                    Text(
                        text = index.toString().padStart(2, '0'),
                        style = type.monoSmall.copy(color = numColor, fontSize = 11.sp),
                    )
                }
            }
            CoverArt(
                song = song,
                palette = album.palette,
                shape = album.shape,
                cornerRadius = 8.dp,
                modifier = Modifier.size(48.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = song.title,
                    style = type.body.copy(color = titleColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artist,
                    style = type.bodySmall.copy(color = subColor, fontSize = 11.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatTime(song.durationSec),
                style = type.monoSmall.copy(color = subColor, fontSize = 11.sp),
            )
        }
    }
}

/** 4-bar EQ animation for the currently-playing queue row. */
@Composable
private fun EqBars(color: Color) {
    val transition = rememberInfiniteTransition(label = "v5-eq")
    val phases = remember { listOf(0, 150, 300, 450) }
    Row(
        modifier = Modifier.height(12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        phases.forEach { delayMs ->
            val anim by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1000, delayMillis = delayMs, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "v5-eq-bar-$delayMs",
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height((12 * anim).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
        }
    }
}

// ----------------------------------------------------------------------------
// SHARED — background, empty state, helpers
// ----------------------------------------------------------------------------

/**
 * V5 background: low-chroma constant gradient, NOT derived from the album.
 * Two radial glows over a dark base. The old design used a blurred album-art
 * halo as the surface and was washing out the lyric text — fixed surface fixes
 * that.
 */
@Composable
private fun Modifier.v5Background(): Modifier = this
    .background(V5BgBase)
    .background(
        Brush.radialGradient(
            colors = listOf(V5BgGlow1.copy(alpha = 0.85f), Color.Transparent),
            radius = 900f,
            center = androidx.compose.ui.geometry.Offset(0.22f * 1920f, 0.30f * 1080f),
        ),
    )
    .background(
        Brush.radialGradient(
            colors = listOf(V5BgGlow2.copy(alpha = 0.7f), Color.Transparent),
            radius = 900f,
            center = androidx.compose.ui.geometry.Offset(0.80f * 1920f, 0.70f * 1080f),
        ),
    )

@Composable
private fun EmptyState() {
    val type = LocalDinkType.current
    Box(
        modifier = Modifier.fillMaxSize().v5Background(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("NOTHING PLAYING", style = type.monoSmall.copy(color = V5White40, fontSize = 11.sp))
            Text("Pick a song from Home", style = type.cardTitle.copy(color = V5White80))
        }
    }
}

private fun formatTime(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

/**
 * MediaStore-backed local songs carry the full filesystem path in [Song.sourcePath]
 * (`/storage/emulated/0/Music/coldsilver.mp3`). The eyebrow can't fit that, so
 * collapse to just the parent dir + filename.
 */
private fun shortSource(sourcePath: String): String {
    if (sourcePath.isBlank()) return "LOCAL"
    val parts = sourcePath.trimEnd('/').split('/').filter { it.isNotEmpty() }
    val tail = parts.takeLast(2).joinToString("/")
    return if (tail.length <= 48) tail else "…" + tail.takeLast(47)
}

private val FALLBACK_PALETTES = listOf(
    ArtPalette(0xFF6B7FFFL, 0xFF8B5CF6L, 0xFFEC4899L),
    ArtPalette(0xFF22D3EEL, 0xFF2563EBL, 0xFF7C3AEDL),
    ArtPalette(0xFFF59E0BL, 0xFFEF4444L, 0xFFEC4899L),
    ArtPalette(0xFF10B981L, 0xFF06B6D4L, 0xFF3B82F6L),
    ArtPalette(0xFFE11D48L, 0xFFEC4899L, 0xFF8B5CF6L),
    ArtPalette(0xFF0EA5E9L, 0xFF14B8A6L, 0xFF22C55EL),
)

private fun fallbackAlbumFor(song: Song): Album {
    val seed = song.id.hashCode() and 0x7FFFFFFF
    val palette = FALLBACK_PALETTES[seed % FALLBACK_PALETTES.size]
    val shapes = AlbumArtShape.values()
    val shape = shapes[(seed ushr 8) % shapes.size]
    return Album(
        id = "fallback-${song.id}",
        title = song.albumTitle.orEmpty(),
        artist = song.artist,
        year = null,
        palette = palette,
        shape = shape,
        tag = "LOCAL",
    )
}

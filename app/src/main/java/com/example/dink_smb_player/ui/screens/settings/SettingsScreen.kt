@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.dink_smb_player.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.DinkApplication
import com.example.dink_smb_player.LocalContentFocus
import com.example.dink_smb_player.LocalRailFocusRequester
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.lyrics.LyricPrefs
import com.example.dink_smb_player.player.EqEngine
import com.example.dink_smb_player.lyrics.LyricSettings
import com.example.dink_smb_player.lyrics.OnlineLyricProviders
import com.example.dink_smb_player.ui.components.GhostButton
import com.example.dink_smb_player.ui.components.Seg
import com.example.dink_smb_player.ui.components.SegOption
import com.example.dink_smb_player.ui.sound.LocalNavSounds
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType
import com.example.dink_smb_player.ui.theme.LocalThemeController
import com.example.dink_smb_player.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import com.example.dink_smb_player.ui.theme.LocalUiScale
import com.example.dink_smb_player.ui.theme.MaxUiScale
import com.example.dink_smb_player.ui.theme.MinUiScale
import com.example.dink_smb_player.ui.theme.UiScaleStep

/** Deep-link target for Settings tabs. Set [initialTab] before navigating to
 *  [com.example.dink_smb_player.nav.ScreenId.Settings] to open a specific tab
 *  (e.g. the EQ shortcut on Now Playing → Audio). Mirrors the screen-as-state nav
 *  pattern used elsewhere (LibraryDetailNav). One-shot: consumed on read. */
object SettingsNav {
    /** 0 = Display, 1 = Audio, 2 = Lyrics, 3 = Library. */
    var initialTab: Int? = null
    fun consume(): Int? {
        val t = initialTab
        initialTab = null
        return t
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val contentFocus = LocalContentFocus.current
    val railReq = LocalRailFocusRequester.current
    val uiScale = LocalUiScale.current
    val context = LocalContext.current
    val lyricPrefs = remember(context) { LyricPrefs(context.applicationContext) }
    val lyricToggles by lyricPrefs.toggles.collectAsState(initial = LyricSettings.snapshot())
    val scope = rememberCoroutineScope()
    val retag by LibraryRepository.retagProgress.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg0)
            .padding(horizontal = 64.dp, vertical = 48.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "SETTINGS", style = type.monoSmall.copy(color = palette.ink2))
            Text(text = "Settings", style = type.screenTitle.copy(color = palette.ink0))

            // Categories as D-pad tabs (switch on focus) instead of one long scroll.
            // Honour a deep-link target (e.g. the Now Playing EQ shortcut → Audio).
            var tab by remember { mutableStateOf(SettingsNav.consume() ?: 0) }
            val tabs = listOf("Display", "Audio", "Lyrics", "Library")
            // Up from content returns to the ACTIVE tab (not the spatially-nearest one,
            // which would switch category). Bound to whichever tab is currently selected.
            val activeTabFocus = remember { FocusRequester() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEachIndexed { i, label ->
                    SettingsTab(
                        label = label,
                        selected = tab == i,
                        onSelect = { tab = i },
                        // First tab owns content focus so a drawer commit lands on the
                        // tab row. ONLY the first tab routes Left to the rail; the rest
                        // must let Left fall through to the previous tab (spatial nav) —
                        // routing every tab's Left to the rail meant arrowing left between
                        // tabs opened the drawer instead of switching tab.
                        isFirst = i == 0,
                        focusRequester = if (i == 0) contentFocus else null,
                        // The selected tab is the Up-target for content below it.
                        activeTabRequester = if (tab == i) activeTabFocus else null,
                        railReq = railReq,
                    )
                }
            }

            val theme = LocalThemeController.current
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                when (tab) {
                    // ---- Display: UI scale + Theme ----
                    0 -> {
                        Column(modifier = Modifier.width(720.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(text = "UI scale", style = type.cardTitle.copy(color = palette.ink0))
                                Text(text = uiScale.percentLabel, style = type.monoValue.copy(color = palette.accent))
                            }
                            Text(
                                text = "Shrink or enlarge every on-screen element. Left / Right to adjust.",
                                style = type.body.copy(color = palette.ink2),
                            )
                            ScaleSlider(
                                fraction = (uiScale.scale - MinUiScale) / (MaxUiScale - MinUiScale),
                                onDecrease = { uiScale.nudge(-UiScaleStep) },
                                onIncrease = { uiScale.nudge(+UiScaleStep) },
                                // Top focusable of Display → Up returns to the active tab.
                                modifier = Modifier.fillMaxWidth().focusProperties { up = activeTabFocus },
                            )
                            GhostButton(
                                label = "Reset to default",
                                onClick = { uiScale.reset() },
                                modifier = Modifier.focusProperties { left = railReq },
                            )
                        }
                        Column(modifier = Modifier.width(720.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = "Theme", style = type.cardTitle.copy(color = palette.ink0))
                            Text(
                                text = "Light, dark, or follow the TV's system setting.",
                                style = type.body.copy(color = palette.ink2),
                            )
                            Seg(
                                selected = theme.mode,
                                options = listOf(
                                    SegOption(ThemeMode.System, "System"),
                                    SegOption(ThemeMode.Dark, "Dark"),
                                    SegOption(ThemeMode.Light, "Light"),
                                ),
                                onSelect = { theme.set(it) },
                                modifier = Modifier.focusProperties { left = railReq },
                            )
                        }
                        val navSounds = LocalNavSounds.current
                        Column(modifier = Modifier.width(720.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = "Sound", style = type.cardTitle.copy(color = palette.ink0))
                            Text(
                                text = "Play a short click as you move through menus and make selections.",
                                style = type.body.copy(color = palette.ink2),
                            )
                            LyricToggleRow(
                                label = "Navigation sounds",
                                checked = navSounds.enabled,
                                onToggle = { navSounds.set(it) },
                                modifier = Modifier.focusProperties { left = railReq },
                            )
                        }
                    }

                    // ---- Audio: graphic equalizer (Phase 11) ----
                    1 -> {
                        val eq by EqEngine.state.collectAsState()
                        Column(modifier = Modifier.width(720.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(text = "Equalizer", style = type.cardTitle.copy(color = palette.ink0))
                            Text(
                                text = "Shape playback across frequency bands. In Simple/Advanced, focus a bar " +
                                    "and press OK to grab it, Up / Down to adjust, Back to release. Applies to all playback.",
                                style = type.body.copy(color = palette.ink2),
                            )
                            if (!eq.available) {
                                Text(
                                    text = "The equalizer isn't available on this device.",
                                    style = type.body.copy(color = palette.ink3),
                                )
                            } else {
                                LyricToggleRow(
                                    label = "Equalizer",
                                    checked = eq.enabled,
                                    onToggle = { EqEngine.setEnabled(it) },
                                    // First focusable of Audio → Up returns to the active tab.
                                    modifier = Modifier.focusProperties { left = railReq; up = activeTabFocus },
                                )
                                // Three sections: Presets / Simple (3 macro bands) / Advanced (all bands).
                                var section by remember { mutableStateOf(0) }
                                Seg(
                                    selected = section,
                                    options = listOf(
                                        SegOption(0, "Presets"),
                                        SegOption(1, "Simple"),
                                        SegOption(2, "Advanced"),
                                    ),
                                    onSelect = { section = it },
                                    modifier = Modifier.focusProperties { left = railReq },
                                )
                                when (section) {
                                    0 -> {
                                        Seg(
                                            selected = eq.currentPreset,
                                            options = eq.presets.map { SegOption(it, it) },
                                            onSelect = { EqEngine.applyPreset(it) },
                                            modifier = Modifier.focusProperties { left = railReq },
                                        )
                                        GhostButton(
                                            label = "Reset equalizer",
                                            onClick = { EqEngine.reset() },
                                            modifier = Modifier.focusProperties { left = railReq },
                                        )
                                    }
                                    1 -> EqBandGroup(
                                        bars = simpleBars(eq),
                                        minMdB = eq.minMdB,
                                        maxMdB = eq.maxMdB,
                                        railReq = railReq,
                                    )
                                    else -> EqBandGroup(
                                        bars = advancedBars(eq),
                                        minMdB = eq.minMdB,
                                        maxMdB = eq.maxMdB,
                                        railReq = railReq,
                                    )
                                }
                            }
                        }
                    }

                    // ---- Lyrics: provider toggles ----
                    2 -> {
                        Column(modifier = Modifier.width(720.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = "Lyric sources", style = type.cardTitle.copy(color = palette.ink0))
                            Text(
                                text = "Online providers, tried in this order. Synced lyrics are preferred; " +
                                    "the search stops at the first match. Sidecar .lrc/.txt and embedded tags are always used.",
                                style = type.body.copy(color = palette.ink2),
                            )
                            OnlineLyricProviders.all.forEachIndexed { i, provider ->
                                LyricToggleRow(
                                    label = provider.label,
                                    checked = lyricToggles[provider.id] ?: provider.defaultEnabled,
                                    onToggle = { next ->
                                        LyricSettings.set(provider.id, next)
                                        scope.launch { lyricPrefs.setProvider(provider.id, next) }
                                    },
                                    // First row → Up returns to the active tab; lower rows
                                    // keep spatial Up to the row above.
                                    modifier = Modifier.focusProperties {
                                        left = railReq
                                        if (i == 0) up = activeTabFocus
                                    },
                                )
                            }
                        }
                    }

                    // ---- Library: maintenance ----
                    else -> {
                        Column(modifier = Modifier.width(720.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = "Library", style = type.cardTitle.copy(color = palette.ink0))
                            Text(
                                text = "Re-read embedded tags (title / artist / album / year) AND track length " +
                                    "from every imported SMB and cloud track and update the library in place. Use " +
                                    "this if names still show filenames or folders, or if track lengths read 0:00 " +
                                    "(library imported before lengths were read). Play counts are preserved. Runs " +
                                    "in the background; leaving this screen is fine.",
                                style = type.body.copy(color = palette.ink2),
                            )
                            val running = retag?.running == true
                            GhostButton(
                                label = when {
                                    running -> "Rescanning… ${retag?.done ?: 0} / ${retag?.total ?: 0}"
                                    else -> "Re-read tags from files"
                                },
                                onClick = {
                                    if (!running) {
                                        val appScope = (context.applicationContext as? DinkApplication)?.appScope
                                        if (appScope != null) {
                                            appScope.launch { LibraryRepository.retagAll(context.applicationContext) }
                                        } else {
                                            scope.launch { LibraryRepository.retagAll(context.applicationContext) }
                                        }
                                    }
                                },
                                // Top focusable of Library → Up returns to the active tab.
                                modifier = Modifier.focusProperties { left = railReq; up = activeTabFocus },
                            )
                            retag?.let { p ->
                                if (p.running) {
                                    val rate = "%.1f".format(p.ratePerSec)
                                    val eta = p.etaSeconds?.let { s -> " · ~%d:%02d left".format(s / 60, s % 60) } ?: ""
                                    Text(
                                        text = "$rate tracks/s$eta",
                                        style = type.monoSmall.copy(color = palette.ink3),
                                    )
                                } else {
                                    Text(
                                        text = "Last rescan: updated ${p.changed} of ${p.total} tracks.",
                                        style = type.monoSmall.copy(color = palette.ink3),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsTab(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    isFirst: Boolean,
    focusRequester: FocusRequester?,
    activeTabRequester: FocusRequester?,
    railReq: FocusRequester,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Switch tab as focus lands (arrow across to flip categories), like a TV tab row.
    Surface(
        onClick = onSelect,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) palette.bg3 else palette.bg1,
            focusedContainerColor = palette.bg2,
            contentColor = if (selected) palette.ink0 else palette.ink2,
            focusedContentColor = palette.ink0,
        ),
        interactionSource = interaction,
        modifier = Modifier
            .height(44.dp)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .let { if (activeTabRequester != null) it.focusRequester(activeTabRequester) else it }
            .focusProperties { if (isFirst) left = railReq }
            .onFocusChanged { if (it.isFocused) onSelect() },
    ) {
        Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = type.buttonLabel.copy(color = if (selected || focused) palette.ink0 else palette.ink2),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LyricToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) palette.bg2 else palette.bg1)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) palette.accent else palette.lineStrong,
                shape = RoundedCornerShape(14.dp),
            )
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> { onToggle(!checked); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = type.cardTitle.copy(color = palette.ink0))
        // Pill switch — accent track when on, knob slides via alignment.
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (checked) palette.accent else palette.bg0),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (checked) palette.ink0 else palette.ink3),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ScaleSlider(
    fraction: Float,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDinkPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val clamped = fraction.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(palette.bg2)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) palette.accent else palette.lineStrong,
                shape = RoundedCornerShape(24.dp),
            )
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onDecrease(); true }
                    Key.DirectionRight -> { onIncrease(); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Filled track. fillMaxWidth(0f) collapses to nothing, so guard the floor.
        Box(
            modifier = Modifier
                .padding(4.dp)
                .height(40.dp)
                .fillMaxWidth(clamped.coerceAtLeast(0.001f))
                .clip(RoundedCornerShape(20.dp))
                .background(palette.accentGradient),
        )
    }
}

/** Per-band gain step in millibels applied per D-pad press (1 dB). */
private const val EQ_STEP_MDB = 100

/** One adjustable bar: a label, its current gain, and how to nudge it. Lets the same
 *  slider render both Simple (macro Bass/Mid/Treble) and Advanced (per hardware band). */
private data class EqBar(val label: String, val levelMdB: Int, val onDelta: (Int) -> Unit)

/** Simple = 3 macro bands. Each groups the hardware bands in its frequency range and
 *  shifts them together, so it stays meaningful regardless of the device band count. */
private fun simpleBars(eq: EqEngine.EqState): List<EqBar> {
    val groups = listOf(
        "Bass" to eq.freqsHz.indices.filter { eq.freqsHz[it] < 250 },
        "Mid" to eq.freqsHz.indices.filter { eq.freqsHz[it] in 250..4000 },
        "Treble" to eq.freqsHz.indices.filter { eq.freqsHz[it] > 4000 },
    ).filter { it.second.isNotEmpty() }
    return groups.map { (label, idxs) ->
        val rep = eq.levelsMdB.getOrElse(idxs.first()) { 0 }
        // Nudge every band in the group by the same delta (preserves its shape).
        EqBar(label, rep) { d -> idxs.forEach { EqEngine.setBandLevel(it, eq.levelsMdB[it] + d) } }
    }
}

/** Advanced = every hardware band, one bar each. */
private fun advancedBars(eq: EqEngine.EqState): List<EqBar> =
    eq.freqsHz.mapIndexed { i, f ->
        EqBar(freqLabel(f), eq.levelsMdB.getOrElse(i) { 0 }) { d ->
            EqEngine.setBandLevel(i, eq.levelsMdB[i] + d)
        }
    }

@Composable
private fun EqBandGroup(
    bars: List<EqBar>,
    minMdB: Int,
    maxMdB: Int,
    railReq: FocusRequester,
) {
    // Select-to-enter: a bar must be "grabbed" with OK before Up/Down adjust it, and Back
    // releases it. Held at the group level so a single BackHandler can release whichever
    // bar is grabbed — Up/Down otherwise hijacked plain navigation, trapping focus.
    var editingIndex by remember(bars.size) { mutableStateOf(-1) }
    BackHandler(enabled = editingIndex != -1) { editingIndex = -1 }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        bars.forEachIndexed { i, bar ->
            EqBandSlider(
                label = bar.label,
                levelMdB = bar.levelMdB,
                minMdB = minMdB,
                maxMdB = maxMdB,
                editing = editingIndex == i,
                onToggleEdit = { editingIndex = if (editingIndex == i) -1 else i },
                onDelta = bar.onDelta,
                onLoseFocus = { if (editingIndex == i) editingIndex = -1 },
                // Only the leftmost bar routes Left → rail; interior bars keep spatial
                // Left to the bar on their left (the multi-column focus rule).
                modifier = if (i == 0) Modifier.focusProperties { left = railReq } else Modifier,
            )
        }
    }
}

@Composable
private fun EqBandSlider(
    label: String,
    levelMdB: Int,
    minMdB: Int,
    maxMdB: Int,
    editing: Boolean,
    onToggleEdit: () -> Unit,
    onDelta: (Int) -> Unit,
    onLoseFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val span = (maxMdB - minMdB).coerceAtLeast(1)
    val frac = ((levelMdB - minMdB).toFloat() / span).coerceIn(0f, 1f)
    val active = editing || focused

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = dbLabel(levelMdB),
            style = type.monoSmall.copy(color = if (editing) palette.accent else if (focused) palette.ink1 else palette.ink2),
            maxLines = 1,
        )
        Box(
            modifier = modifier
                .width(44.dp)
                .height(160.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(palette.bg2)
                .border(
                    width = if (editing) 3.dp else if (focused) 2.dp else 1.dp,
                    color = if (active) palette.accent else palette.lineStrong,
                    shape = RoundedCornerShape(20.dp),
                )
                .onFocusChanged { if (!it.isFocused) onLoseFocus() }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        // OK grabs / releases the bar.
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onToggleEdit(); true }
                        // Up/Down only adjust while grabbed; otherwise pass through to navigate.
                        Key.DirectionUp -> if (editing) { onDelta(+EQ_STEP_MDB); true } else false
                        Key.DirectionDown -> if (editing) { onDelta(-EQ_STEP_MDB); true } else false
                        // While grabbed, swallow Left/Right so the bar can't be left mid-edit.
                        Key.DirectionLeft, Key.DirectionRight -> editing
                        else -> false
                    }
                }
                .focusable(interactionSource = interaction),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // Fill grows from the bottom by the gain fraction. Floor the height so a
            // band at minimum still shows a sliver instead of vanishing.
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(frac.coerceAtLeast(0.02f))
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.accentGradient),
            )
        }
        Text(
            text = label,
            style = type.monoSmall.copy(color = if (active) palette.ink1 else palette.ink3),
            maxLines = 1,
        )
    }
}

private fun dbLabel(mdB: Int): String {
    val db = mdB / 100
    return when {
        db > 0 -> "+$db"
        else -> "$db"
    }
}

private fun freqLabel(hz: Int): String =
    if (hz >= 1000) {
        val k = hz / 1000f
        if (k % 1f == 0f) "${k.toInt()}k" else "%.1fk".format(k)
    } else {
        "$hz"
    }

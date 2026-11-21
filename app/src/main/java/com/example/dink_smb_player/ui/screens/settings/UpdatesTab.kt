@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.dink_smb_player.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.BuildConfig
import com.example.dink_smb_player.ui.components.GhostButton
import com.example.dink_smb_player.ui.components.GradientButton
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType
import com.example.dink_smb_player.update.MarkdownLite
import com.example.dink_smb_player.update.MdBlock
import com.example.dink_smb_player.update.MdSpan
import com.example.dink_smb_player.update.ReleaseInfo
import com.example.dink_smb_player.update.Semver
import com.example.dink_smb_player.update.UpdateRepository
import com.example.dink_smb_player.update.UpdateState
import kotlinx.coroutines.launch

/** Settings -> Updates: version/status, check + install (sideload flavor), and the changelog reader. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun UpdatesTab(activeTabFocus: FocusRequester, railReq: FocusRequester) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by UpdateRepository.state.collectAsState()
    val releases by UpdateRepository.releases.collectAsState()
    val notice by UpdateRepository.notice.collectAsState()
    val prefs = remember(context) { UpdateRepository.prefs(context) }
    val autoCheck by prefs.autoCheck.collectAsState(initial = BuildConfig.SELF_UPDATE)
    val selfUpdate = BuildConfig.SELF_UPDATE

    // Play flavor has no background check: load the changelog when the tab opens.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        UpdateRepository.attach(context)
        UpdateRepository.onUpdatesTabShown()
        if (!selfUpdate && releases.isEmpty()) UpdateRepository.checkAsync()
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(modifier = Modifier.width(720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Updates", style = type.cardTitle.copy(color = palette.ink0))
            Text(
                text = "Installed version ${BuildConfig.VERSION_NAME}",
                style = type.monoValue.copy(color = palette.accent),
            )
            Text(text = statusLine(state, selfUpdate), style = type.body.copy(color = palette.ink2))
            notice?.let { Text(text = it, style = type.body.copy(color = palette.ink1)) }

            var topUsed = false
            fun topMod(): Modifier {
                val m = Modifier.focusProperties { left = railReq; if (!topUsed) up = activeTabFocus }
                topUsed = true
                return m
            }

            if (selfUpdate) {
                LyricToggleRow(
                    label = "Automatically check for updates",
                    checked = autoCheck,
                    onToggle = { next -> scope.launch { UpdateRepository.setAutoCheck(context, next) } },
                    modifier = topMod(),
                )
            } else {
                Text(
                    text = "Updates are delivered through Google Play.",
                    style = type.cardTitle.copy(color = palette.ink0),
                )
            }

            val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
            GhostButton(
                label = if (state is UpdateState.Checking) "Checking…"
                else if (selfUpdate) "Check for updates" else "Refresh changelog",
                onClick = { if (!busy) UpdateRepository.checkAsync() },
                modifier = topMod(),
            )

            if (selfUpdate) {
                when (val s = state) {
                    is UpdateState.Available -> {
                        GradientButton(
                            label = "Download & install v${s.release.version}",
                            onClick = { UpdateRepository.downloadAndInstall() },
                            modifier = Modifier.focusProperties { left = railReq },
                        )
                        Text(
                            text = "Playback will stop while installing.",
                            style = type.monoSmall.copy(color = palette.ink3),
                        )
                    }
                    is UpdateState.Downloading -> Text(
                        text = "Downloading v${s.release.version}… ${(s.progress * 100).toInt()}%",
                        style = type.monoValue.copy(color = palette.accent),
                    )
                    is UpdateState.ReadyToInstall -> {
                        GradientButton(
                            label = "Install v${s.release.version}",
                            onClick = { UpdateRepository.installDownloaded() },
                            modifier = Modifier.focusProperties { left = railReq },
                        )
                        Text(
                            text = "Playback will stop while installing.",
                            style = type.monoSmall.copy(color = palette.ink3),
                        )
                    }
                    is UpdateState.Error -> if (releases.any { Semver.isNewer(it.tag, BuildConfig.VERSION_NAME) && it.apk != null }) {
                        GradientButton(
                            label = "Download & install again",
                            onClick = { UpdateRepository.downloadAndInstall() },
                            modifier = Modifier.focusProperties { left = railReq },
                        )
                    }
                    else -> Unit
                }
            }
        }

        Text(text = "Changelog", style = type.cardTitle.copy(color = palette.ink0))
        if (releases.isEmpty()) {
            Text(
                text = if (state is UpdateState.Checking) "Loading…" else "No release notes available yet. Check your connection and refresh.",
                style = type.body.copy(color = palette.ink2),
            )
        } else {
            Changelog(releases = releases, railReq = railReq)
        }
    }
}

private fun statusLine(state: UpdateState, selfUpdate: Boolean): String = when (state) {
    UpdateState.Idle -> if (selfUpdate) "Press Check for updates to look for a new version." else "Updates come from Google Play."
    UpdateState.Checking -> "Checking GitHub for new releases…"
    UpdateState.UpToDate -> "You're up to date."
    is UpdateState.Available -> "Version ${state.release.version} is available."
    is UpdateState.Downloading -> "Downloading version ${state.release.version}…"
    is UpdateState.ReadyToInstall -> "Version ${state.release.version} is downloaded and ready to install."
    is UpdateState.Installing -> "Installing version ${state.release.version}… confirm on the system prompt."
    is UpdateState.Error -> state.message
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Changelog(releases: List<ReleaseInfo>, railReq: FocusRequester) {
    val palette = LocalDinkPalette.current
    var selectedTag by remember { mutableStateOf(releases.first().tag) }
    val selected = releases.firstOrNull { it.tag == selectedTag } ?: releases.first()

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        // Release list: own scroll box so 20 rows don't push the pane off-screen.
        Column(
            modifier = Modifier
                .width(280.dp)
                .height(420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            releases.forEach { r ->
                ReleaseRow(
                    release = r,
                    installed = Semver.compare(r.tag, BuildConfig.VERSION_NAME) == 0,
                    selected = r.tag == selected.tag,
                    onFocus = { selectedTag = r.tag },
                    modifier = Modifier.focusProperties { left = railReq },
                )
            }
        }
        ReadingPane(release = selected, modifier = Modifier.weight(1f).height(420.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ReleaseRow(
    release: ReleaseInfo,
    installed: Boolean,
    selected: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onFocus,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) palette.bg3 else palette.bg1,
            focusedContainerColor = palette.bg2,
            contentColor = palette.ink1,
            focusedContentColor = palette.ink0,
        ),
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .onFocusChanged { if (it.isFocused) onFocus() },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "v${release.version}" + if (installed) "  (installed)" else "",
                    style = type.buttonLabel.copy(color = if (focused || selected) palette.ink0 else palette.ink1),
                )
                Text(text = release.dateLabel, style = type.monoSmall.copy(color = palette.ink3))
            }
        }
    }
}

/** Focusable, D-pad scrollable markdown view: Up/Down scroll the text while there is more to
 *  scroll in that direction, then fall through so focus can leave the pane. */
@Composable
private fun ReadingPane(release: ReleaseInfo, modifier: Modifier = Modifier) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val stepPx = with(LocalDensity.current) { 96.dp.toPx() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val blocks = remember(release.tag) { MarkdownLite.parse(release.body) }
    androidx.compose.runtime.LaunchedEffect(release.tag) { scroll.scrollTo(0) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.bg1)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) palette.accent else palette.lineStrong,
                shape = RoundedCornerShape(14.dp),
            )
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> if (scroll.canScrollForward) {
                        scope.launch { scroll.animateScrollBy(stepPx) }; true
                    } else false
                    Key.DirectionUp -> if (scroll.canScrollBackward) {
                        scope.launch { scroll.animateScrollBy(-stepPx) }; true
                    } else false
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .verticalScroll(scroll)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "v${release.version}  ·  ${release.dateLabel}",
            style = type.monoSmall.copy(color = palette.ink3),
        )
        if (blocks.isEmpty()) {
            Text(text = "No release notes.", style = type.body.copy(color = palette.ink2))
        }
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    text = styled(b.spans),
                    style = (if (b.level <= 2) type.cardTitle else type.buttonLabel).copy(color = palette.ink0),
                    modifier = Modifier.padding(top = 6.dp),
                )
                is MdBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = "•", style = type.body.copy(color = palette.accent))
                    Text(text = styled(b.spans), style = type.body.copy(color = palette.ink1))
                }
                is MdBlock.Paragraph -> Text(text = styled(b.spans), style = type.body.copy(color = palette.ink1))
            }
        }
    }
}

@Composable
private fun styled(spans: List<MdSpan>): AnnotatedString {
    val palette = LocalDinkPalette.current
    return buildAnnotatedString {
        spans.forEach { s ->
            val style = SpanStyle(
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontFamily = if (s.code) FontFamily.Monospace else null,
                background = if (s.code) palette.bg3 else androidx.compose.ui.graphics.Color.Unspecified,
            )
            withStyle(style) { append(s.text) }
        }
    }
}

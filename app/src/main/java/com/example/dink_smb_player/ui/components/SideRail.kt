@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.dink_smb_player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.SignalWifiOff
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings as AndroidSettings
import java.net.Inet4Address
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.nav.RailGroup
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType

private val ItemHeight: Dp = 44.dp
private val IconBoxSize: Dp = 32.dp
private val ClosedDrawerWidth: Dp = 56.dp
private val OpenDrawerWidth: Dp = 260.dp

/**
 * Drawer content for the app's persistent NavigationDrawer. Expand/collapse and
 * width are owned by the wrapping NavigationDrawer in `DinkApp`; this composable
 * only renders the rail's content for the given [drawerValue].
 *
 * Focus-driven nav: each item calls [onSelect] on focus gain so D-pad Up/Down
 * swaps the screen without leaving the drawer. Press Select (or D-pad Right) to
 * commit — that triggers [onCommit] which moves focus into the screen content,
 * and NavigationDrawer collapses the drawer when its focus leaves.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DinkDrawerContent(
    drawerValue: DrawerValue,
    current: ScreenId,
    onSelect: (ScreenId) -> Unit,
    onCommit: (ScreenId) -> Unit,
    currentItemFocusRequester: FocusRequester,
) {
    val palette = LocalDinkPalette.current
    val expanded = drawerValue == DrawerValue.Open

    // Tracks the most recently focused rail item *while the drawer is open*.
    // Used to distinguish two cases in RailItem.onFocusChanged:
    //   (a) D-pad nav inside the drawer (lastFocusedItem != null, different
    //       screen) → fire onSelect to swap the screen.
    //   (b) The drawer regaining focus because content unmounted / lost focus
    //       (lastFocusedItem == null after a Closed→Open transition) → DON'T
    //       fire onSelect, otherwise an in-screen button click that navigates
    //       elsewhere will be clobbered by Home (or last drawer item) firing.
    // Cleared whenever the drawer transitions to Closed.
    var lastFocusedItem by remember { mutableStateOf<ScreenId?>(null) }
    var lastFocusChangeMs by remember { mutableStateOf(0L) }
    LaunchedEffect(drawerValue) {
        if (drawerValue == DrawerValue.Closed) {
            lastFocusedItem = null
            lastFocusChangeMs = 0L
        }
    }
    // Distinguish real D-pad nav (≥60ms between item-focus events — user
    // physical key cadence) from Compose's spatial-search noise that fires
    // when content unmounts (multiple focus events within the same frame,
    // <16ms apart). Pure timing — no async coroutines = no race window.
    val gatedSelect: (ScreenId) -> Unit = { screen ->
        val now = System.currentTimeMillis()
        val prev = lastFocusedItem
        val delta = now - lastFocusChangeMs
        lastFocusedItem = screen
        lastFocusChangeMs = now
        if (prev != null && prev != screen && delta > 60) onSelect(screen)
    }

    Column(
        modifier = Modifier
            .width(if (expanded) OpenDrawerWidth else ClosedDrawerWidth)
            .fillMaxHeight()
            .background(palette.bg1)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        Brand(expanded = expanded)
        Spacer(Modifier.height(8.dp))

        Section(
            group = RailGroup.Top,
            current = current,
            expanded = expanded,
            onSelect = gatedSelect,
            onCommit = onCommit,
            currentItemFocusRequester = currentItemFocusRequester,
        )
        Divider(expanded = expanded)
        Section(
            group = RailGroup.Library,
            current = current,
            expanded = expanded,
            onSelect = gatedSelect,
            onCommit = onCommit,
            currentItemFocusRequester = currentItemFocusRequester,
            eyebrow = "LIBRARY",
        )
        Divider(expanded = expanded)
        Section(
            group = RailGroup.Sources,
            current = current,
            expanded = expanded,
            onSelect = gatedSelect,
            onCommit = onCommit,
            currentItemFocusRequester = currentItemFocusRequester,
            eyebrow = "SOURCES",
        )

        Spacer(Modifier.height(8.dp))
        Section(
            group = RailGroup.Bottom,
            current = current,
            expanded = expanded,
            onSelect = gatedSelect,
            onCommit = onCommit,
            currentItemFocusRequester = currentItemFocusRequester,
        )

        if (expanded) {
            Spacer(Modifier.height(16.dp))
            val net = rememberNetworkStatus()
            DeviceChip(text = net.label, icon = net.icon, online = net.online)
        }
    }
}

@Composable
private fun Section(
    group: RailGroup,
    current: ScreenId,
    expanded: Boolean,
    onSelect: (ScreenId) -> Unit,
    onCommit: (ScreenId) -> Unit,
    eyebrow: String? = null,
    currentItemFocusRequester: FocusRequester? = null,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val entries = ScreenId.railEntries.filter { it.group == group }
    if (entries.isEmpty()) return

    if (expanded && eyebrow != null) {
        Text(
            text = eyebrow,
            style = type.monoSmall.copy(color = palette.ink3),
            modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 8.dp),
        )
    }
    entries.forEach { screen ->
        val isCurrent = screen == current
        RailItem(
            screen = screen,
            active = isCurrent,
            expanded = expanded,
            // ALL rail items are commit-only (open with Enter / D-pad Right), matching
            // what NowPlaying always did. Preview-on-focus (swap the content pane as you
            // arrow past an item) was inconsistent — only NowPlaying was exempt — and on
            // NowPlaying it reliably wedged the rail (the focus engine refused to traverse
            // DOWN onto a preview-navigated item and dropped a press). Commit-only makes
            // every menu behave the same: arrowing the rail just moves focus; the screen
            // changes only when you select.
            navOnFocus = false,
            onSelect = { onSelect(screen) },
            onCommit = { onCommit(screen) },
            // Attach the shared rail requester to whichever item matches the
            // current screen. Left-from-content lands here; drawer reopen lands
            // here. Keeps highlight + focus in sync with nav.current.
            focusRequester = if (isCurrent) currentItemFocusRequester else null,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RailItem(
    screen: ScreenId,
    active: Boolean,
    expanded: Boolean,
    onSelect: () -> Unit,
    onCommit: () -> Unit,
    navOnFocus: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val itemShape = RoundedCornerShape(14.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(22.dp)
                .background(
                    if (active) palette.accent else Color.Transparent,
                    RoundedCornerShape(2.dp),
                ),
        )
        Spacer(Modifier.width(4.dp))

        Surface(
            onClick = onCommit,
            shape = ClickableSurfaceDefaults.shape(shape = itemShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (active) palette.bg3 else Color.Transparent,
                focusedContainerColor = palette.bg2,
                contentColor = palette.ink0,
                focusedContentColor = palette.ink0,
            ),
            interactionSource = interaction,
            modifier = Modifier
                .testTag("rail_${screen.key}")
                .height(ItemHeight)
                .weight(1f, fill = expanded)
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .onFocusChanged { state -> if (state.isFocused && navOnFocus) onSelect() }
                .focusProperties { left = FocusRequester.Cancel },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconBadge(icon = screen.icon, active = active, focused = focused)
                if (expanded) {
                    Text(
                        text = screen.displayName,
                        style = type.buttonLabel.copy(
                            color = if (active) palette.ink0 else palette.ink1,
                        ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun IconBadge(
    icon: ImageVector,
    active: Boolean,
    focused: Boolean,
) {
    val palette = LocalDinkPalette.current
    Box(
        modifier = Modifier
            .size(IconBoxSize)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    active -> palette.accent.copy(alpha = 0.18f)
                    focused -> palette.bg3
                    else -> Color.Transparent
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) palette.accent else palette.ink1,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun Brand(expanded: Boolean) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    Box(modifier = Modifier.padding(start = 14.dp, end = 8.dp).height(28.dp)) {
        Text(
            text = if (expanded) "Dink" else "D",
            style = type.cardTitle.copy(color = palette.ink0),
        )
    }
}

@Composable
private fun Divider(expanded: Boolean) {
    val palette = LocalDinkPalette.current
    Spacer(
        modifier = Modifier
            .padding(horizontal = if (expanded) 14.dp else 10.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.line),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DeviceChip(text: String, icon: ImageVector, online: Boolean) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    Row(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .background(palette.bg0, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (online) palette.good else palette.ink3,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = type.monoSmall.copy(color = palette.ink2),
        )
    }
}

/** Real device + LAN readout for the rail chip: device name · IPv4 · link type. Replaces
 *  the old hardcoded "Living Room · 192.168.1.18 · WI-FI" mock. Read once per drawer
 *  composition from ConnectivityManager (needs ACCESS_NETWORK_STATE, already declared). */
private data class NetworkStatus(val label: String, val icon: ImageVector, val online: Boolean)

@Composable
private fun rememberNetworkStatus(): NetworkStatus {
    val context = LocalContext.current
    return remember {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val ip = network?.let { cm.getLinkProperties(it) }
            ?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
        val (kind, icon) = when {
            caps == null -> "Offline" to Icons.Outlined.SignalWifiOff
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet" to Icons.Outlined.Lan
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi" to Icons.Outlined.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular" to Icons.Outlined.Wifi
            else -> "Online" to Icons.Outlined.Wifi
        }
        val name = runCatching {
            AndroidSettings.Global.getString(context.contentResolver, "device_name")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
        val online = ip != null && caps != null
        val label = listOfNotNull(name, ip, kind).joinToString(" · ")
        NetworkStatus(label = label, icon = icon, online = online)
    }
}

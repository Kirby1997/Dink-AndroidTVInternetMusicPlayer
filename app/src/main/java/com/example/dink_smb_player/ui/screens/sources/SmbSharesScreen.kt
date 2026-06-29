@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.example.dink_smb_player.ui.screens.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.LocalContentFocus
import com.example.dink_smb_player.LocalRailFocusRequester
import com.example.dink_smb_player.data.SharesLibrary
import com.example.dink_smb_player.data.model.ConnectionStatus
import com.example.dink_smb_player.data.model.SmbShare
import com.example.dink_smb_player.data.prefs.SharePrefs
import com.example.dink_smb_player.data.source.smb.SmbConnectionRegistry
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.player.PlayerState
import com.example.dink_smb_player.ui.components.GradientButton
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType
import kotlinx.coroutines.launch

@Composable
fun SmbSharesScreen(
    player: PlayerState,
    onNavigate: (ScreenId) -> Unit,
    onToast: (String) -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val railRequester = LocalRailFocusRequester.current
    val contentFocus = LocalContentFocus.current
    val context = LocalContext.current
    val sharePrefs = remember(context) { SharePrefs(context.applicationContext) }
    val shares by sharePrefs.shares.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    // Push the live SharePrefs view into the SmbDataSource registry. Without this
    // the data source can't resolve `?sid=` back to a share config.
    LaunchedEffect(shares) { SmbConnectionRegistry.update(shares) }

    // Grab content focus on entry, else focus is unbound on arrival and Compose
    // hands it to the rail (drawer pops open / focus stranded at the rail top).
    // contentFocus is bound to the "Add share" button below.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { contentFocus.requestFocus() }
    }

    // Track counts come from the persisted per-share total (updated on import), not
    // a live flat walk — browsing is lazy now. `importing` drives the card pill.
    val importing = SharesLibrary.importingShares
    val errors = SharesLibrary.errorsByShare

    val totalTracks = shares.sumOf { it.trackCount }
    val totalBytes = shares.sumOf { it.sizeBytes }
    val connectedCount = shares.count { it.status == ConnectionStatus.Connected }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "SMB Shares",
                    style = type.sectionTitle.copy(color = palette.ink0),
                    maxLines = 1,
                )
                Text(
                    text = if (shares.isEmpty()) {
                        "No shares connected. Add one to stream music from your LAN NAS."
                    } else {
                        "$connectedCount connected · ${formatBytes(totalBytes)} indexed · $totalTracks tracks"
                    },
                    style = type.bodySmall.copy(color = palette.ink2),
                    maxLines = 2,
                )
            }
            GradientButton(
                label = "Add share",
                leadingIcon = Icons.Filled.Add,
                onClick = { onNavigate(ScreenId.AddShareWizard) },
                height = 48.dp,
                modifier = Modifier
                    .focusRequester(contentFocus)
                    .focusProperties { left = railRequester },
            )
        }

        StatsStrip(
            connected = connectedCount,
            shares = shares.size,
            tracks = totalTracks,
            sizeBytes = totalBytes,
        )

        if (shares.isEmpty()) {
            EmptyState()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(shares) { idx, share ->
                    ShareCard(
                        share = share,
                        trackCount = share.trackCount,
                        syncing = importing[share.id] == true,
                        importedSoFar = SharesLibrary.importProgress[share.id],
                        error = errors[share.id],
                        // Leftmost column (every 3rd card) routes Left → rail; interior
                        // columns keep spatial Left to the card on their left. `idx == 0`
                        // alone stranded the leftmost cards of rows 2+.
                        modifier = if (idx % 3 == 0) Modifier.focusProperties { left = railRequester } else Modifier,
                        onOpen = {
                            // Open the per-share folder browser — all share ops (Import,
                            // Monitor, Re-import, Delete) and playback live there now, so the
                            // card stays a single uncramped target.
                            SharesLibrary.activeBrowseShareId = share.id
                            onNavigate(ScreenId.SmbBrowse)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsStrip(connected: Int, shares: Int, tracks: Int, sizeBytes: Long) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.bg1, RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stat("Connected", "$connected / $shares")
        Stat("Tracks", tracks.toString())
        Stat("Storage", formatBytes(sizeBytes))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = type.monoSmall.copy(color = palette.ink3))
        Text(value, style = type.cardTitle.copy(color = palette.ink0))
    }
}

@Composable
private fun ShareCard(
    share: SmbShare,
    trackCount: Int,
    syncing: Boolean,
    importedSoFar: Int?,
    error: String?,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current

    // Non-focusable container: the card itself is no longer a single clickable
    // Surface (which trapped focus and made the inner buttons unreachable). Each
    // button below is independently focusable, so D-pad can land on Open / Sync /
    // Delete. `modifier` (left=rail for the first card) is routed to Open so the
    // grid's top-left control still bounces Left into the rail.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.bg1)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Storage,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = share.name,
                style = type.cardTitle.copy(color = palette.ink0),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusPill(share.status, syncing)
        }
        Text(
            text = "${share.host}:${share.port} · ${share.shareName}",
            style = type.monoSmall.copy(color = palette.ink3),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = when {
                error != null -> error
                syncing && importedSoFar != null -> "Importing… ${"%,d".format(importedSoFar)} tracks"
                syncing -> "Importing…"
                else -> "$trackCount tracks · ${lastSyncLabel(share.lastSyncMs)}"
            },
            style = type.bodySmall.copy(color = if (error != null) palette.warn else palette.ink2),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton(label = "Open", icon = Icons.Filled.PlayArrow, onClick = onOpen, modifier = modifier)
        }
    }
}

@Composable
private fun CardButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    var focused by remember { mutableStateOf(false) }
    val ink = if (focused) Color.Black else palette.ink0
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) palette.accent else palette.bg0)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        Text(label, style = type.buttonLabel.copy(color = ink))
    }
}

@Composable
private fun Spacer() {
    Box(modifier = Modifier.height(2.dp))
}

@Composable
private fun StatusPill(status: ConnectionStatus, syncing: Boolean) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val (label, dot) = when {
        syncing -> "Syncing" to palette.accent
        status == ConnectionStatus.Connected -> "Online" to palette.good
        status == ConnectionStatus.Offline -> "Offline" to palette.ink3
        status == ConnectionStatus.Expired -> "Expired" to palette.warn
        else -> status.name to palette.ink2
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(palette.bg0, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).background(dot, CircleShape))
        Text(label, style = type.monoSmall.copy(color = palette.ink2))
    }
}

@Composable
private fun EmptyState() {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg1, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Storage,
                contentDescription = null,
                tint = palette.ink3,
                modifier = Modifier.size(40.dp),
            )
            Text("No SMB shares yet", style = type.cardTitle.copy(color = palette.ink1))
            Text(
                text = "Add one to stream music from your NAS — credentials are stored encrypted on this device.",
                style = type.bodySmall.copy(color = palette.ink3),
            )
        }
    }
}

private fun lastSyncLabel(lastSyncMs: Long?): String {
    if (lastSyncMs == null || lastSyncMs <= 0L) return "never synced"
    val mins = (System.currentTimeMillis() - lastSyncMs) / 60_000L
    return when {
        mins < 1 -> "synced just now"
        mins < 60 -> "synced ${mins}m ago"
        mins < 1440 -> "synced ${mins / 60}h ago"
        else -> "synced ${mins / 1440}d ago"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
    return "%.1f %s".format(v, units[i])
}

@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.example.dink_smb_player.ui.screens.sources

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.LocalContentFocus
import com.example.dink_smb_player.LocalRailFocusRequester
import com.example.dink_smb_player.data.CloudLibrary
import com.example.dink_smb_player.data.model.CloudFolderRef
import com.example.dink_smb_player.data.prefs.SharePrefs
import com.example.dink_smb_player.data.source.cloud.CloudBrowser
import com.example.dink_smb_player.data.source.cloud.CloudConnectionRegistry
import com.example.dink_smb_player.data.source.cloud.GoogleDriveClient
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.player.PlayerState
import com.example.dink_smb_player.ui.components.GhostButton
import com.example.dink_smb_player.ui.components.GradientButton
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Per-provider cloud **folder browser**, mirror of [SmbBrowseScreen]. Lists one Drive
 * folder at a time via [CloudBrowser] (no whole-account walk). Per folder the user can
 * Import (recursive, index-only — streamed, never downloaded) or toggle Monitor.
 * Navigation is by folder id; a breadcrumb of names drives display + import scoping.
 */
@Composable
fun CloudBrowseScreen(
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
    val providers by sharePrefs.providers.collectAsState(initial = emptyList())
    val activeId = CloudLibrary.activeBrowseProviderId
    val provider = remember(activeId, providers) { providers.firstOrNull { it.id == activeId } }

    // Navigation stack of folder refs (id + name breadcrumb). Root = My Drive.
    val rootRef = remember { CloudFolderRef(GoogleDriveClient.ROOT_ID, "") }
    var stack by remember(provider?.id) { mutableStateOf(listOf(rootRef)) }
    val current = stack.last()

    var entries by remember(provider?.id) { mutableStateOf<List<CloudBrowser.Entry>>(emptyList()) }
    var loading by remember(provider?.id) { mutableStateOf(false) }
    var error by remember(provider?.id) { mutableStateOf<String?>(null) }

    val importing = provider?.let { CloudLibrary.importingProviders[it.id] == true } ?: false
    val listFocus = remember { FocusRequester() }

    LaunchedEffect(provider?.id, current.id) {
        val p = provider ?: return@LaunchedEffect
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            val token = CloudConnectionRegistry.validAccessToken(p.id)
                ?: return@withContext Result.failure<List<CloudBrowser.Entry>>(
                    IllegalStateException("Reconnect needed — token unavailable."),
                )
            CloudBrowser.list(p, token, current.id, current.path)
        }
        result.onSuccess { entries = it }.onFailure { error = it.message ?: it::class.simpleName }
        loading = false
    }

    LaunchedEffect(provider?.id, current.id, loading) {
        if (provider == null || loading) return@LaunchedEffect
        repeat(15) {
            delay(40)
            val target = if (entries.isNotEmpty()) listFocus else contentFocus
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    var wasImporting by remember(provider?.id) { mutableStateOf(false) }
    LaunchedEffect(importing) {
        if (wasImporting && !importing) {
            val id = provider?.id
            val err = id?.let { CloudLibrary.errorsByProvider[it] }
            if (err != null) onToast("Import failed: $err")
            else onToast("Imported ${id?.let { CloudLibrary.lastImportedCount[it] } ?: 0} tracks (incl. subfolders)")
        }
        wasImporting = importing
    }

    BackHandler(enabled = true) {
        if (stack.size > 1) stack = stack.dropLast(1) else onNavigate(ScreenId.Cloud)
    }

    if (provider == null) {
        Box(modifier = Modifier.fillMaxSize().padding(64.dp), contentAlignment = Alignment.Center) {
            Text("No provider selected. Return to Cloud Storage and pick one.", style = type.body.copy(color = palette.ink2))
        }
        return
    }

    val folderSongs = remember(entries) { entries.mapNotNull { it.song } }
    val crumb = if (current.path.isEmpty()) "${provider.name} / My Drive" else "${provider.name} / ${current.path}"
    val currentImported = provider.importFolders.any { it.id == current.id }
    val currentMonitored = provider.monitoredFolders.any { it.id == current.id }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(crumb, style = type.sectionTitle.copy(color = palette.ink0), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = when {
                    loading -> "Loading…"
                    importing -> "Indexing into library…"
                    error != null -> "Error: $error"
                    else -> "${entries.count { it.isDir }} folders · ${folderSongs.size} tracks here" +
                        (if (currentImported) " · ${provider.trackCount} indexed from account" else "") +
                        (if (currentMonitored) " · monitored" else "")
                },
                style = type.bodySmall.copy(color = if (error != null) palette.bad else palette.ink2),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Row 1 — navigation + import.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                label = "Cloud",
                leadingIcon = Icons.Filled.ArrowBack,
                onClick = { onNavigate(ScreenId.Cloud) },
                height = 44.dp,
                modifier = Modifier.focusRequester(contentFocus).focusProperties { left = railRequester },
            )
            if (stack.size > 1) {
                GhostButton(
                    label = "Up",
                    leadingIcon = Icons.Filled.KeyboardArrowUp,
                    onClick = { stack = stack.dropLast(1) },
                    height = 44.dp,
                )
            }
            GradientButton(
                label = if (currentImported) "Re-index (+ subfolders)" else "Add to library (+ subfolders)",
                leadingIcon = Icons.Filled.Add,
                onClick = {
                    CloudLibrary.importFolder(context, provider, current)
                    val name = if (current.path.isEmpty()) "${provider.name} (My Drive)" else current.path
                    onToast("Indexing $name and everything below it…")
                },
                height = 44.dp,
            )
            if (folderSongs.isNotEmpty()) {
                GhostButton(
                    label = "Shuffle",
                    leadingIcon = Icons.Filled.Shuffle,
                    onClick = {
                        if (!player.shuffle) player.toggleShuffle()
                        player.playFrom(folderSongs, folderSongs.indices.random())
                        onNavigate(ScreenId.NowPlaying)
                    },
                    height = 44.dp,
                )
            }
        }

        // Row 2 — monitor + remove + disconnect.
        val folderLabel = if (current.path.isEmpty()) "${provider.name} (My Drive)" else current.path
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                label = if (currentMonitored) "Monitoring folder" else "Monitor folder",
                leadingIcon = if (currentMonitored) Icons.Filled.Check else Icons.Filled.Refresh,
                onClick = {
                    CloudLibrary.setFolderMonitored(context, provider, current, !currentMonitored)
                    onToast(if (currentMonitored) "Stopped monitoring $folderLabel" else "Monitoring $folderLabel")
                },
                height = 44.dp,
            )
            if (currentImported) {
                GhostButton(
                    label = "Remove from library",
                    leadingIcon = Icons.Filled.Delete,
                    onClick = {
                        CloudLibrary.removeImportedFolder(context, provider, current)
                        onToast("Removed $folderLabel from library")
                    },
                    height = 44.dp,
                )
            }
            GhostButton(
                label = "Disconnect",
                leadingIcon = Icons.Filled.Delete,
                onClick = {
                    CloudLibrary.deleteProvider(context, provider.id)
                    onToast("${provider.name} disconnected")
                    onNavigate(ScreenId.Cloud)
                },
                height = 44.dp,
            )
        }

        if (entries.isEmpty() && !loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (error != null) "Couldn't list this folder." else "Empty folder.",
                    style = type.body.copy(color = palette.ink3),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(entries, key = { _, e -> e.id }) { idx, entry ->
                    val rowMod = if (idx == 0) {
                        Modifier.focusRequester(listFocus).focusProperties { left = railRequester }
                    } else Modifier.focusProperties { left = railRequester }
                    if (entry.isDir) {
                        val imported = provider.importFolders.any { ref ->
                            ref.path.isEmpty() || entry.namePath == ref.path || entry.namePath.startsWith("${ref.path}/")
                        }
                        BrowseRow(
                            title = entry.name,
                            subtitle = "Folder",
                            icon = Icons.Outlined.Folder,
                            onClick = { stack = stack + CloudFolderRef(entry.id, entry.namePath) },
                            modifier = rowMod,
                            imported = imported,
                        )
                    } else {
                        val song = entry.song!!
                        val songIndex = folderSongs.indexOf(song)
                        BrowseRow(
                            title = song.title,
                            subtitle = buildString {
                                append(song.artist)
                                if (song.bitrate != "—") { append(" · "); append(song.bitrate) }
                            },
                            icon = Icons.Filled.PlayArrow,
                            onClick = {
                                player.playFrom(folderSongs, songIndex.coerceAtLeast(0))
                                onNavigate(ScreenId.NowPlaying)
                            },
                            modifier = rowMod,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imported: Boolean = false,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.bg1,
            focusedContainerColor = palette.bg2,
            contentColor = palette.ink0,
            focusedContentColor = palette.ink0,
        ),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().height(60.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.size(36.dp).background(palette.accent.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = type.songTitleCompact.copy(color = palette.ink0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = type.monoSmall.copy(color = palette.ink3), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (imported) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(imageVector = Icons.Filled.Check, contentDescription = "In library", tint = palette.good, modifier = Modifier.size(18.dp))
                    Text("In library", style = type.monoSmall.copy(color = palette.good), maxLines = 1)
                }
            }
        }
    }
}

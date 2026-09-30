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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import com.example.dink_smb_player.ui.components.LocalToast
import com.example.dink_smb_player.LocalContentFocus
import com.example.dink_smb_player.LocalDrawerOpen
import com.example.dink_smb_player.data.source.SourceLocks
import com.example.dink_smb_player.LocalRailFocusRequester
import com.example.dink_smb_player.data.SharesLibrary
import com.example.dink_smb_player.data.model.SmbShare
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.data.prefs.EncryptedShareStore
import com.example.dink_smb_player.data.prefs.SharePrefs
import com.example.dink_smb_player.data.source.smb.SmbBrowser
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.player.PlayerState
import com.example.dink_smb_player.ui.components.ConfirmDialog
import com.example.dink_smb_player.ui.components.GhostButton
import com.example.dink_smb_player.ui.components.GradientButton
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Per-share **folder browser**. Lists one directory at a time via [SmbBrowser]
 * (no upfront whole-share walk), so playing a folder hands the player only that
 * folder's tracks — the bounded-scope fix for the 28k-track ANR. Per folder the
 * user can Import into the library or toggle Monitor. Share-level ops (Resync,
 * Monitor, Delete) live here too, since the share card has no room for them.
 */
@Composable
fun SmbBrowseScreen(
    player: PlayerState,
    onNavigate: (ScreenId) -> Unit,
    onToast: (String) -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val railRequester = LocalRailFocusRequester.current
    val contentFocus = LocalContentFocus.current
    val context = LocalContext.current
    val toast = LocalToast.current
    val sharePrefs = remember(context) { SharePrefs(context.applicationContext) }
    val shares by sharePrefs.shares.collectAsState(initial = emptyList())
    val activeId = SharesLibrary.activeBrowseShareId
    val storedShare = remember(activeId, shares) { shares.firstOrNull { it.id == activeId } }
    // While a delete is in flight the share leaves SharePrefs a moment before we navigate
    // away; keep rendering the last copy so the screen doesn't flash "No share selected"
    // (and drop focus to the rail) in that gap.
    var lastShare by remember { mutableStateOf<SmbShare?>(null) }
    SideEffect { if (storedShare != null) lastShare = storedShare }
    val deleting = activeId != null && SharesLibrary.deletingShares[activeId] == true
    val share = storedShare ?: lastShare?.takeIf { deleting && it.id == activeId }

    // Pending destructive action awaiting the user's confirm (null = no dialog).
    var confirm by remember { mutableStateOf<PendingConfirm?>(null) }

    // False once this screen leaves composition. A delete's onDone can land long after the
    // user moved on (a dead NAS); it must not then navigate (commitNav steals focus).
    val screenAlive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) {
        screenAlive.set(true)
        onDispose { screenAlive.set(false) }
    }

    // Current folder path (backslash, relative to share root; "" = root) + the
    // listing for it. Loaded off the main thread; reload key includes path.
    var path by remember(share?.id) { mutableStateOf("") }
    var entries by remember(share?.id) { mutableStateOf<List<SmbBrowser.Entry>>(emptyList()) }
    var loading by remember(share?.id) { mutableStateOf(false) }
    var error by remember(share?.id) { mutableStateOf<String?>(null) }

    val importing = share?.let { SharesLibrary.importingShares[it.id] == true } ?: false

    // Focus target for the first list row. Folder navigation (row click, Up, Back)
    // swaps the whole list out from under the focused row, so Compose loses focus and
    // it falls back to the nav drawer. We re-anchor focus onto the new folder's first
    // row (or the action buttons when empty) once the listing has loaded.
    val listFocus = remember { FocusRequester() }

    LaunchedEffect(share?.id, path) {
        val s = share ?: return@LaunchedEffect
        // Drop the previous folder's rows up front: left in place they stayed clickable
        // under the new crumb while loading, and under "Error" if the listing failed.
        // Park focus on the header first so removing a focused row can't strand it on
        // the rail; the settle effect below moves it to the new first row.
        if (entries.isNotEmpty()) {
            runCatching { contentFocus.requestFocus() }
            entries = emptyList()
        }
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            val creds = EncryptedShareStore.get(context.applicationContext).getSmbCreds(s.id)
            SmbBrowser.list(s, creds, path)
        }
        result
            .onSuccess { entries = it }
            .onFailure {
                entries = emptyList()
                error = it.message ?: it::class.simpleName
            }
        loading = false
    }

    // After a folder listing settles, pull focus back into the content (first row, or
    // the buttons when the folder is empty). Retries because the lazy row isn't laid
    // out the instant loading flips false. Re-fires only on path/loading change, so it
    // never yanks focus during import/monitor toggles (those don't reload the list).
    LaunchedEffect(share?.id, path, loading) {
        if (share == null || loading) return@LaunchedEffect
        repeat(15) {
            delay(40)
            val target = if (entries.isNotEmpty()) listFocus else contentFocus
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    // Report the real (recursive) import result when an import finishes, since the
    // folder header only shows this folder's direct contents.
    var wasImporting by remember(share?.id) { mutableStateOf(false) }
    // Folder the user started importing from this screen (null = unknown: started elsewhere
    // or before this visit), and whether a Remove-from-library has cancelled that import.
    // A cancelled import also clears the importing flag; reporting it as "Imported N" (a
    // stale N) or as a failure was wrong — the Remove's own toast says what happened.
    var importingPath by remember(share?.id) { mutableStateOf<String?>(null) }
    var cancelledByRemove by remember(share?.id) { mutableStateOf(false) }
    LaunchedEffect(importing) {
        if (wasImporting && !importing) {
            val id = share?.id
            when (val t = importEndToast(
                cancelledByRemove = cancelledByRemove,
                error = id?.let { SharesLibrary.errorsByShare[it] },
                count = id?.let { SharesLibrary.lastImportedCount[it] },
                // A share delete cancels its import too; the delete reports itself.
                deleting = id?.let { SharesLibrary.deletingShares[it] == true } ?: false,
            )) {
                is ImportEndToast.Failed -> toast.error("Import failed: ${t.error}")
                is ImportEndToast.Done -> onToast("Imported ${t.count} tracks (incl. subfolders)")
                null -> Unit
            }
            cancelledByRemove = false
            importingPath = null
        }
        wasImporting = importing
    }

    // Back: climb out of the folder tree one level, then return to the share list.
    // Not while the drawer is open: Back there is the app's exit dialog (UI-22).
    BackHandler(enabled = !LocalDrawerOpen.current.value) {
        if (path.isNotEmpty()) path = path.substringBeforeLast('\\', "")
        else onNavigate(ScreenId.SmbShares)
    }

    if (share == null) {
        Box(modifier = Modifier.fillMaxSize().padding(64.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "No share selected. Return to SMB Shares and pick one.",
                style = type.body.copy(color = palette.ink2),
            )
        }
        return
    }

    val folderSongs = remember(entries) { entries.mapNotNull { it.song } }
    val crumb = if (path.isEmpty()) share.name else "${share.name} / ${path.replace('\\', '/')}"

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(crumb, style = type.sectionTitle.copy(color = palette.ink0), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = when {
                    deleting -> "Deleting share…"
                    loading -> "Loading…"
                    importing -> SharesLibrary.importProgress[share.id]
                        ?.let { "Importing into library… ${"%,d".format(it.found)} found (${"%.1f".format(it.ratePerSec)}/s)" }
                        ?: "Importing into library…"
                    error != null -> "Error: $error"
                    // "here" makes clear these counts are this folder's direct contents —
                    // Import is recursive, so the share-wide imported total is shown too.
                    else -> "${entries.count { it.isDir }} folders · ${folderSongs.size} tracks here" +
                        (if (path in share.importPaths) " · ${share.trackCount} imported from share" else "") +
                        (if (path in share.monitoredPaths) " · monitored" else "")
                },
                style = type.bodySmall.copy(color = if (error != null) palette.bad else palette.ink2),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Share-level status, e.g. a monitored folder that's gone from the NAS.
            val shareError = SharesLibrary.errorsByShare[share.id]
            if (shareError != null && !deleting && !importing) {
                Text(
                    text = shareError,
                    style = type.bodySmall.copy(color = palette.warn),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Row 1 — navigation + folder actions.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                label = "Shares",
                leadingIcon = Icons.Filled.ArrowBack,
                onClick = { onNavigate(ScreenId.SmbShares) },
                height = 44.dp,
                modifier = Modifier
                    .focusRequester(contentFocus)
                    .focusProperties { left = railRequester },
            )
            if (path.isNotEmpty()) {
                GhostButton(
                    label = "Up",
                    leadingIcon = Icons.Filled.KeyboardArrowUp,
                    onClick = { path = path.substringBeforeLast('\\', "") },
                    height = 44.dp,
                )
            }
            GradientButton(
                label = if (path in share.importPaths) "Re-import (+ subfolders)" else "Import (+ subfolders)",
                leadingIcon = Icons.Filled.Add,
                onClick = {
                    importingPath = path
                    SharesLibrary.importFolder(context, share, path)
                    val name = if (path.isEmpty()) "${share.name} (whole share)" else path.replace('\\', '/')
                    onToast("Importing $name and everything below it…")
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

        // Row 2 — folder monitor + share-level ops (moved off the cramped share card).
        val folderMonitored = path in share.monitoredPaths
        val folderLabel = if (path.isEmpty()) "${share.name} (whole share)" else path.replace('\\', '/')
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                label = if (folderMonitored) "Monitoring folder" else "Monitor folder",
                leadingIcon = if (folderMonitored) Icons.Filled.Check else Icons.Filled.Refresh,
                onClick = {
                    SharesLibrary.setFolderMonitored(context, share, path, !folderMonitored)
                    onToast(if (folderMonitored) "Stopped monitoring $folderLabel" else "Monitoring $folderLabel")
                },
                height = 44.dp,
            )
            if (path in share.importPaths) {
                GhostButton(
                    label = "Remove from library",
                    leadingIcon = Icons.Filled.Delete,
                    onClick = { if (!deleting) confirm = PendingConfirm.RemoveFolder(path, folderLabel) },
                    height = 44.dp,
                )
            }
            GhostButton(
                label = "Delete share",
                leadingIcon = Icons.Filled.Delete,
                onClick = { if (!deleting) confirm = PendingConfirm.DeleteShare },
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(entries, key = { _, e -> e.smbPath }) { idx, entry ->
                    val rowMod = if (idx == 0) {
                        Modifier
                            .focusRequester(listFocus)
                            .focusProperties { left = railRequester }
                    } else Modifier.focusProperties { left = railRequester }
                    if (entry.isDir) {
                        // Imported if this folder is an import root or sits under one
                        // ("" root = whole share imported).
                        val imported = share.importPaths.any {
                            it.isEmpty() || entry.smbPath == it || entry.smbPath.startsWith("$it\\")
                        }
                        BrowseRow(
                            title = entry.name,
                            subtitle = "Folder",
                            icon = Icons.Outlined.Folder,
                            onClick = { path = entry.smbPath },
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

    when (val pending = confirm) {
        null -> Unit
        is PendingConfirm.RemoveFolder -> ConfirmDialog(
            eyebrow = "REMOVE FROM LIBRARY",
            title = "Remove ${pending.label} from your library?",
            body = "Its tracks leave your library and it stops being monitored. " +
                "Nothing is deleted from ${share.name}; you can import it again any time.",
            cancelLabel = "Cancel",
            confirmLabel = "Remove",
            onCancel = { confirm = null },
            onConfirm = {
                confirm = null
                // The button goes away with the folder's import flag — park focus on the
                // header first so it can't fall back to the rail.
                runCatching { contentFocus.requestFocus() }
                if (removeCancelsImport(importing, importingPath, pending.smbPath)) cancelledByRemove = true
                SharesLibrary.removeImportedFolder(context, share, pending.smbPath) { err ->
                    if (err == null) onToast("Removed ${pending.label} from library")
                    else toast.error("Couldn't remove ${pending.label}: ${err.message ?: err::class.simpleName}")
                }
            },
        )
        PendingConfirm.DeleteShare -> ConfirmDialog(
            eyebrow = "DELETE SHARE",
            title = "Delete ${share.name}?",
            body = "Its ${share.trackCount} tracks leave your library and the saved login is erased. " +
                "Files on the NAS are not touched.",
            cancelLabel = "Cancel",
            confirmLabel = "Delete",
            onCancel = { confirm = null },
            onConfirm = {
                confirm = null
                val name = share.name
                val deletedId = share.id
                // The delete cancels an import in flight: don't report that as "Imported N"
                // or "Import failed" — the delete's own toast says what happened.
                if (importing) cancelledByRemove = true
                // Toast + leave only once the delete has actually finished — and leave only if
                // this screen is still up, showing that share.
                SharesLibrary.deleteShare(context, deletedId) { err ->
                    if (err == null) {
                        onToast("$name removed")
                        if (leaveAfterDelete(screenAlive.get(), SharesLibrary.activeBrowseShareId, deletedId)) {
                            onNavigate(ScreenId.SmbShares)
                        }
                    } else {
                        toast.error("Couldn't delete $name: ${err.message ?: err::class.simpleName}")
                    }
                }
            },
        )
    }
}

/** What to report when an import stops. Null = say nothing (a Remove or a share delete
 *  cancelled it). */
internal sealed interface ImportEndToast {
    data class Done(val count: Int) : ImportEndToast
    data class Failed(val error: String) : ImportEndToast
}

internal fun importEndToast(
    cancelledByRemove: Boolean,
    error: String?,
    count: Int?,
    deleting: Boolean = false,
): ImportEndToast? = when {
    cancelledByRemove || deleting -> null
    error != null -> ImportEndToast.Failed(error)
    else -> ImportEndToast.Done(count ?: 0)
}

/** Whether removing [removedPath] cancels the running import: SharesLibrary cancels
 *  imports of overlapping folders. Unknown import folder → assume it does. */
internal fun removeCancelsImport(importing: Boolean, importingPath: String?, removedPath: String): Boolean =
    importing && (importingPath == null || SourceLocks.pathsOverlap(importingPath, removedPath))

/** Whether a finished share delete should navigate back to the share list: only while this
 *  browse screen is still composed ([screenAlive]) AND showing the deleted share. The delete
 *  nulls [browsedShareId] when it was the deleted one; another id means the user moved on. */
internal fun leaveAfterDelete(screenAlive: Boolean, browsedShareId: String?, deletedId: String): Boolean =
    screenAlive && (browsedShareId == null || browsedShareId == deletedId)

/** A destructive browse-screen action waiting on [ConfirmDialog]. */
private sealed interface PendingConfirm {
    data class RemoveFolder(val smbPath: String, val label: String) : PendingConfirm
    data object DeleteShare : PendingConfirm
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
                modifier = Modifier
                    .size(36.dp)
                    .background(palette.accent.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = type.songTitleCompact.copy(color = palette.ink0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = type.monoSmall.copy(color = palette.ink3), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Green check marks folders whose tracks are in the library (this folder
            // is an import root or sits under one), so the browser shows what's in.
            if (imported) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(imageVector = Icons.Filled.Check, contentDescription = "Imported", tint = palette.good, modifier = Modifier.size(18.dp))
                    Text("In library", style = type.monoSmall.copy(color = palette.good), maxLines = 1)
                }
            }
        }
    }
}

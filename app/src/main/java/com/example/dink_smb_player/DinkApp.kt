package com.example.dink_smb_player

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.rememberDrawerState
import com.example.dink_smb_player.data.MediaLibrary
import com.example.dink_smb_player.data.PreviewMockData
import com.example.dink_smb_player.data.library.LibraryRepository
import com.example.dink_smb_player.data.library.PlaylistRepository
import com.example.dink_smb_player.lyrics.LyricChain
import com.example.dink_smb_player.lyrics.LyricPrefs
import com.example.dink_smb_player.lyrics.LyricSettings
import com.example.dink_smb_player.lyrics.toDisplayLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.dink_smb_player.nav.RailGroup
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.nav.rememberDinkNav
import com.example.dink_smb_player.player.PlayerState
import com.example.dink_smb_player.player.rememberPlayerState
import com.example.dink_smb_player.ui.components.DinkDrawerContent
import com.example.dink_smb_player.ui.components.ExitConfirmDialog
import com.example.dink_smb_player.ui.components.LocalToast
import com.example.dink_smb_player.ui.components.MiniPlayer
import com.example.dink_smb_player.ui.components.MiniPlayerState
import com.example.dink_smb_player.ui.components.PlaceholderScreen
import com.example.dink_smb_player.ui.components.ToastHost
import com.example.dink_smb_player.ui.components.TopBar
import com.example.dink_smb_player.ui.components.rememberToastState
import com.example.dink_smb_player.ui.screens.home.HomeScreen
import com.example.dink_smb_player.ui.screens.library.LibraryDetailNav
import com.example.dink_smb_player.ui.screens.library.ListScrollMemo
import com.example.dink_smb_player.ui.screens.library.LibraryDetailScreen
import com.example.dink_smb_player.ui.screens.library.LibraryGroupScreen
import com.example.dink_smb_player.ui.screens.library.albumGroups
import com.example.dink_smb_player.ui.screens.library.artistGroups
import com.example.dink_smb_player.ui.screens.library.folderGroups
import com.example.dink_smb_player.ui.screens.library.PlaylistsScreen
import com.example.dink_smb_player.ui.screens.library.SearchMemo
import com.example.dink_smb_player.ui.screens.library.SearchScreen
import com.example.dink_smb_player.ui.screens.library.SongsScreen
import com.example.dink_smb_player.ui.screens.nowplaying.NowPlayingScreen
import com.example.dink_smb_player.ui.screens.settings.SettingsScreen
import com.example.dink_smb_player.ui.screens.sources.AddShareWizard
import com.example.dink_smb_player.ui.screens.sources.LocalStorageScreen
import com.example.dink_smb_player.ui.screens.sources.CloudBrowseScreen
import com.example.dink_smb_player.ui.screens.sources.CloudScreen
import com.example.dink_smb_player.ui.screens.sources.SmbBrowseScreen
import com.example.dink_smb_player.ui.screens.sources.SmbSharesScreen
import com.example.dink_smb_player.data.prefs.EncryptedShareStore
import com.example.dink_smb_player.data.prefs.SharePrefs
import com.example.dink_smb_player.data.source.cloud.CloudConnectionRegistry
import com.example.dink_smb_player.data.source.smb.SmbConnectionRegistry
import com.example.dink_smb_player.ui.sound.LocalNavSounds
import com.example.dink_smb_player.ui.sound.rememberNavSounds
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalUiScale
import com.example.dink_smb_player.ui.theme.rememberUiScaleController

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun DinkApp() {
    val palette = LocalDinkPalette.current
    val uiScale = rememberUiScaleController()
    val navSounds = rememberNavSounds()
    val baseDensity = LocalDensity.current
    // Scale the whole UI from one knob: multiplying Density.density rescales every
    // dp dimension AND sp text uniformly, so no per-screen sizes need re-tuning.
    val scaledDensity = remember(uiScale.scale, baseDensity) {
        Density(density = baseDensity.density * uiScale.scale, fontScale = baseDensity.fontScale)
    }
    val nav = rememberDinkNav()
    val toast = rememberToastState()
    val albumsById = remember { PreviewMockData.albums.associateBy { it.id } }
    val context = LocalContext.current
    val player = rememberPlayerState(
        albumLookup = { id -> id?.let { albumsById[it] } },
    )

    // Persist tags the engine extracts while streaming (Phase 8.7) into the index.
    // Surface playback source errors (e.g. a track whose file was moved/deleted on the
    // share) as a toast — PlayerState auto-skips the bad track, this just tells the user.
    // consumePlaybackError drops an error raised long ago with no UI attached (service-side
    // error-skip while the app was closed), so it doesn't toast on the next open.
    LaunchedEffect(player.playbackError) {
        val msg = player.consumePlaybackError() ?: return@LaunchedEffect
        toast.error(msg)
    }

    // Tell the user (once) if the secure credential store had to be wiped or can't be
    // opened — otherwise shares just start failing auth with no explanation.
    LaunchedEffect(Unit) {
        EncryptedShareStore.notice.collect { notice ->
            when (notice ?: return@collect) {
                EncryptedShareStore.Notice.Reset ->
                    toast.error("Saved share passwords were reset — re-enter them in Sources")
                EncryptedShareStore.Notice.Unavailable ->
                    toast.error("Secure storage unavailable — passwords won't be saved")
            }
            EncryptedShareStore.acknowledgeNotice(context)
        }
    }

    // Library sinks (play credit → markPlayed, engine tags → enrichTrack) are bound once per
    // process in DinkApplication, not here: plays must count with no UI (media-key cold start).

    // Re-resolves when the title changes too, so enrichment (dirty filename → real
    // tag title) re-runs the lyric chain with the accurate name — and when the lyric
    // settings change what the chain may consult (e.g. Online lyrics switched on), so the
    // current track refetches instead of waiting for the next one. Re-keying cancels an
    // in-flight resolve started under the old settings.
    val lyricFingerprint by LyricSettings.fingerprintFlow.collectAsState()
    LaunchedEffect(player.currentSong?.id, player.currentSong?.title, lyricFingerprint) {
        val song = player.currentSong ?: return@LaunchedEffect
        if (song.mediaUri == null && song.id != PreviewMockData.songIxion.id) return@LaunchedEffect
        // A restored/preloaded track sits paused at launch; don't fire the provider chain
        // (up to a dozen network lookups + parsing) into the launch window for it. Resolve
        // once it plays or Now Playing is opened.
        snapshotFlow { player.isPlaying || nav.current == ScreenId.NowPlaying }.first { it }
        // resolve() suspends and is cancellable: a skip re-keys this effect, which cancels
        // the previous track's in-flight lookups instead of letting them pile up (LYR-8).
        val chain = LyricChain.resolve(context, song).toDisplayLines(song.durationSec)
        val resolved =
            if (chain.isNotEmpty()) chain
            else if (song.id == PreviewMockData.songIxion.id) PreviewMockData.sampleLyrics
            else emptyList()
        player.setLyricsFor(song.id, resolved)
    }

    LaunchedEffect(Unit) {
        // Rebuild the in-memory library index from disk first so imported SMB tracks
        // (and stats) survive a restart, then refresh local MediaStore on top.
        // Whole pipeline on Default: the restore upserts and loadOnce's importSource
        // (index merge + full persist snapshot) are pure computation that stalled the
        // first frames when run on this LaunchedEffect's main dispatcher.
        withContext(Dispatchers.Default) {
            LibraryRepository.ensureRestored(context)
            PlaylistRepository.ensureRestored(context)
            val audioPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(context, audioPerm) == PackageManager.PERMISSION_GRANTED) {
                MediaLibrary.loadOnce(context)
            }
        }
    }

    // SMB infrastructure: wire EncryptedShareStore-backed credLookup once, then
    // mirror SharePrefs.shares into the registry so SmbDataSource can resolve
    // `?sid=` for any persisted share — across the whole app, not just while
    // SmbSharesScreen is composed.
    LaunchedEffect(Unit) {
        // EncryptedSharedPreferences creation does Keystore + Tink init and a prefs
        // read — ~350ms measured on this TV. Off main; it stalled the first frames.
        val secretStore = withContext(Dispatchers.IO) { EncryptedShareStore.get(context.applicationContext) }
        SmbConnectionRegistry.installCredLookup { sid -> secretStore.getSmbCreds(sid) }
        val sharePrefs = SharePrefs(context.applicationContext)
        sharePrefs.shares.collect { shares -> SmbConnectionRegistry.update(shares) }
    }

    // Cloud infrastructure: same shape as SMB. Install a token store (read for
    // playback, write so a mid-session refresh persists) once, then mirror
    // persisted providers into the registry so CloudDataSource can resolve `?pid=`
    // app-wide — even resuming a cloud track without opening CloudScreen.
    LaunchedEffect(Unit) {
        // Same Keystore-init cost as the SMB store above — keep off main.
        val secretStore = withContext(Dispatchers.IO) { EncryptedShareStore.get(context.applicationContext) }
        CloudConnectionRegistry.installTokenStore(
            get = { pid -> secretStore.getCloudToken(pid) },
            put = { pid, token -> secretStore.putCloudToken(pid, token) },
        )
        val sharePrefs = SharePrefs(context.applicationContext)
        sharePrefs.providers.collect { providers -> CloudConnectionRegistry.update(providers) }
    }

    // Hydrate the in-memory lyric-provider toggles so LyricChain (which runs
    // synchronously on IO and can't read DataStore) honours the user's choices.
    LaunchedEffect(Unit) {
        LyricPrefs(context.applicationContext).toggles.collect { LyricSettings.hydrate(it) }
    }

    var exitDialog by remember { mutableStateOf(false) }
    val railRequester = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // alpha07's NavigationDrawer auto-expand on focus is unreliable — drive
    // DrawerValue ourselves from a focusGroup wrapper around drawerContent.
    var drawerHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(drawerHasFocus) {
        drawerState.setValue(if (drawerHasFocus) DrawerValue.Open else DrawerValue.Closed)
    }

    // Pull initial focus onto the active rail item so the drawer expands on
    // first frame and the highlight lands on the current screen, not the first
    // focusable Compose happens to find.
    LaunchedEffect(Unit) {
        runCatching { railRequester.requestFocus() }
    }

    // Any nav-from-content (song click in Local Storage, Continue Playing on
    // Home, etc.) must bounce focus to the new screen's primary focusable;
    // otherwise the old screen's button loses focus → Compose picks a drawer
    // rail item → drawer expands on the wrong screen.
    // Keyed on a per-commit counter, not the target screen: re-committing the screen
    // already shown (Enter on its own rail item) left the key unchanged, so the effect
    // never re-ran and focus stayed on the rail.
    var commitSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(commitSeq) {
        if (commitSeq == 0) return@LaunchedEffect
        // A single fixed delay races with the new screen's composition/layout
        // (data-heavy screens like LazyGrid take longer than any guess), so the
        // request no-ops and focus drifts onto the rail or mini-player. Retry
        // until contentFocus is attached: requestFocus() throws while the node
        // is unmounted and succeeds (moving focus) once it is, so we stop on the
        // first success and never yank focus back from the user afterwards.
        repeat(15) {
            delay(40)
            if (runCatching { contentFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    val commitNav: (ScreenId) -> Unit = { screen ->
        navSounds.select()
        nav.go(screen)
        commitSeq++
    }

    // Screen-level BackHandlers (SmbBrowse, CloudBrowse, AddShareWizard) register later and
    // so win over this one; they're gated on LocalDrawerOpen so Back in the open drawer
    // still reaches the exit dialog here (UI-22).
    // A State (not a Boolean read here) so drawer toggles don't recompose all of DinkApp.
    val drawerOpen = remember { derivedStateOf { drawerState.currentValue == DrawerValue.Open } }
    BackHandler(enabled = !exitDialog) {
        when (resolveBack(drawerOpen.value, nav.current, LibraryDetailNav.frames.size)) {
            // Drawer open (focused) → exit dialog.
            BackAction.ExitDialog -> exitDialog = true
            // Nested detail (album opened under an artist) → pop back to the artist's albums.
            BackAction.PopDetailFrame -> if (LibraryDetailNav.popFrame()) navSounds.select()
            // On a detail → return to the screen we came from (Albums/Artists/Folders/Search/
            // Home), where scroll + the opened item's focus are restored. Go straight (no
            // commitSeq bump) so the screen's own refocus wins instead of the primary focusable.
            BackAction.DetailToParent -> {
                navSounds.select()
                val parent = LibraryDetailNav.parent
                ListScrollMemo.facetOf(parent)?.let { ListScrollMemo.arm(it) }
                if (parent == ScreenId.Search) SearchMemo.arm()
                nav.go(parent)
            }
            // Otherwise (a top-level screen) → focus the rail; Back again (drawer open) exits.
            BackAction.FocusRail -> runCatching { railRequester.requestFocus() }
        }
    }

    CompositionLocalProvider(
        LocalRailFocusRequester provides railRequester,
        LocalContentFocus provides contentFocus,
        LocalDrawerOpen provides drawerOpen,
        LocalToast provides toast,
        LocalUiScale provides uiScale,
        LocalNavSounds provides navSounds,
        LocalDensity provides scaledDensity,
    ) {
        // testTagsAsResourceId surfaces Modifier.testTag(...) as uiautomator
        // resource-ids so the adb focus crawler (tools/focuscheck.py) can name
        // which zone (rail/content/miniplayer) holds focus. Debug-only cost.
        Box(modifier = Modifier
            .fillMaxSize()
            .background(palette.bg0)
            // Root D-pad tick: every directional key press anywhere (lists, grids,
            // tabs, settings — not just the rail) gets a navigation click. Fires on
            // KeyDown of the four directions only; Center/Enter is the commit click
            // handled by select(). Never consumes (returns false) — pure feedback.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && when (event.key) {
                        Key.DirectionUp, Key.DirectionDown,
                        Key.DirectionLeft, Key.DirectionRight -> true
                        else -> false
                    }
                ) {
                    navSounds.move()
                }
                false
            }
            .semantics { testTagsAsResourceId = true }) {
            NavigationDrawer(
                drawerContent = { drawerValue ->
                    Box(
                        modifier = Modifier
                            .focusGroup()
                            .onFocusChanged { state -> drawerHasFocus = state.hasFocus },
                    ) {
                        DinkDrawerContent(
                            drawerValue = drawerValue,
                            // Map transient screens (AddShareWizard, SmbBrowse) to their
                            // parent rail entry so the drawer highlights something AND
                            // railRequester gets attached to a RailItem. Without this,
                            // pressing D-pad Left on the wizard crashes with
                            // "FocusRequester is not initialized" because the rail has
                            // no item bound to railRequester.
                            current = railCurrentFor(nav.current),
                            onCommit = commitNav,
                            currentItemFocusRequester = railRequester,
                        )
                    }
                },
                drawerState = drawerState,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopBar(
                        crumbs = crumbsFor(nav.current),
                        sourceStatus = null,
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        ScreenHost(
                            screen = nav.current,
                            player = player,
                            onNavigate = commitNav,
                            onToast = toast::show,
                        )
                    }
                    // MiniPlayer is the persistent transport readout — but it's redundant
                    // on Now Playing, which has its own full transport, so hide it there.
                    // Rail items are commit-only (no preview-on-focus), so nav between
                    // screens is a committed action and the one-time relayout is fine.
                    if (nav.current != ScreenId.NowPlaying) {
                        val miniState = MiniPlayerState(
                            title = player.currentSong?.title ?: "Nothing playing",
                            artist = player.currentSong?.artist ?: "—",
                            isPlaying = player.isPlaying,
                        )
                        MiniPlayer(
                            state = miniState,
                            // Read in MiniPlayer's draw phase only, so the 250 ms position tick
                            // doesn't recompose this content lambda (every screen) — UI-11.
                            progress = { player.progress },
                            song = player.currentSong,
                            onPlayPause = player::togglePlayPause,
                            onPrev = player::prev,
                            onNext = player::next,
                        )
                    }
                }
            }
            ToastHost(state = toast)
        }
    }

    if (exitDialog) {
        ExitConfirmDialog(
            onCancel = { exitDialog = false },
            onConfirm = {
                exitDialog = false
                (context as? Activity)?.finish()
            },
        )
    }
}

@Composable
private fun ScreenHost(
    screen: ScreenId,
    player: PlayerState,
    onNavigate: (ScreenId) -> Unit,
    onToast: (String) -> Unit,
) {
    when (screen) {
        ScreenId.Home -> HomeScreen(player = player, onNavigate = onNavigate, onToast = onToast)
        ScreenId.Search -> SearchScreen(player = player, onNavigate = onNavigate, onToast = onToast)
        ScreenId.NowPlaying -> NowPlayingScreen(player = player, onNavigate = onNavigate)
        ScreenId.Songs -> SongsScreen(player = player, onNavigate = onNavigate, onToast = onToast)
        ScreenId.Albums -> LibraryGroupScreen("Albums", ScreenId.Albums.icon, player, onNavigate, ::albumGroups, facet = "Album", parent = ScreenId.Albums, artGrid = true)
        ScreenId.Artists -> LibraryGroupScreen("Artists", ScreenId.Artists.icon, player, onNavigate, ::artistGroups, facet = "Artist", parent = ScreenId.Artists, artGrid = true)
        ScreenId.Playlists -> PlaylistsScreen(player = player, onNavigate = onNavigate, onToast = onToast)
        ScreenId.Folders -> LibraryGroupScreen("Folders", ScreenId.Folders.icon, player, onNavigate, ::folderGroups, facet = "Folder", parent = ScreenId.Folders)
        ScreenId.LibraryDetail -> LibraryDetailScreen(player = player, onNavigate = onNavigate, onToast = onToast)
        ScreenId.LocalStorage -> LocalStorageScreen(player = player, onNavigate = onNavigate)
        ScreenId.SmbShares -> SmbSharesScreen(player = player, onNavigate = onNavigate, onToast = onToast)
        ScreenId.SmbBrowse -> SmbBrowseScreen(player = player, onNavigate = onNavigate, onToast = onToast)
        ScreenId.Cloud -> CloudScreen(player = player, onNavigate = onNavigate, onToast = onToast)
        ScreenId.CloudBrowse -> CloudBrowseScreen(player = player, onNavigate = onNavigate, onToast = onToast)
        // AddShareWizard hands off to whatever target it chose (SmbShares on Cancel,
        // SmbBrowse on Save).
        ScreenId.AddShareWizard -> AddShareWizard(onDone = onNavigate, onToast = onToast)
        ScreenId.Settings -> SettingsScreen()
        else -> PlaceholderScreen(screen)
    }
}

/**
 * FocusRequester bound to the first NavigationDrawerItem. Screens use this to
 * route Left-edge focus back into the drawer via
 * `Modifier.focusProperties { left = LocalRailFocusRequester.current }`. The
 * drawer itself handles expand/collapse on focus changes.
 */
val LocalRailFocusRequester = compositionLocalOf<FocusRequester> {
    error("LocalRailFocusRequester not provided")
}

/**
 * FocusRequester attached to the active screen's primary focusable. The drawer
 * uses it on a committed select (Enter) to bounce focus into content so the
 * drawer collapses.
 */
val LocalContentFocus = compositionLocalOf<FocusRequester> {
    error("LocalContentFocus not provided")
}

/**
 * Whether the side drawer is open (focus in the rail). Screen BackHandlers gate on it
 * (`enabled = !LocalDrawerOpen.current.value`) so Back in the drawer reaches the app-level
 * exit dialog instead of the screen's own "up a level" handler (UI-22).
 */
val LocalDrawerOpen = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/** What app-level Back does, by precedence. Pure so the order is unit-tested. */
internal enum class BackAction { ExitDialog, PopDetailFrame, DetailToParent, FocusRail }

internal fun resolveBack(drawerOpen: Boolean, current: ScreenId, detailFrames: Int): BackAction = when {
    drawerOpen -> BackAction.ExitDialog
    current == ScreenId.LibraryDetail && detailFrames > 1 -> BackAction.PopDetailFrame
    current == ScreenId.LibraryDetail -> BackAction.DetailToParent
    else -> BackAction.FocusRail
}

/** Map non-rail screens to their parent rail entry. Used both to highlight the
 *  drawer correctly and to ensure railRequester always attaches to a RailItem. */
private fun railCurrentFor(screen: ScreenId): ScreenId = when (screen) {
    ScreenId.AddShareWizard, ScreenId.SmbBrowse -> ScreenId.SmbShares
    ScreenId.CloudBrowse -> ScreenId.Cloud
    ScreenId.LibraryDetail -> LibraryDetailNav.parent
    else -> screen
}

internal fun crumbsFor(screen: ScreenId): String {
    if (screen == ScreenId.LibraryDetail) {
        val g = LibraryDetailNav.group
        val parent = LibraryDetailNav.parent
        // "Library / " only for library-list parents — a detail opened from Search or Home
        // read "Library / Search / …".
        val prefix = if (parent.group == RailGroup.Library) "Library / " else ""
        return "$prefix${parent.displayName}" + (g?.let { " / ${it.title}" } ?: "")
    }
    val prefix = when (screen.group) {
        RailGroup.Top -> ""
        RailGroup.Library -> "Library / "
        RailGroup.Sources -> "Sources / "
        RailGroup.Bottom -> ""
    }
    return "$prefix${screen.displayName}"
}

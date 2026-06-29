@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.example.dink_smb_player.ui.screens.sources

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.example.dink_smb_player.DinkApplication
import com.example.dink_smb_player.LocalContentFocus
import com.example.dink_smb_player.LocalRailFocusRequester
import com.example.dink_smb_player.data.SharesLibrary
import com.example.dink_smb_player.data.model.ConnectionStatus
import com.example.dink_smb_player.data.model.SmbProtocol
import com.example.dink_smb_player.data.model.SmbShare
import com.example.dink_smb_player.data.prefs.EncryptedShareStore
import com.example.dink_smb_player.data.prefs.SharePrefs
import com.example.dink_smb_player.data.prefs.SmbCreds
import com.example.dink_smb_player.data.source.smb.DiscoveredHost
import com.example.dink_smb_player.data.source.smb.LanScanner
import com.example.dink_smb_player.data.source.smb.SmbClient
import com.example.dink_smb_player.data.source.smb.SmbConnectionRegistry
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Add SMB Share — single compact scrollable form (no multi-step wizard).
 *
 * Everything (host, port, LAN discovery, share/display name, auth, credentials,
 * test result, action buttons) lives in one `verticalScroll` Column so nothing
 * can be clipped off-screen. Controls are built from foundation primitives only;
 * the tv-material `Surface` fails to place/render reliably inside this screen on
 * the target device, so we don't use it here.
 *
 * Focus: the host field is the entry point (bound to [LocalContentFocus]) with
 * `up = Cancel` so D-pad Up can't escape to the rail and silently reopen the nav
 * drawer. Every control routes `left` to the rail (the only intended rail entry)
 * and the action row caps `down = Cancel`.
 *
 * On Add: persists non-secret config to [SharePrefs], secret creds to
 * [EncryptedShareStore], pushes the entry into [SmbConnectionRegistry] so
 * playback can resolve `?sid=` immediately, then kicks off a background sync.
 */
@Composable
fun AddShareWizard(
    onDone: (ScreenId) -> Unit,
    onToast: (String) -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val railRequester = LocalRailFocusRequester.current
    val contentFocus = LocalContentFocus.current
    val context = LocalContext.current
    val sharePrefs = remember(context) { SharePrefs(context.applicationContext) }
    val secretStore = remember(context) { EncryptedShareStore(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("445") }
    var shareName by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var authGuest by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var domain by remember { mutableStateOf("") }

    var discovering by remember { mutableStateOf(false) }
    var hosts by remember { mutableStateOf<List<DiscoveredHost>>(emptyList()) }
    val scanScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(Unit) { onDispose { scanScope.coroutineContext[Job]?.cancel() } }

    var testing by remember { mutableStateOf(false) }
    var testError by remember { mutableStateOf<String?>(null) }
    var testPassed by remember { mutableStateOf(false) }
    val addFocus = remember { FocusRequester() }

    // When the test passes, pull focus straight onto the now-enabled Add button so
    // the user just presses OK to confirm — no hunting, no chance of drifting to the rail.
    LaunchedEffect(testPassed) {
        if (testPassed) {
            kotlinx.coroutines.delay(40)
            runCatching { addFocus.requestFocus() }
        }
    }

    val valid = host.isNotBlank() &&
        port.toIntOrNull() != null &&
        shareName.isNotBlank() &&
        (authGuest || (user.isNotBlank() && password.isNotBlank()))

    // Drop focus onto the host field on entry so OK starts typing immediately and
    // the nav handler's contentFocus.requestFocus() lands here, not on the rail.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { contentFocus.requestFocus() }
    }

    // Own Back so it cancels the form instead of bubbling to the global handler
    // (which focuses the rail and reopens the drawer). A field in edit mode has
    // its own inner BackHandler that takes priority.
    BackHandler(enabled = !testing) { onDone(ScreenId.SmbShares) }

    val rail = Modifier.focusProperties { left = railRequester }

    fun startTest() {
        testing = true
        testError = null
        scope.launch {
            val portInt = port.toIntOrNull() ?: 445
            val creds = if (authGuest) null
                else SmbCreds(user, password, domain.ifBlank { null })
            val result = withContext(Dispatchers.IO) {
                SmbClient.test(host, portInt, shareName, creds)
            }
            result
                .onSuccess { testPassed = true }
                .onFailure { testError = it.message ?: it::class.simpleName.orEmpty() }
            testing = false
        }
    }

    fun save() {
        val portInt = port.toIntOrNull() ?: 445
        val id = "smb-${UUID.randomUUID().toString().substring(0, 8)}"
        val share = SmbShare(
            id = id,
            name = displayName.ifBlank { "$host/$shareName" },
            host = host,
            port = portInt,
            shareName = shareName,
            mountPath = "//$host/$shareName",
            user = if (authGuest) "guest" else user,
            protocol = SmbProtocol.Auto,
            // Only claim Connected if the test actually passed; otherwise Idle so
            // the card doesn't lie about an unverified (possibly wrong-IP) share.
            status = if (testPassed) ConnectionStatus.Connected else ConnectionStatus.Idle,
            trackCount = 0,
            sizeBytes = 0L,
            lastSyncMs = null,
            signal = 1f,
        )
        val credsToStore = if (authGuest) null else SmbCreds(user, password, domain.ifBlank { null })
        // Persist on the app scope, NOT the wizard's rememberCoroutineScope: we
        // navigate away on Add, which would cancel a write mid-flight.
        val appScope = (context.applicationContext as DinkApplication).appScope
        SmbConnectionRegistry.add(share)
        SharesLibrary.activeBrowseShareId = id
        appScope.launch {
            // Persist the share config FIRST and independently of the secret write.
            // If the encrypted creds store is wedged it must NOT block the share from
            // landing in SharePrefs — that ordering was the "re-add vanishes" bug.
            runCatching { sharePrefs.saveShare(share) }
                .onFailure { android.util.Log.e("AddShareWizard", "saveShare failed id=$id", it) }
            if (credsToStore != null) {
                runCatching { secretStore.putSmbCreds(id, credsToStore) }
                    .onFailure { android.util.Log.e("AddShareWizard", "putSmbCreds failed id=$id", it) }
            }
            android.util.Log.i("AddShareWizard", "saved share id=$id host=${share.host}")
            // No upfront flat walk — SmbBrowseScreen lists the root folder lazily on
            // open, and the user imports the folders they want into the library there.
        }
        onToast("${share.name} saved")
        onDone(ScreenId.SmbShares)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Add SMB Share", style = type.sectionTitle.copy(color = palette.ink0))

        // Host + Port row
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                TapEditField(
                    label = "Host or IP",
                    value = host,
                    onChange = { host = it; testPassed = false; testError = null },
                    placeholder = "192.168.1.42",
                    // Entry point: bind content focus + cap Up so focus can't
                    // escape upward to the rail and reopen the drawer.
                    fieldModifier = Modifier
                        .focusRequester(contentFocus)
                        .focusProperties { left = railRequester; up = FocusRequester.Cancel },
                )
            }
            Box(modifier = Modifier.width(120.dp)) {
                TapEditField(
                    label = "Port",
                    value = port,
                    onChange = { port = it; testPassed = false; testError = null },
                    placeholder = "445",
                    keyboardType = KeyboardType.Number,
                    fieldModifier = rail,
                )
            }
        }

        FormButton(
            label = if (discovering) "Stop scan" else "Discover LAN",
            modifier = rail,
            onClick = {
                if (!discovering) {
                    discovering = true
                    hosts = emptyList()
                    scope.launch {
                        LanScanner.discover(context.applicationContext, scanScope)
                            .collect { list -> hosts = list }
                    }
                } else {
                    discovering = false
                    scanScope.coroutineContext[Job]?.cancelChildren()
                }
            },
        )

        if (discovering || hosts.isNotEmpty()) {
            DiscoveryList(
                hosts = hosts,
                searching = discovering && hosts.isEmpty(),
                railRequester = railRequester,
                onPick = { picked ->
                    host = picked.address
                    port = picked.port.toString()
                    testPassed = false
                    discovering = false
                    scanScope.coroutineContext[Job]?.cancelChildren()
                },
            )
        }

        TapEditField(
            label = "Share name",
            value = shareName,
            onChange = { shareName = it; testPassed = false; testError = null },
            placeholder = "music",
            fieldModifier = rail,
        )
        TapEditField(
            label = "Display name (optional)",
            value = displayName,
            onChange = { displayName = it },
            placeholder = "Attic NAS",
            fieldModifier = rail,
        )

        // Auth toggle
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AuthChip(
                title = "Password",
                selected = !authGuest,
                onClick = { authGuest = false; testPassed = false },
                modifier = Modifier.weight(1f).then(rail),
            )
            AuthChip(
                title = "Guest",
                selected = authGuest,
                onClick = { authGuest = true; testPassed = false },
                modifier = Modifier.weight(1f),
            )
        }

        if (!authGuest) {
            TapEditField(
                label = "Username",
                value = user,
                onChange = { user = it; testPassed = false },
                placeholder = "username",
                fieldModifier = rail,
            )
            TapEditField(
                label = "Password",
                value = password,
                onChange = { password = it; testPassed = false },
                placeholder = "••••••••",
                // Mask only when hidden; reveal toggle below flips it. KeyboardType
                // .Password puts the IME in password mode (no learning/suggestions)
                // regardless of reveal, so the on-screen keyboard never remembers it.
                secret = !showPassword,
                keyboardType = KeyboardType.Password,
                fieldModifier = rail,
            )
            FormButton(
                label = if (showPassword) "Hide password" else "Show password",
                modifier = rail,
                onClick = { showPassword = !showPassword },
            )
            TapEditField(
                label = "Domain (optional)",
                value = domain,
                onChange = { domain = it },
                placeholder = "WORKGROUP",
                fieldModifier = rail,
            )
        }

        // Test result line
        when {
            testing -> Text("Connecting…", style = type.bodySmall.copy(color = palette.ink1))
            testPassed -> Text("Connection OK", style = type.bodySmall.copy(color = palette.good))
            testError != null -> Text("Failed: $testError", style = type.bodySmall.copy(color = palette.bad))
        }

        // Action row — caps Down so focus can't fall off into the rail.
        Row(
            modifier = Modifier.fillMaxWidth().focusProperties { down = FocusRequester.Cancel },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FormButton(
                label = "Cancel",
                modifier = Modifier.weight(1f).then(rail),
                onClick = { onDone(ScreenId.SmbShares) },
            )
            FormButton(
                label = if (testing) "Testing…" else "Test",
                modifier = Modifier.weight(1f),
                enabled = valid && !testing,
                onClick = { startTest() },
            )
            FormButton(
                // Gated on a passing test so a wrong IP / share name can't be
                // saved as a phantom "online" share.
                label = "Add share",
                modifier = Modifier.weight(1f).focusRequester(addFocus),
                primary = true,
                enabled = testPassed && !testing,
                onClick = { save() },
            )
        }

        HelpText(
            if (!testPassed) "Run Test first — Add unlocks once the connection succeeds. Default port 445 (139 for legacy NetBIOS); Discover scans the LAN (~3 s). Credentials stored encrypted on this device only."
            else "Connection verified. Press Add share to save. Credentials stored encrypted on this device only.",
        )
    }
}

@Composable
private fun DiscoveryList(
    hosts: List<DiscoveredHost>,
    searching: Boolean,
    railRequester: FocusRequester,
    onPick: (DiscoveredHost) -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.bg1, RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (searching) "Scanning LAN…" else "${hosts.size} hosts found",
            style = type.monoSmall.copy(color = palette.ink3),
        )
        hosts.forEachIndexed { idx, h ->
            var focused by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (focused) palette.accent else palette.bg2)
                    .onFocusChanged { focused = it.isFocused }
                    .let { if (idx == 0) it.focusProperties { left = railRequester } else it }
                    .clickable { onPick(h) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val ink = if (focused) Color.Black else palette.ink0
                Text(h.name, style = type.bodySmall.copy(color = ink), modifier = Modifier.weight(1f))
                Text(h.address, style = type.monoSmall.copy(color = ink))
                Text(h.via.uppercase(), style = type.monoSmall.copy(color = ink))
            }
        }
    }
}

/**
 * Tap-to-edit field. Focusing a `BasicTextField` on TV immediately opens the
 * full-screen leanback IME, which is jarring while merely D-pad navigating. So
 * we render a focusable display chip and only mount the `BasicTextField` (which
 * then auto-focuses and triggers IME) after the user activates with OK.
 */
@Composable
private fun TapEditField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    fieldModifier: Modifier = Modifier,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current

    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val displayFocus = remember { FocusRequester() }
    var returnFocus by remember { mutableStateOf(false) }
    // Block re-entry into edit mode for a beat after exiting. Leanback IME Done /
    // Back emits a trailing key-up that lands on the just-refocused chip and would
    // otherwise fire its clickable → IME reopens with no OK press.
    var reopenGuardUntil by remember { mutableStateOf(0L) }

    fun exitEdit() {
        editing = false
        returnFocus = true
        reopenGuardUntil = System.currentTimeMillis() + 400
    }

    if (editing) {
        BackHandler(enabled = true) { exitEdit() }
    }

    // After leaving edit mode, pull focus back onto this field's display chip,
    // else focus falls to nowhere → Compose grabs the rail → drawer reopens.
    LaunchedEffect(returnFocus) {
        if (returnFocus) {
            kotlinx.coroutines.delay(40)
            runCatching { displayFocus.requestFocus() }
            returnFocus = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = if (editing) "${label.uppercase()} · BACK WHEN DONE" else "${label.uppercase()} · OK TO EDIT",
            style = type.monoSmall.copy(color = if (editing) palette.accent else palette.ink3),
        )
        if (editing) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(40)
                runCatching { fieldFocus.requestFocus() }
            }
            // Caller modifier intentionally NOT applied: it carries the display
            // chip's .focusRequester; the edit Box isn't focusable, so binding it
            // here would orphan the requester and crash on the next key event.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(palette.bg1, RoundedCornerShape(8.dp))
                    .border(1.dp, palette.accent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, style = type.bodySmall.copy(color = palette.ink3))
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = type.bodySmall.copy(color = palette.ink0),
                    cursorBrush = SolidColor(palette.accent),
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { exitEdit() }),
                    // Trap all D-pad directions while editing: a directional press
                    // the text field doesn't consume would otherwise escape to the
                    // rail and open the nav drawer mid-type. Exit is Back only.
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        .focusProperties {
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                            up = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                        },
                )
            }
        } else {
            Box(
                modifier = fieldModifier
                    .focusRequester(displayFocus)
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.bg1)
                    .border(
                        1.dp,
                        if (focused) palette.accent else palette.lineStrong,
                        RoundedCornerShape(8.dp),
                    )
                    .onFocusChanged { focused = it.isFocused }
                    .clickable {
                        if (System.currentTimeMillis() >= reopenGuardUntil) editing = true
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                val displayed = when {
                    value.isEmpty() -> placeholder
                    secret -> "•".repeat(value.length.coerceAtMost(24))
                    else -> value
                }
                val color = if (value.isEmpty()) palette.ink3 else palette.ink0
                Text(displayed, style = type.bodySmall.copy(color = color))
            }
        }
    }
}

@Composable
private fun AuthChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) palette.accent else if (selected) palette.bg2 else palette.bg1)
            .border(
                1.dp,
                if (selected) palette.accent else palette.lineStrong,
                RoundedCornerShape(8.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = type.buttonLabel.copy(color = if (focused) Color.Black else palette.ink0),
        )
    }
}

/** Full-width focusable button from foundation primitives. */
@Composable
private fun FormButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        !enabled -> palette.bg1
        focused -> palette.accent
        primary -> palette.accent
        else -> palette.bg1
    }
    val ink = when {
        !enabled -> palette.ink3
        focused || primary -> Color.Black
        else -> palette.ink0
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            // Always focusable — only the action is gated. A disabled `clickable`
            // drops the node from the focus tree, so disabling the just-pressed Test
            // button mid-test threw focus to the rail and opened the nav drawer.
            .clickable { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = type.buttonLabel.copy(color = ink))
    }
}

@Composable
private fun HelpText(text: String) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    Text(text, style = type.bodySmall.copy(color = palette.ink3))
}

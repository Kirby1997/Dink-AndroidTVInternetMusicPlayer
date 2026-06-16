package com.example.dink_smb_player.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.themePrefsDataStore by preferencesDataStore(name = "dink_theme")
private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")

/** App theme choice. [System] follows the device's dark/light setting. */
enum class ThemeMode { System, Dark, Light }

/**
 * Single global theme knob, mirroring [UiScaleController]. Held ABOVE [DinkTheme] (in
 * MainActivity) so the chosen mode resolves the palette for the whole tree; Settings
 * reads it via [LocalThemeController] and flips it. Persisted to a Preferences DataStore.
 */
@Stable
class ThemeController(
    initial: ThemeMode,
    private val persist: (ThemeMode) -> Unit,
) {
    var mode by mutableStateOf(initial)
        private set

    fun set(value: ThemeMode) {
        if (value == mode) return
        mode = value
        persist(value)
    }

    /** Apply a persisted value on load WITHOUT re-persisting it. */
    internal fun adoptStored(value: ThemeMode) {
        mode = value
    }
}

val LocalThemeController = staticCompositionLocalOf<ThemeController> {
    error("LocalThemeController not provided")
}

@Composable
fun rememberThemeController(): ThemeController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember {
        ThemeController(ThemeMode.System) { value ->
            scope.launch { context.themePrefsDataStore.edit { it[THEME_MODE_KEY] = value.name } }
        }
    }
    // Load the persisted value once on mount (survives process death / reboot).
    LaunchedEffect(Unit) {
        val stored = context.themePrefsDataStore.data
            .map { prefs -> prefs[THEME_MODE_KEY]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System }
            .first()
        controller.adoptStored(stored)
    }
    return controller
}

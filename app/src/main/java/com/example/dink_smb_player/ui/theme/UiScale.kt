package com.example.dink_smb_player.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Context.uiPrefsDataStore by preferencesDataStore(name = "dink_ui")
private val UI_SCALE_KEY = floatPreferencesKey("ui_scale")

// The 1920×1080 design reference is generous for a TV viewed across a room; 0.85
// tightens every dp/sp so more content fits without re-tuning each screen. The
// slider in Settings drives this between [MinUiScale, MaxUiScale].
const val DefaultUiScale = 0.85f
const val MinUiScale = 0.60f
const val MaxUiScale = 1.20f
const val UiScaleStep = 0.05f

/**
 * Single global UI-scale knob. Multiplies [androidx.compose.ui.unit.Density.density],
 * which scales BOTH dp dimensions and sp text by the same factor — so the whole UI
 * shrinks/grows uniformly from one value. Persisted to a Preferences DataStore.
 */
@Stable
class UiScaleController(
    initial: Float,
    private val persist: (Float) -> Unit,
) {
    var scale by mutableFloatStateOf(initial.snapToStep())
        private set

    /** Percentage label for the UI, e.g. 0.85 → "85%". */
    val percentLabel: String get() = "${(scale * 100).roundToInt()}%"

    fun set(value: Float) {
        val next = value.snapToStep()
        if (next == scale) return
        scale = next
        persist(next)
    }

    fun nudge(delta: Float) = set(scale + delta)

    fun reset() = set(DefaultUiScale)

    /** Apply a persisted value on load WITHOUT re-persisting it. */
    internal fun adoptStored(value: Float) {
        scale = value.snapToStep()
    }
}

private fun Float.snapToStep(): Float {
    val clamped = coerceIn(MinUiScale, MaxUiScale)
    return (clamped / UiScaleStep).roundToInt() * UiScaleStep
}

val LocalUiScale = staticCompositionLocalOf<UiScaleController> {
    error("LocalUiScale not provided")
}

@Composable
fun rememberUiScaleController(): UiScaleController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember {
        UiScaleController(DefaultUiScale) { value ->
            scope.launch { context.uiPrefsDataStore.edit { it[UI_SCALE_KEY] = value } }
        }
    }
    // Load the persisted value once on mount (survives process death / reboot).
    LaunchedEffect(Unit) {
        val stored = context.uiPrefsDataStore.data
            .map { it[UI_SCALE_KEY] ?: DefaultUiScale }
            .first()
        controller.adoptStored(stored)
    }
    return controller
}

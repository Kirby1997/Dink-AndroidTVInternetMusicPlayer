package com.example.dink_smb_player.ui.sound

import android.content.Context
import android.media.AudioManager
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
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.soundPrefsDataStore by preferencesDataStore(name = "dink_sound")
private val NAV_SOUNDS_KEY = booleanPreferencesKey("nav_sounds")

const val DefaultNavSounds = true

/**
 * Short UI feedback clicks for D-pad menu navigation and selection. Plays from the
 * platform [AudioManager] sound-effect bank ([AudioManager.FX_FOCUS_NAVIGATION_RIGHT]
 * for hover, [AudioManager.FX_KEY_CLICK] for commit), so there are NO bundled audio
 * assets to ship — it reuses the TV's own navigation clicks and the device honours its
 * system "touch sounds" preference on top of our in-app toggle. Persisted to a
 * Preferences DataStore so the choice survives process death.
 */
@Stable
class NavSounds(
    context: Context,
    enabled: Boolean,
    private val persist: (Boolean) -> Unit,
) {
    private val audio =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    var enabled by mutableStateOf(enabled)
        private set

    fun set(value: Boolean) {
        if (value == enabled) return
        enabled = value
        persist(value)
        if (value) select() // immediate audible confirmation when switching on
    }

    /** Apply a persisted value on load WITHOUT re-persisting it. */
    internal fun adoptStored(value: Boolean) {
        enabled = value
    }

    /** Focus moved onto a new item (D-pad navigation tick). */
    fun move() {
        // FX_KEY_CLICK, not FX_FOCUS_NAVIGATION_*: the directional nav effects are
        // not populated in the sound pool on most Android TV builds and play silent,
        // which is why navigation made no sound. FX_KEY_CLICK is the one effect proven
        // audible here (commit uses it too), so reuse it softer for the move tick.
        if (enabled) audio?.playSoundEffect(AudioManager.FX_KEY_CLICK, MOVE_VOLUME)
    }

    /** An item was committed (Enter / click). */
    fun select() {
        if (enabled) audio?.playSoundEffect(AudioManager.FX_KEY_CLICK, SELECT_VOLUME)
    }

    private companion object {
        // Soft relative to the music being played — a feedback tick, not a jingle.
        // Move is quieter than commit so navigation and selection feel distinct.
        const val MOVE_VOLUME = 0.25f
        const val SELECT_VOLUME = 0.45f
    }
}

val LocalNavSounds = staticCompositionLocalOf<NavSounds> { error("LocalNavSounds not provided") }

@Composable
fun rememberNavSounds(): NavSounds {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember {
        NavSounds(context, DefaultNavSounds) { value ->
            scope.launch { context.soundPrefsDataStore.edit { it[NAV_SOUNDS_KEY] = value } }
        }
    }
    LaunchedEffect(Unit) {
        val stored = context.soundPrefsDataStore.data
            .map { it[NAV_SOUNDS_KEY] ?: DefaultNavSounds }
            .first()
        controller.adoptStored(stored)
    }
    return controller
}

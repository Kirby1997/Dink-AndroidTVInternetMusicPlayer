package com.example.dink_smb_player.player

import android.content.Context
import android.media.audiofx.Equalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 11 — multi-band graphic equalizer over the ExoPlayer audio pipeline.
 *
 * Wraps a single [android.media.audiofx.Equalizer] bound to the player's audio session
 * id (set in [PlayerService]). A process-level singleton because the EQ must outlive any
 * one screen — playback runs in the service, and Settings drives the same effect — and
 * because there is exactly one audio session. [PlayerService] owns its lifecycle via
 * [attach] / [release]; Settings observes [state] and calls the mutators.
 *
 * Persistence is SharedPreferences (tiny payload, and synchronous so the stored curve is
 * applied the instant the effect is created, before the first note plays). Band gains are
 * stored in millibels (the AudioEffect unit); presets are computed from each band's centre
 * frequency so they behave consistently regardless of how many bands the device exposes.
 */
object EqEngine {

    data class EqState(
        /** False until [attach] succeeds (device lacks the effect, or no session yet). */
        val available: Boolean = false,
        val enabled: Boolean = false,
        /** Band centre frequencies in Hz, low → high. */
        val freqsHz: List<Int> = emptyList(),
        /** Current per-band gain in millibels (same order as [freqsHz]). */
        val levelsMdB: List<Int> = emptyList(),
        val minMdB: Int = -1500,
        val maxMdB: Int = 1500,
        val presets: List<String> = EqPresets.NAMES,
        /** One of [EqPresets.NAMES]; "Custom" once a band is moved by hand. */
        val currentPreset: String = EqPresets.FLAT,
    )

    private const val PREFS = "dink_eq"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRESET = "preset"
    private const val KEY_LEVELS = "levels" // CSV of millibels

    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state.asStateFlow()

    private var eq: Equalizer? = null
    private var appContext: Context? = null

    /** Create the effect on the player's [sessionId] and apply the persisted curve.
     *  Safe to call repeatedly; a second call rebinds. No-ops (available=false) if the
     *  platform can't provide the effect (some emulators), leaving the UI to say so. */
    @Synchronized
    fun attach(context: Context, sessionId: Int) {
        appContext = context.applicationContext
        release()
        val effect = runCatching { Equalizer(0, sessionId) }.getOrNull()
        if (effect == null) {
            _state.value = EqState(available = false)
            return
        }
        eq = effect
        val prefs = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = effect.numberOfBands.toInt()
        val range = effect.bandLevelRange // [min, max] in mdB
        val minMdB = range[0].toInt()
        val maxMdB = range[1].toInt()
        val freqs = (0 until n).map { effect.getCenterFreq(it.toShort()) / 1000 } // mHz → Hz

        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val preset = prefs.getString(KEY_PRESET, EqPresets.FLAT) ?: EqPresets.FLAT
        val levels = if (preset == EqPresets.CUSTOM) {
            parseLevels(prefs.getString(KEY_LEVELS, null), n, minMdB, maxMdB)
        } else {
            EqPresets.levelsMdB(preset, freqs, minMdB, maxMdB)
        }

        runCatching {
            effect.enabled = enabled
            levels.forEachIndexed { i, l -> effect.setBandLevel(i.toShort(), l.toShort()) }
        }
        _state.value = EqState(
            available = true,
            enabled = enabled,
            freqsHz = freqs,
            levelsMdB = levels,
            minMdB = minMdB,
            maxMdB = maxMdB,
            currentPreset = preset,
        )
    }

    @Synchronized
    fun release() {
        runCatching { eq?.release() }
        eq = null
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        val effect = eq ?: return
        runCatching { effect.enabled = value }
        prefs()?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        _state.value = _state.value.copy(enabled = value)
    }

    /** Move one band by hand → the curve becomes "Custom" and is persisted level-by-level. */
    @Synchronized
    fun setBandLevel(index: Int, levelMdB: Int) {
        val effect = eq ?: return
        val s = _state.value
        if (index !in s.levelsMdB.indices) return
        val clamped = levelMdB.coerceIn(s.minMdB, s.maxMdB)
        runCatching { effect.setBandLevel(index.toShort(), clamped.toShort()) }
        val levels = s.levelsMdB.toMutableList().also { it[index] = clamped }
        _state.value = s.copy(levelsMdB = levels, currentPreset = EqPresets.CUSTOM)
        prefs()?.edit()
            ?.putString(KEY_PRESET, EqPresets.CUSTOM)
            ?.putString(KEY_LEVELS, levels.joinToString(","))
            ?.apply()
    }

    /** Reset every band to 0 dB (the Flat curve). */
    @Synchronized
    fun reset() = applyPreset(EqPresets.FLAT)

    @Synchronized
    fun applyPreset(name: String) {
        val effect = eq ?: return
        val s = _state.value
        // "Custom" isn't a curve — selecting it just keeps the current hand-tuned levels.
        val levels = if (name == EqPresets.CUSTOM) s.levelsMdB
        else EqPresets.levelsMdB(name, s.freqsHz, s.minMdB, s.maxMdB)
        runCatching { levels.forEachIndexed { i, l -> effect.setBandLevel(i.toShort(), l.toShort()) } }
        _state.value = s.copy(levelsMdB = levels, currentPreset = name)
        prefs()?.edit()
            ?.putString(KEY_PRESET, name)
            ?.putString(KEY_LEVELS, levels.joinToString(","))
            ?.apply()
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun parseLevels(csv: String?, n: Int, minMdB: Int, maxMdB: Int): List<Int> {
        val parsed = csv?.split(",")?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        return (0 until n).map { (parsed.getOrNull(it) ?: 0).coerceIn(minMdB, maxMdB) }
    }
}

/** Named gain curves, computed per band centre frequency so they hold up across devices
 *  with different band counts. Gains are in dB; [levelsMdB] converts + clamps to the
 *  device's millibel range. */
object EqPresets {
    const val FLAT = "Flat"
    const val ROCK = "Rock"
    const val VOCAL = "Vocal"
    const val BASS = "Bass"
    const val CUSTOM = "Custom"

    val NAMES = listOf(FLAT, ROCK, VOCAL, BASS, CUSTOM)

    fun levelsMdB(name: String, freqsHz: List<Int>, minMdB: Int, maxMdB: Int): List<Int> =
        freqsHz.map { f -> (gainDb(name, f) * 100).toInt().coerceIn(minMdB, maxMdB) }

    private fun gainDb(name: String, f: Int): Float = when (name) {
        BASS -> when {
            f <= 120 -> 6f
            f <= 400 -> 3f
            f <= 1000 -> 0.5f
            else -> 0f
        }
        ROCK -> when {
            f <= 120 -> 4f
            f <= 400 -> 2f
            f <= 2000 -> -1f
            f > 4000 -> 3f
            else -> 0f
        }
        VOCAL -> when {
            f < 200 -> -2f
            f in 200..3500 -> 4f
            f > 6000 -> 1f
            else -> 0f
        }
        else -> 0f // Flat / Custom (Custom never routed here)
    }
}

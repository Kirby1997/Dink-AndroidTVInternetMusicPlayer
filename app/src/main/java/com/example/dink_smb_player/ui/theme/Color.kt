package com.example.dink_smb_player.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@Immutable
data class DinkPalette(
    val bg0: Color,
    val bg1: Color,
    val bg2: Color,
    val bg3: Color,
    val line: Color,
    val lineStrong: Color,
    val ink0: Color,
    val ink1: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    val accent2: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
) {
    val accentGradient: Brush
        get() = Brush.linearGradient(listOf(accent, accent2))
}

val DarkPalette = DinkPalette(
    bg0 = Color(0xFF06080D),
    bg1 = Color(0xFF0C0F17),
    bg2 = Color(0xFF141826),
    bg3 = Color(0xFF1C2236),
    line = Color(0x14FFFFFF),
    lineStrong = Color(0x24FFFFFF),
    ink0 = Color(0xFFF4F5F7),
    ink1 = Color(0xFFC8CAD3),
    ink2 = Color(0xFF8B8FA0),
    ink3 = Color(0xFF5A5D6C),
    accent = Color(0xFF5B8DFF),
    accent2 = Color(0xFF9B6DFF),
    good = Color(0xFF3DDC97),
    warn = Color(0xFFF0A23A),
    bad = Color(0xFFFF5577),
)

val LightPalette = DinkPalette(
    bg0 = Color(0xFFF6F3EC),
    bg1 = Color(0xFFEBE7DD),
    bg2 = Color(0xFFE0DCCF),
    bg3 = Color(0xFFD2CDBD),
    line = Color(0x14000000),
    lineStrong = Color(0x2E000000),
    ink0 = Color(0xFF16161A),
    ink1 = Color(0xFF3A3A44),
    ink2 = Color(0xFF6A6A78),
    ink3 = Color(0xFF9B9AA6),
    accent = Color(0xFF5B8DFF),
    accent2 = Color(0xFF9B6DFF),
    good = Color(0xFF3DDC97),
    warn = Color(0xFFF0A23A),
    bad = Color(0xFFFF5577),
)

val LocalDinkPalette = staticCompositionLocalOf { DarkPalette }

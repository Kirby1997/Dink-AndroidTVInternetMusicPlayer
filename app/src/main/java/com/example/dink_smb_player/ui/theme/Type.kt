package com.example.dink_smb_player.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Sizes from the 1920×1080 design reference; 1 dp ≈ 1 px at xhdpi so px → sp directly.
@Immutable
data class DinkType(
    val screenTitle: TextStyle = TextStyle(
        fontFamily = SoraFamily,
        fontWeight = FontWeight.Bold,
        // 56sp read oversized on a 10-foot TV and, with the header padding, pushed the
        // Albums/Artists grid down to ~1 visible row. 36sp keeps hierarchy, reclaims height.
        fontSize = 36.sp,
        letterSpacing = (-0.025).em,
    ),
    val sectionTitle: TextStyle = TextStyle(
        fontFamily = SoraFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        letterSpacing = (-0.02).em,
    ),
    val cardTitle: TextStyle = TextStyle(
        fontFamily = SoraFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = (-0.015).em,
    ),
    val buttonLabel: TextStyle = TextStyle(
        fontFamily = SoraFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        letterSpacing = (-0.01).em,
    ),
    val heroTitle: TextStyle = TextStyle(
        fontFamily = InstrumentSerifFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 84.sp,
        lineHeight = (84 * 1.05f).sp,
    ),
    val nowPlayingTitle: TextStyle = TextStyle(
        fontFamily = InstrumentSerifFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 56.sp,
        lineHeight = 60.sp,
    ),
    val songTitle: TextStyle = TextStyle(
        fontFamily = InstrumentSerifFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
    ),
    val songTitleCompact: TextStyle = TextStyle(
        fontFamily = InstrumentSerifFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
    ),
    val lyric: TextStyle = TextStyle(
        fontFamily = InstrumentSerifFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 52.sp,
        lineHeight = 64.sp,
    ),
    val body: TextStyle = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    val bodySmall: TextStyle = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    val mono: TextStyle = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.14.em,
    ),
    val monoSmall: TextStyle = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.18.em,
    ),
    val monoValue: TextStyle = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
    ),
)

val LocalDinkType = staticCompositionLocalOf { DinkType() }

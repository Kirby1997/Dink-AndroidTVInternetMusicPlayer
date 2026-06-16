package com.example.dink_smb_player.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

@Immutable
data class DinkShapes(
    val pill: RoundedCornerShape = RoundedCornerShape(24.dp),
    val pillSmall: RoundedCornerShape = RoundedCornerShape(18.dp),
    val card: RoundedCornerShape = RoundedCornerShape(16.dp),
    val cardSmall: RoundedCornerShape = RoundedCornerShape(14.dp),
    val cardLarge: RoundedCornerShape = RoundedCornerShape(18.dp),
    val modal: RoundedCornerShape = RoundedCornerShape(22.dp),
    val tile: RoundedCornerShape = RoundedCornerShape(12.dp),
    val tileLarge: RoundedCornerShape = RoundedCornerShape(14.dp),
    val statusPill: RoundedCornerShape = RoundedCornerShape(12.dp),
)

val LocalDinkShapes = staticCompositionLocalOf { DinkShapes() }

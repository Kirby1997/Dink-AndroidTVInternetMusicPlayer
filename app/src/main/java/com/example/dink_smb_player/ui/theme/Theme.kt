package com.example.dink_smb_player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette

    // tv-material3 still drives default-component colours; map the subset that matters
    // so built-in widgets blend with the Dink palette. Everything bespoke reads
    // LocalDinkPalette.current directly.
    val tvColorScheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.accent,
            secondary = palette.accent2,
            background = palette.bg0,
            surface = palette.bg1,
            surfaceVariant = palette.bg2,
            onPrimary = palette.ink0,
            onSurface = palette.ink0,
            onSurfaceVariant = palette.ink1,
            border = palette.line,
            borderVariant = palette.lineStrong,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            secondary = palette.accent2,
            background = palette.bg0,
            surface = palette.bg1,
            surfaceVariant = palette.bg2,
            onPrimary = palette.ink0,
            onSurface = palette.ink0,
            onSurfaceVariant = palette.ink1,
            border = palette.line,
            borderVariant = palette.lineStrong,
        )
    }

    CompositionLocalProvider(
        LocalDinkPalette provides palette,
        LocalDinkShapes provides DinkShapes(),
        LocalDinkType provides DinkType(),
    ) {
        MaterialTheme(
            colorScheme = tvColorScheme,
            content = content,
        )
    }
}

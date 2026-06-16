package com.example.dink_smb_player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.example.dink_smb_player.ui.theme.DinkTheme
import com.example.dink_smb_player.ui.theme.LocalThemeController
import com.example.dink_smb_player.ui.theme.ThemeMode
import com.example.dink_smb_player.ui.theme.rememberThemeController

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Theme controller lives ABOVE DinkTheme so the chosen mode picks the palette
            // for the whole tree; Settings flips it via LocalThemeController.
            val theme = rememberThemeController()
            val dark = when (theme.mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
            }
            CompositionLocalProvider(LocalThemeController provides theme) {
                DinkTheme(darkTheme = dark) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RectangleShape,
                    ) {
                        DinkApp()
                    }
                }
            }
        }
    }
}

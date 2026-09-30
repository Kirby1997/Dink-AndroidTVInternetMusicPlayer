package com.example.dink_smb_player.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Screen-as-state navigation. Compose Navigation is URL-shaped; the rail-driven model
 * just swaps [current]. There is no back stack: app-level Back is resolved in DinkApp
 * (`resolveBack`) from the drawer state and [LibraryDetailNav][com.example.dink_smb_player.ui.screens.library.LibraryDetailNav]'s
 * parent / frame stack, and transient screens (wizard, browse) navigate explicitly.
 */
class DinkNavState {
    var current: ScreenId by mutableStateOf(ScreenId.Home)
        private set

    fun go(screen: ScreenId) {
        current = screen
    }
}

@Composable
fun rememberDinkNav(): DinkNavState = remember { DinkNavState() }

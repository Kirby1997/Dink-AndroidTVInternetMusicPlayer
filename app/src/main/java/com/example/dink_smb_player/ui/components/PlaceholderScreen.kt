@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.dink_smb_player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.example.dink_smb_player.LocalContentFocus
import com.example.dink_smb_player.LocalRailFocusRequester
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaceholderScreen(screen: ScreenId) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val contentFocus = LocalContentFocus.current
    val railReq = LocalRailFocusRequester.current

    // Owns a focusable so drawer commit (Enter) can land focus here and the
    // drawer can collapse. No LaunchedEffect auto-grab — focus is moved only
    // when the user actually selects, not on mount.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg0)
            .padding(horizontal = 64.dp, vertical = 48.dp)
            .focusRequester(contentFocus)
            .focusProperties { left = railReq }
            .focusable(),
    ) {
        Column(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "LIBRARY / ${screen.displayName.uppercase()}",
                style = type.monoSmall.copy(color = palette.ink2),
            )
            Text(
                text = screen.displayName,
                style = type.screenTitle.copy(color = palette.ink0),
            )
            Text(
                text = "Coming soon. This screen is wired into navigation; content lands in a later phase.",
                style = type.body.copy(color = palette.ink2),
            )
        }
    }
}

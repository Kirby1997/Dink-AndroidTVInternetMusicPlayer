@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.dink_smb_player.ui.screens.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.example.dink_smb_player.LocalContentFocus
import com.example.dink_smb_player.LocalRailFocusRequester
import com.example.dink_smb_player.player.PlayerState
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkType

/**
 * Cloud Storage — parked. Connecting a provider needs a device-flow OAuth that grants
 * broad read scope; Google's TV device flow blocks `drive.readonly`, so the whole cloud
 * subsystem (browser / importer / streaming `CloudDataSource`) stays in place and
 * compiles but isn't user-actionable. This screen is a "coming soon" placeholder until a
 * provider whose device flow allows broad file scopes (OneDrive is the likely path) is
 * wired up. See PHASES.md "park cloud" decision.
 */
@Composable
fun CloudScreen(
    player: PlayerState,
    onNavigate: (com.example.dink_smb_player.nav.ScreenId) -> Unit,
    onToast: (String) -> Unit,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val contentFocus = LocalContentFocus.current
    val railRequester = LocalRailFocusRequester.current

    // Owns the screen's focusable so a drawer commit (Enter on the rail) lands focus
    // here and the drawer collapses; Left routes back to the rail.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg0)
            .padding(horizontal = 64.dp, vertical = 48.dp)
            .focusRequester(contentFocus)
            .focusProperties { left = railRequester }
            .focusable(),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudOff,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("SOURCES / CLOUD STORAGE", style = type.monoSmall.copy(color = palette.ink3))
                    Text("Cloud Storage", style = type.screenTitle.copy(color = palette.ink0), maxLines = 1)
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.accent.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("COMING SOON", style = type.monoSmall.copy(color = palette.accent))
            }

            Text(
                text = "Streaming from Google Drive, OneDrive and Dropbox is on the way. " +
                    "On a TV, connecting an account needs a sign-in flow that grants broad read " +
                    "access to your files — Google's TV device sign-in currently blocks that scope, " +
                    "so cloud is parked until a provider that allows it is wired up.",
                style = type.body.copy(color = palette.ink1),
            )
            Text(
                text = "Your music stays in the cloud — when this lands, tracks stream on demand and " +
                    "nothing is downloaded to the device. In the meantime, add a NAS via SMB Shares " +
                    "or plug in USB / SD storage.",
                style = type.bodySmall.copy(color = palette.ink2),
            )
        }
    }
}

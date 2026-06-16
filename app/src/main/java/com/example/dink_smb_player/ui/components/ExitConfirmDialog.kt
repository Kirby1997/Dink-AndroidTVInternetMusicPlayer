package com.example.dink_smb_player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkShapes
import com.example.dink_smb_player.ui.theme.LocalDinkType

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ExitConfirmDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val palette = LocalDinkPalette.current
    val shapes = LocalDinkShapes.current
    val type = LocalDinkType.current

    Dialog(onDismissRequest = onCancel) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xAA000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCancel,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .background(palette.bg1, shapes.modal)
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = "EXIT DINK",
                    style = type.monoSmall.copy(color = palette.ink3),
                )
                Text(
                    text = "Stop listening and close the app?",
                    style = type.cardTitle.copy(color = palette.ink0),
                )
                Text(
                    text = "Playback will stop. SMB shares, cloud connections and your library stay saved.",
                    style = type.body.copy(color = palette.ink2),
                )
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        onClick = onCancel,
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(24.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = palette.bg2,
                            focusedContainerColor = palette.bg3,
                            contentColor = palette.ink0,
                            focusedContentColor = palette.ink0,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Keep listening",
                                style = type.buttonLabel.copy(color = palette.ink0),
                            )
                        }
                    }
                    Surface(
                        onClick = onConfirm,
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(24.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = palette.bad,
                            focusedContainerColor = palette.bad,
                            contentColor = palette.ink0,
                            focusedContentColor = palette.ink0,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Exit",
                                style = type.buttonLabel.copy(color = palette.ink0),
                            )
                        }
                    }
                }
            }
        }
    }
}

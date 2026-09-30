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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.delay

@Composable
fun ExitConfirmDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDialog(
        eyebrow = "EXIT DINK",
        title = "Stop listening and close the app?",
        body = "Playback will stop. SMB shares, cloud connections and your library stay saved.",
        cancelLabel = "Keep listening",
        confirmLabel = "Exit",
        onCancel = onCancel,
        onConfirm = onConfirm,
    )
}

/**
 * Modal yes/no for a destructive action. Focus starts on [cancelLabel] so an
 * accidental OK press on the remote dismisses rather than confirms; the confirm
 * button is the red one to its right.
 */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ConfirmDialog(
    eyebrow: String,
    title: String,
    body: String,
    cancelLabel: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    // The dialog window takes a frame or two to attach; retry until the request lands.
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { cancelFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(30)
        }
    }
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
                    text = eyebrow,
                    style = type.monoSmall.copy(color = palette.ink3),
                )
                Text(
                    text = title,
                    style = type.cardTitle.copy(color = palette.ink0),
                )
                Text(
                    text = body,
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
                        // Edge buttons don't wrap: Left off Cancel / Right off the confirm stays put.
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(cancelFocus)
                            .focusProperties { left = FocusRequester.Cancel },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = cancelLabel,
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
                        modifier = Modifier
                            .weight(1f)
                            .focusProperties { right = FocusRequester.Cancel },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = confirmLabel,
                                style = type.buttonLabel.copy(color = palette.ink0),
                            )
                        }
                    }
                }
            }
        }
    }
}

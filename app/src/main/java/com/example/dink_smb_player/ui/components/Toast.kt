package com.example.dink_smb_player.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.example.dink_smb_player.ui.theme.LocalDinkPalette
import com.example.dink_smb_player.ui.theme.LocalDinkShapes
import com.example.dink_smb_player.ui.theme.LocalDinkType
import kotlinx.coroutines.delay

/** Info = a confirmation (green check). Error = a failure (no check, error tint). */
enum class ToastKind { Info, Error }

class ToastState {
    var message: String? by mutableStateOf(null)
        private set
    var kind: ToastKind by mutableStateOf(ToastKind.Info)
        private set
    // Observable so the host's dismiss timer restarts on every show() — including a
    // repeat of the same text, which left `message` unchanged and the old timer running.
    var ticket: Int by mutableIntStateOf(0)
        private set

    fun show(msg: String, kind: ToastKind = ToastKind.Info) {
        message = msg
        this.kind = kind
        ticket++
    }

    fun error(msg: String) = show(msg, ToastKind.Error)

    fun clear() { message = null }
}

@Composable
fun rememberToastState(): ToastState = remember { ToastState() }

/** The app's toast, for call sites that need more than the plain `onToast` lambda
 *  (e.g. [ToastState.error]). Provided by DinkApp. */
val LocalToast = staticCompositionLocalOf<ToastState> { error("LocalToast not provided") }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ToastHost(
    state: ToastState,
    modifier: Modifier = Modifier,
    dismissAfterMs: Long = 2400L,
) {
    val palette = LocalDinkPalette.current
    val type = LocalDinkType.current
    val shapes = LocalDinkShapes.current

    LaunchedEffect(state.ticket) {
        if (state.message != null) {
            delay(dismissAfterMs)
            state.clear()
        }
    }

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = state.message != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 140.dp),
        ) {
            Row(
                modifier = Modifier
                    .background(palette.bg2, shapes.pill)
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val isError = state.kind == ToastKind.Error
                Icon(
                    imageVector = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = if (isError) palette.bad else palette.good,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = state.message ?: "",
                    style = type.body.copy(color = palette.ink0),
                )
            }
        }
    }
}

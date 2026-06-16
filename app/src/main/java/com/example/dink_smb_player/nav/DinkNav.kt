package com.example.dink_smb_player.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Hand-rolled back-stack. Compose Navigation is URL-shaped; the rail-driven model is
 * screen-as-state, so a single ArrayDeque keeps it simpler.
 *
 * Invariant: stack always has at least one entry (Home on construction).
 * `back()` pops and returns false only when the stack is at its single root entry —
 * the caller is then responsible for showing the exit-confirm dialog.
 */
class DinkNavState {
    private val stack: ArrayDeque<ScreenId> = ArrayDeque<ScreenId>().apply { add(ScreenId.Home) }

    var current: ScreenId by mutableStateOf(ScreenId.Home)
        private set

    val canGoBack: Boolean get() = stack.size > 1

    fun go(screen: ScreenId) {
        if (current == screen) return
        stack.addLast(screen)
        current = screen
    }

    /** Returns true if a pop occurred; false if we are already at root (caller may exit). */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeLast()
        current = stack.last()
        return true
    }

    /** Clear stack and replace with a single entry. Used when the user taps a top-level rail item. */
    fun reset(to: ScreenId) {
        stack.clear()
        stack.add(to)
        current = to
    }

    /**
     * Swap the top of the stack with [screen]. Used when a transient screen (e.g.,
     * AddShareWizard) finishes and hands off to a different screen — pressing Back
     * from there should return to whatever was *before* the transient, not to the
     * transient itself.
     */
    fun replaceTop(screen: ScreenId) {
        if (stack.isEmpty()) {
            stack.addLast(screen)
        } else {
            stack.removeLast()
            stack.addLast(screen)
        }
        current = screen
    }
}

@Composable
fun rememberDinkNav(): DinkNavState = remember { DinkNavState() }

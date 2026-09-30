package com.example.dink_smb_player.ui.components

import androidx.compose.ui.focus.FocusRequester
import org.junit.Assert.assertSame
import org.junit.Test

/** UI-1: a shelf's first-card requester is only a valid focus destination while LazyRow
 *  item 0 is composed; scrolled past it, fall back to spatial search. */
class ShelfFirstCardTargetTest {

    @Test
    fun routesToFirstCardWhileItIsTheFirstVisibleItem() {
        val first = FocusRequester()
        assertSame(first, shelfFirstCardTarget(0, first))
    }

    @Test
    fun fallsBackToDefaultOnceScrolledPastFirstCard() {
        val first = FocusRequester()
        assertSame(FocusRequester.Default, shelfFirstCardTarget(1, first))
        assertSame(FocusRequester.Default, shelfFirstCardTarget(7, first))
    }
}

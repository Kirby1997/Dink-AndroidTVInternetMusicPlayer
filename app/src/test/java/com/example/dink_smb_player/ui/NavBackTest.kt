package com.example.dink_smb_player.ui

import com.example.dink_smb_player.BackAction
import com.example.dink_smb_player.crumbsFor
import com.example.dink_smb_player.nav.ScreenId
import com.example.dink_smb_player.resolveBack
import com.example.dink_smb_player.ui.screens.library.LibraryDetailNav
import com.example.dink_smb_player.ui.screens.library.LibraryGroup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** App-level Back precedence (UI-22: open drawer always wins) and detail parents (UI-6). */
class NavBackTest {

    @After
    fun reset() {
        LibraryDetailNav.frames.clear()
    }

    @Test
    fun openDrawerAlwaysMeansExitDialog() {
        assertEquals(BackAction.ExitDialog, resolveBack(true, ScreenId.Home, 0))
        assertEquals(BackAction.ExitDialog, resolveBack(true, ScreenId.LibraryDetail, 2))
        assertEquals(BackAction.ExitDialog, resolveBack(true, ScreenId.SmbBrowse, 0))
    }

    @Test
    fun detailPopsNestedFrameThenReturnsToParent() {
        assertEquals(BackAction.PopDetailFrame, resolveBack(false, ScreenId.LibraryDetail, 2))
        assertEquals(BackAction.DetailToParent, resolveBack(false, ScreenId.LibraryDetail, 1))
        assertEquals(BackAction.FocusRail, resolveBack(false, ScreenId.Search, 0))
    }

    @Test
    fun searchOpenedDetailReturnsToSearch() {
        val g = LibraryGroup("iowa", "Iowa", "Slipknot · 14 tracks", emptyList())
        LibraryDetailNav.open(g, "Album", ScreenId.Search)
        assertEquals(ScreenId.Search, LibraryDetailNav.parent)
        assertEquals("Search / Iowa", crumbsFor(ScreenId.LibraryDetail))
        LibraryDetailNav.open(g, "Album", ScreenId.Albums)
        assertEquals("Library / Albums / Iowa", crumbsFor(ScreenId.LibraryDetail))
    }
}

package com.example.dink_smb_player.ui.screens.library

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** UI-6: Search state survives a detail round-trip; Back refocuses the opened row once. */
class SearchMemoTest {

    @After
    fun reset() {
        SearchMemo.query.value = ""
        SearchMemo.facet.value = SearchFacet.Songs
        SearchMemo.openedKey = null
        SearchMemo.armBackFocus = false
    }

    @Test
    fun queryAndFacetLiveOutsideTheScreen() {
        SearchMemo.query.value = "slip wait"
        SearchMemo.facet.value = SearchFacet.Albums
        // A new SearchScreen composition reads the same holder.
        assertEquals("slip wait", SearchMemo.query.value)
        assertEquals(SearchFacet.Albums, SearchMemo.facet.value)
    }

    @Test
    fun backReturnRefocusesOpenedRowOnce() {
        SearchMemo.rememberOpened("al-iowa")
        SearchMemo.arm()
        assertEquals("al-iowa", SearchMemo.consumeBackFocus(setOf("al-iowa", "al-vol3")))
        assertFalse(SearchMemo.armBackFocus)
        assertNull(SearchMemo.consumeBackFocus(setOf("al-iowa")))
    }

    @Test
    fun notArmedMeansNoRefocus() {
        SearchMemo.rememberOpened("al-iowa")
        // Fresh rail visit: nothing armed.
        assertNull(SearchMemo.consumeBackFocus(setOf("al-iowa")))
    }

    @Test
    fun vanishedRowConsumesArmWithoutTarget() {
        SearchMemo.rememberOpened("al-gone")
        SearchMemo.arm()
        assertNull(SearchMemo.consumeBackFocus(setOf("al-iowa")))
        assertFalse(SearchMemo.armBackFocus)
    }
}

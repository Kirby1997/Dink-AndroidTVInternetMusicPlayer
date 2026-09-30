package com.example.dink_smb_player.ui.screens.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Remove-from-library that cancels an in-flight import must not toast "Imported N". */
class ImportEndToastTest {

    @Test
    fun cancelledByRemoveSaysNothing() {
        assertNull(importEndToast(cancelledByRemove = true, error = null, count = 812))
        assertNull(importEndToast(cancelledByRemove = true, error = "Job was cancelled", count = null))
    }

    @Test
    fun normalEndReportsCountOrError() {
        assertEquals(ImportEndToast.Done(812), importEndToast(false, null, 812))
        assertEquals(ImportEndToast.Done(0), importEndToast(false, null, null))
        assertEquals(ImportEndToast.Failed("timeout"), importEndToast(false, "timeout", 5))
    }

    @Test
    fun removeCancelsOnlyOverlappingOrUnknownImports() {
        assertFalse(removeCancelsImport(importing = false, importingPath = null, removedPath = "Music"))
        assertTrue(removeCancelsImport(true, null, "Music"))
        assertTrue(removeCancelsImport(true, "Music", "Music"))
        assertTrue(removeCancelsImport(true, "Music\\Rock", "Music"))
        assertTrue(removeCancelsImport(true, "", "Music")) // whole-share import
        assertFalse(removeCancelsImport(true, "Podcasts", "Music"))
    }

    // A share delete cancels its import: neither "Imported N" nor "Import failed".
    @Test
    fun deleteInFlightSaysNothing() {
        assertNull(importEndToast(cancelledByRemove = false, error = "closed connection", count = null, deleting = true))
        assertNull(importEndToast(cancelledByRemove = false, error = null, count = 5, deleting = true))
    }

    // Review #4: a late delete completion only navigates back from the screen that asked.
    @Test
    fun leaveAfterDeleteOnlyFromTheLiveScreenForThatShare() {
        assertTrue(leaveAfterDelete(screenAlive = true, browsedShareId = null, deletedId = "a"))
        assertTrue(leaveAfterDelete(screenAlive = true, browsedShareId = "a", deletedId = "a"))
        assertFalse(leaveAfterDelete(screenAlive = true, browsedShareId = "b", deletedId = "a"))
        assertFalse(leaveAfterDelete(screenAlive = false, browsedShareId = null, deletedId = "a"))
    }
}

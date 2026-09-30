package com.example.dink_smb_player.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** UI-21: repeats must restart the host's dismiss timer; errors carry their own kind. */
class ToastStateTest {

    @Test
    fun repeatOfSameMessageBumpsTicket() {
        val t = ToastState()
        t.show("Added to queue")
        val first = t.ticket
        t.show("Added to queue")
        assertNotEquals(first, t.ticket)
        assertEquals("Added to queue", t.message)
    }

    @Test
    fun errorSetsErrorKindAndInfoResetsIt() {
        val t = ToastState()
        t.error("Import failed: timeout")
        assertEquals(ToastKind.Error, t.kind)
        assertEquals("Import failed: timeout", t.message)
        t.show("Imported 12 tracks")
        assertEquals(ToastKind.Info, t.kind)
    }

    @Test
    fun clearDropsMessage() {
        val t = ToastState()
        t.show("x")
        t.clear()
        assertNull(t.message)
    }
}

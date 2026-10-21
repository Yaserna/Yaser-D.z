package com.privatemsg.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name a conversation row shows. Two rules live here: an ordinary row shows
 * the contact name, and a hidden row never does.
 */
class DisplayNamesTest {

    @Test
    fun `an ordinary row shows the contact name`() {
        assertEquals("علی", DisplayNames.forRow("09123456789", null, "علی"))
    }

    @Test
    fun `an ordinary row without a known name shows the number`() {
        assertEquals("09123456789", DisplayNames.forRow("09123456789", null, null))
    }

    @Test
    fun `an empty cached name shows the number`() {
        assertEquals("09123456789", DisplayNames.forRow("09123456789", null, "   "))
    }

    @Test
    fun `a hidden row shows its own label`() {
        assertEquals("کار", DisplayNames.forRow("09123456789", "کار", "علی", hidden = true))
    }

    @Test
    fun `a hidden row without a label never shows the contact name`() {
        // The privacy rule: the contact behind a hidden number stays hidden, so the
        // row shows the number itself even though the name is known.
        assertEquals("09123456789", DisplayNames.forRow("09123456789", null, "علی", hidden = true))
    }

    @Test
    fun `a hidden row whose label was removed shows the number again`() {
        // An empty label means the alias was deleted, so the row must not keep the
        // old name around.
        assertEquals("09123456789", DisplayNames.forRow("09123456789", "", "علی", hidden = true))
    }

    @Test
    fun `an ordinary list may show the contact photo`() {
        assertTrue(DisplayNames.showsPhoto(ownLabels = false))
    }

    @Test
    fun `a list with its own labels never shows the contact photo`() {
        // The privacy rule for pictures: the face behind a hidden number would
        // reveal who it is, so the hidden list shows letters and icons only.
        assertFalse(DisplayNames.showsPhoto(ownLabels = true))
    }
}

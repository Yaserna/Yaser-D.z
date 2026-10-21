package com.privatemsg.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The app hides, mutes, pins and archives by comparing addresses, so "the same
 * number" has to be decided one way and only one way. These tests pin that rule
 * down; they need no device.
 */
class PhoneNumbersTest {

    @Test
    fun `same number written four ways is one number`() {
        val expected = "9123456789"
        assertEquals(expected, PhoneNumbers.normalize("+98 912 345 6789"))
        assertEquals(expected, PhoneNumbers.normalize("00989123456789"))
        assertEquals(expected, PhoneNumbers.normalize("09123456789"))
        assertEquals(expected, PhoneNumbers.normalize("9123456789"))
    }

    @Test
    fun `spaces dashes and brackets are ignored`() {
        val expected = "9123456789"
        assertEquals(expected, PhoneNumbers.normalize("0912 345 6789"))
        assertEquals(expected, PhoneNumbers.normalize("0912-345-6789"))
        assertEquals(expected, PhoneNumbers.normalize("(0912) 345 6789"))
        assertEquals(expected, PhoneNumbers.normalize("+98 (912) 345-6789"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("9123456789", PhoneNumbers.normalize("   09123456789   "))
        assertEquals("9123456789", PhoneNumbers.normalize("\t09123456789\n"))
    }

    @Test
    fun `persian digits become latin`() {
        // U+06F0..U+06F9 (extended Arabic-Indic) as written in Iran.
        assertEquals("9123456789", PhoneNumbers.normalize("۰۹۱۲۳۴۵۶۷۸۹"))
        // U+0660..U+0669 (Arabic-Indic).
        assertEquals("9123456789", PhoneNumbers.normalize("٠٩١٢٣٤٥٦٧٨٩"))
    }

    @Test
    fun `a persian number and its latin spelling are one number`() {
        assertEquals(
            PhoneNumbers.normalize("09123456789"),
            PhoneNumbers.normalize("۰۹۱۲۳۴۵۶۷۸۹")
        )
    }

    @Test
    fun `a short code is kept as typed`() {
        // Too short to be a line number: it is an operator code, not a subscriber.
        assertEquals("1234", PhoneNumbers.normalize("1234"))
        assertEquals("5000", PhoneNumbers.normalize("5000"))
        // A short code with a prefix stays one string too.
        assertEquals("*123#", PhoneNumbers.normalize("*123#"))
    }

    @Test
    fun `an operator name is only lowercased`() {
        assertEquals("hamrah", PhoneNumbers.normalize("Hamrah"))
        assertEquals("itmc", PhoneNumbers.normalize("ITMC"))
    }

    @Test
    fun `different numbers stay different`() {
        assertNotEquals(
            PhoneNumbers.normalize("09123456789"),
            PhoneNumbers.normalize("09123456700")
        )
    }

    @Test
    fun `an empty address stays empty`() {
        assertEquals("", PhoneNumbers.normalize(""))
        assertEquals("", PhoneNumbers.normalize("   "))
    }
}
package com.privatemsg.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat turns runs of digits into tappable phone numbers. These rules decide
 * what counts as one; they need no device.
 */
class NumberRulesTest {

    private fun dialables(body: String): List<String> =
        NumberRules.find(body).map { it.dialable }

    @Test
    fun `a plain number is found`() {
        assertEquals(listOf("09123456789"), dialables("09123456789"))
    }

    @Test
    fun `a number in the middle of a sentence is found`() {
        assertEquals(listOf("09123456789"), dialables("call 09123456789 today"))
    }

    @Test
    fun `a persian number is found and handed over in latin digits`() {
        assertEquals(listOf("09123456789"), dialables("۰۹۱۲۳۴۵۶۷۸۹"))
    }

    @Test
    fun `an arabic-indic number is found and handed over in latin digits`() {
        assertEquals(listOf("09123456789"), dialables("٠٩١٢٣٤٥٦٧٨٩"))
    }

    @Test
    fun `a number split by spaces is found`() {
        assertEquals(listOf("09123456789"), dialables("0912 345 6789"))
    }

    @Test
    fun `a number with a plus keeps it`() {
        assertEquals(listOf("+989123456789"), dialables("+98 912 345 6789"))
    }

    @Test
    fun `three digits are not a number`() {
        assertTrue(dialables("only 123 here").isEmpty())
    }

    @Test
    fun `a time is not a number`() {
        assertTrue(dialables("meet at 12:30").isEmpty())
    }

    @Test
    fun `a date is not a number`() {
        assertTrue(dialables("on 12/03 today").isEmpty())
    }

    @Test
    fun `prose digits separated by spaces are not a number`() {
        assertTrue(dialables("12 34 56").isEmpty())
    }

    @Test
    fun `two numbers side by side are not merged`() {
        // Two separate numbers stay two numbers; they are not glued into one.
        assertEquals(listOf("1111", "2222"), dialables("1111 and 2222"))
    }

    @Test
    fun `a long run of digits split by spaces is prose, not a number`() {
        assertTrue(dialables("1111 2222 3333").isEmpty())
    }
}

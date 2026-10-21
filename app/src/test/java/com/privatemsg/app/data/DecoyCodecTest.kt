package com.privatemsg.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decoy settings live as one blob of tab-separated records. A tab or a newline
 * typed into the fake text used to split a record in two, and the record after it
 * was lost with it. These tests hold that fix in place.
 */
class DecoyCodecTest {

    @Test
    fun `a plain value is returned untouched`() {
        assertEquals("hello", DecoyCodec.escape("hello"))
        assertEquals("hello", DecoyCodec.unescape("hello"))
    }

    @Test
    fun `a tab in a value does not split the record`() {
        val escaped = DecoyCodec.escape("two\tcolumns")
        assertEquals("two\\tcolumns", escaped)
        assertEquals("two\tcolumns", DecoyCodec.unescape(escaped))
    }

    @Test
    fun `a newline in a value does not split the record`() {
        val escaped = DecoyCodec.escape("first\nsecond")
        assertEquals("first\\nsecond", escaped)
        assertEquals("first\nsecond", DecoyCodec.unescape(escaped))
    }

    @Test
    fun `a carriage return in a value does not split the record`() {
        val escaped = DecoyCodec.escape("first\r\nsecond")
        assertEquals("first\\r\\nsecond", escaped)
        assertEquals("first\r\nsecond", DecoyCodec.unescape(escaped))
    }

    @Test
    fun `a backslash survives the round trip`() {
        val escaped = DecoyCodec.escape("C:\\path")
        assertEquals("C:\\\\path", escaped)
        assertEquals("C:\\path", DecoyCodec.unescape(escaped))
    }

    @Test
    fun `escaping is reversible for every awkward character`() {
        val raw = "a\tb\nc\rd\\e"
        assertEquals(raw, DecoyCodec.unescape(DecoyCodec.escape(raw)))
    }

    @Test
    fun `an unknown escape is kept as typed`() {
        // A value written by a newer build must not be mangled by an older one.
        assertEquals("a\\qb", DecoyCodec.unescape("a\\qb"))
    }

    @Test
    fun `a trailing backslash is kept`() {
        assertEquals("a\\", DecoyCodec.unescape("a\\"))
    }

    @Test
    fun `an old plain record still reads back`() {
        // Values written by older builds carry no escapes: unescaping is a no-op.
        val values = DecoyCodec.valuesOf(listOf("key", "name", "text", "target"))
        assertEquals(listOf("name", "text", "target"), values)
    }

    @Test
    fun `a malformed record is dropped instead of guessed`() {
        assertNull(DecoyCodec.valuesOf(listOf("key", "name")))
        assertNull(DecoyCodec.valuesOf(listOf("key")))
        assertNull(DecoyCodec.valuesOf(emptyList()))
    }

    @Test
    fun `a record keeps a tab inside its last field`() {
        val line = DecoyCodec.line("key", listOf("n", "t", "with\ttab"))
        // The key plus three fields: the tab inside the value does not add a field.
        assertEquals(4, DecoyCodec.split(line).size)
        val values = DecoyCodec.valuesOf(DecoyCodec.split(line))
        assertEquals(listOf("n", "t", "with\ttab"), values)
    }

    @Test
    fun `a missing field becomes an empty value`() {
        val line = DecoyCodec.line("key", listOf("only-one"))
        val values = DecoyCodec.valuesOf(DecoyCodec.split(line))
        assertEquals(listOf("only-one", "", ""), values)
    }

    @Test
    fun `a whole blob survives a write and a read`() {
        val entries = listOf(
            Triple("key1", "name\twith tab", "text"),
            Triple("key2", "line\nbreak", "target\\slash")
        )
        val blob = entries.joinToString("\n") { (k, n, t) -> DecoyCodec.line(k, listOf(n, t, "")) }
        val readBack = blob.split("\n").map { DecoyCodec.split(it) }
            .mapNotNull { DecoyCodec.valuesOf(it) }
        assertEquals(2, readBack.size)
        assertEquals("name\twith tab", readBack[0][0])
        assertEquals("line\nbreak", readBack[1][0])
        assertEquals("target\\slash", readBack[1][1])
    }
}
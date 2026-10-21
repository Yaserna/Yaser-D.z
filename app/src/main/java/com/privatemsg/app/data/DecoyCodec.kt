package com.privatemsg.app.data

/**
 * Encoding of the three decoy fields of one address inside the single preference blob.
 *
 * The blob is one record per line and the fields are tab-separated, so a tab or a
 * newline typed into the fake text used to split a record in two: the record was then
 * dropped on the next read and every decoy stored after it was lost as well. Every
 * value is therefore escaped before it is joined.
 *
 * Values written by older builds carry no backslash escapes, and ciphertext is plain
 * Base64, so unescaping them is a no-op and the old records keep working.
 */
object DecoyCodec {

    fun escape(value: String): String {
        if (value.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return value
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun unescape(value: String): String {
        if (value.indexOf('\\') < 0) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    // An escape this build does not know: keep both characters as typed.
                    else -> { sb.append(ch).append(next); i += 2 }
                }
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    /** One line of the blob: the address key and its three escaped values. */
    fun line(key: String, values: List<String>): String =
        buildString {
            append(key)
            for (i in 0..2) {
                append('\t').append(escape(values.getOrElse(i) { "" }))
            }
        }

    /** The parts of one line after the key, unescaped; empty when the line is malformed. */
    fun valuesOf(parts: List<String>): List<String>? {
        if (parts.size < 4) return null
        return listOf(unescape(parts[1]), unescape(parts[2]), unescape(parts[3]))
    }

    /** A blob value split into key + escaped fields, with a tab inside a value kept whole. */
    fun split(line: String): List<String> = line.split('\t', limit = 4)
}

package com.privatemsg.app.ui

import java.util.regex.Pattern

/**
 * The rules that decide which run of digits inside a message is a phone number.
 *
 * Kept free of Android types on purpose: the rules can then be unit-tested
 * without a device.
 */
object NumberRules {

    /**
     * The digit shapes a phone number is written with: ASCII, Persian
     * (U+06F0..U+06F9) and Arabic-Indic (U+0660..U+0669).
     *
     * The class is written out instead of relying on \d with
     * [Pattern.UNICODE_CHARACTER_CLASS]: that flag is not supported on Android
     * and makes Pattern.compile() throw, which took the whole chat screen down
     * with an ExceptionInInitializerError. Android's \d is Unicode-aware
     * anyway, but the explicit class keeps the behaviour identical everywhere.
     */
    private const val DIGIT = "0-9\\u06F0-\\u06F9\\u0660-\\u0669"

    /** A run of digits (with an optional + and spaces) that may be a number. */
    private val NUMBER: Pattern =
        Pattern.compile("\\+?[$DIGIT][$DIGIT ]{2,}[$DIGIT]")

    /** A number found in a message: where it sits and what the dialer should get. */
    data class Match(val start: Int, val end: Int, val dialable: String)

    /** Every number-shaped run in [body], in the order they appear. */
    fun find(body: String): List<Match> {
        val out = ArrayList<Match>()
        val m = NUMBER.matcher(body)
        while (m.find()) {
            val s = m.start()
            val e = m.end()
            val raw = body.substring(s, e)
            val digits = raw.filter { it.isDigit() }
            // Need at least 4 digits to be a meaningful number.
            if (digits.length < 4) continue
            // E.164 caps a real number at 15 digits; longer means the greedy match
            // swallowed several separate runs of prose.
            if (digits.length > 15) continue
            // Only the shapes phone numbers actually come in may be space-split:
            // "+98 21 ..." or "0912 345 ...". "12 34 56" is prose, not a number.
            val firstDigit = Character.digit(digits[0], 10)
            if (firstDigit < 0) continue
            if (raw.contains(' ') && !raw.startsWith("+") && firstDigit != 0) continue
            // Skip times/dates/ranges: matches sitting next to ':' '/' '-'.
            val before = if (s > 0) body[s - 1] else ' '
            val after = if (e < body.length) body[e] else ' '
            if (before == ':' || before == '/' || before == '-' ||
                after == ':' || after == '/' || after == '-'
            ) continue
            // Hand the dialer ASCII digits: a tel: URI built from Persian digits is
            // not parsed by every dialer.
            val dialable = (if (raw.startsWith("+")) "+" else "") +
                digits.map { Character.forDigit(Character.digit(it, 10), 10).toChar() }
                    .joinToString("")
            out.add(Match(s, e, dialable))
        }
        return out
    }
}

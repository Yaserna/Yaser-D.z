package com.privatemsg.app.data

/**
 * One definition of "the same number", used by every comparison in the app.
 *
 * Pure Kotlin on purpose: it decides whether a message is hidden, muted, pinned or
 * archived, so it must be readable and testable without an Android device.
 */
object PhoneNumbers {

    /**
     * Persian/Arabic-Indic digits become Latin, surrounding spaces are trimmed, and a
     * run that is nothing but a real phone number collapses to its last 10 digits (so
     * "+98 912 345 6789", "00989123456789" and "09123456789" all name one thread).
     * Anything else - a short code, an operator name - is only lowercased.
     */
    fun normalize(address: String): String {
        val latin = address.map { ch ->
            when (ch) {
                in '۰'..'۹' -> ('0'.code + (ch - '۰')).toChar()
                in '٠'..'٩' -> ('0'.code + (ch - '٠')).toChar()
                else -> ch
            }
        }.joinToString("").trim()
        val digits = latin.filter { it in '0'..'9' }
        val nonDigitNonSep = latin.count { it !in '0'..'9' && it !in "+-() " }
        return if (nonDigitNonSep == 0 && digits.length >= MIN_PHONE_DIGITS) {
            digits.takeLast(KEPT_DIGITS)
        } else {
            latin.lowercase()
        }
    }

    /** Shorter runs cannot be a line number; they are short codes and stay as typed. */
    private const val MIN_PHONE_DIGITS = 7

    /** Country codes are dropped; the subscriber part is what identifies a thread. */
    private const val KEPT_DIGITS = 10
}

package com.privatemsg.app.data

/**
 * The developer's contact details are assembled at runtime from scrambled bytes so the
 * plain number and address never appear as readable strings in the packaged resources.
 */
object SupportContact {

    private const val MASK = 0x5A

    private val PHONE_BYTES = byteArrayOf(
        113, 99, 98, 99, 106, 105, 111, 111, 106, 111, 107, 111, 106
    )

    private val EMAIL_BYTES = byteArrayOf(
        3, 59, 41, 116, 52, 59, 50, 53, 47, 49, 26, 61, 55, 59, 51, 54, 116, 57, 53, 55
    )

    private fun decode(bytes: ByteArray): String =
        bytes.map { (it.toInt() xor MASK).toChar() }.joinToString("")

    val phone: String by lazy { decode(PHONE_BYTES) }
    val email: String by lazy { decode(EMAIL_BYTES) }
}

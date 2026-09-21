package com.privatemsg.app.data

import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object NumericCipher {

    private const val MAGIC_PREFIX = "8800"

    private val MASTER_KEY = byteArrayOf(
        0x4E.toByte(), 0x79.toByte(), 0x3A.toByte(), 0x1F.toByte(),
        0x92.toByte(), 0xA5.toByte(), 0x4C.toByte(), 0x88.toByte(),
        0x10.toByte(), 0xDF.toByte(), 0x67.toByte(), 0xB3.toByte(),
        0x24.toByte(), 0xE1.toByte(), 0x09.toByte(), 0x5D.toByte()
    )

    data class DecryptedResult(
        val text: String,
        val fromHidden: Boolean
    )

    fun isNumericEncrypted(raw: String): Boolean {
        val trimmed = raw.trim()
        if (!trimmed.startsWith(MAGIC_PREFIX) || trimmed.length < MAGIC_PREFIX.length + 12) {
            return false
        }
        return trimmed.all { it.isDigit() }
    }

    fun encryptToNumeric(plainText: String, fromHidden: Boolean = true): String {
        val random = SecureRandom()
        val iv = ByteArray(16)
        random.nextBytes(iv)

        val textBytes = plainText.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + textBytes.size)
        payload[0] = if (fromHidden) 1 else 0
        System.arraycopy(textBytes, 0, payload, 1, textBytes.size)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(MASTER_KEY, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(payload)

        val combined = ByteArray(1 + iv.size + encrypted.size)
        combined[0] = 0x01
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(encrypted, 0, combined, 1 + iv.size, encrypted.size)

        val bigInt = BigInteger(1, combined)
        return MAGIC_PREFIX + bigInt.toString(10)
    }

    fun decryptFromNumeric(numericText: String): DecryptedResult? {
        val trimmed = numericText.trim()
        if (!isNumericEncrypted(trimmed)) return null
        val digits = trimmed.substring(MAGIC_PREFIX.length)
        return try {
            val bigInt = BigInteger(digits, 10)
            var combined = bigInt.toByteArray()
            if (combined.isNotEmpty() && combined[0] == 0x00.toByte()) {
                combined = combined.copyOfRange(1, combined.size)
            }
            if (combined.isEmpty() || combined[0] != 0x01.toByte() || combined.size < 1 + 16 + 16) {
                return null
            }

            val iv = combined.copyOfRange(1, 17)
            val encrypted = combined.copyOfRange(17, combined.size)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(MASTER_KEY, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = cipher.doFinal(encrypted)

            if (decrypted.isEmpty()) return null
            val fromHidden = decrypted[0].toInt() == 1
            val text = String(decrypted, 1, decrypted.size - 1, Charsets.UTF_8)
            DecryptedResult(text, fromHidden)
        } catch (_: Exception) {
            null
        }
    }
}

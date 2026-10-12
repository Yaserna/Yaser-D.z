package com.privatemsg.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts the text that the private databases store on disk (hidden messages and
 * the hidden side of starred messages), so reading those files gives no content.
 *
 * The key lives in the Android keystore (never exportable). If a device refuses to
 * provide one, a random device-local key is generated and kept in private prefs; the
 * chosen source is remembered so rows already written stay readable.
 */
object DbCipher {

    private const val TAG = "DbCipher"
    private const val KEY_ALIAS = "com.privatemsg.app.hidden.aes"
    private const val PREFS = "cipher_prefs"
    private const val FIELD_SOURCE = "key_source"
    private const val FIELD_FALLBACK = "fallback_key"
    private const val SOURCE_ANDROID = "android"
    private const val SOURCE_LOCAL = "local"

    /** Marks a stored value as encrypted; anything without it is read as plain text. */
    private const val MARK = "enc1:"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private const val FIELD_ADDR_SALT = "addr_salt"

    private val random = SecureRandom()

    /**
     * Stable keyed hash of a lookup value (the private address). Encrypted text cannot
     * be compared directly, so rows are found through this instead. It reveals nothing
     * usable on its own and stays valid across installs of the same app.
     */
    fun lookupHash(context: Context, value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest((saltFor(context) + value).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    @Volatile
    private var cachedSalt: String? = null

    /**
     * One-time read/creation of the address salt. Two threads racing to create it
     * would persist different salts and permanently hide rows hashed with the loser,
     * so creation is serialized and committed synchronously (an async apply could be
     * lost if the process dies right after the first hidden insert).
     */
    private fun saltFor(context: Context): String {
        cachedSalt?.let { return it }
        synchronized(this) {
            cachedSalt?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val salt = prefs.getString(FIELD_ADDR_SALT, null)
                ?: newSalt().also { prefs.edit().putString(FIELD_ADDR_SALT, it).commit() }
            cachedSalt = salt
            return salt
        }
    }

    private fun newSalt(): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Volatile
    private var cachedKey: SecretKey? = null

    fun isEncrypted(value: String): Boolean = value.startsWith(MARK)

    /** Encrypts [plain]; returns it unchanged if the text is empty or encryption fails. */
    fun encrypt(context: Context, plain: String): String {
        if (plain.isEmpty() || isEncrypted(plain)) return plain
        val key = keyFor(context) ?: return plain
        return try {
            val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            MARK + Base64.encodeToString(iv + body, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "encrypt failed", e)
            plain
        }
    }

    /**
     * Reverses [encrypt]; values written before encryption existed come back as-is. A value
     * that carries the mark but this key cannot open comes back empty, never as ciphertext,
     * so an unreadable row cannot print its own encrypted form on screen.
     */
    fun decrypt(context: Context, stored: String): String {
        if (!isEncrypted(stored)) return stored
        val key = keyFor(context) ?: return ""
        return try {
            val raw = Base64.decode(stored.substring(MARK.length), Base64.NO_WRAP)
            if (raw.size <= IV_LENGTH) return ""
            val iv = raw.copyOfRange(0, IV_LENGTH)
            val body = raw.copyOfRange(IV_LENGTH, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "decrypt failed", e)
            ""
        }
    }

    /** True when the value was written ciphered but the current key can no longer open it. */
    fun isUndecryptable(context: Context, stored: String): Boolean =
        isEncrypted(stored) && decrypt(context, stored).isEmpty()

    private fun keyFor(context: Context): SecretKey? {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val source = prefs.getString(FIELD_SOURCE, null) ?: run {
                // Decide once which key store this device will use.
                val chosen = if (androidKey() != null) SOURCE_ANDROID else SOURCE_LOCAL
                prefs.edit().putString(FIELD_SOURCE, chosen).apply()
                chosen
            }
            val key = when (source) {
                SOURCE_ANDROID -> androidKey() ?: localKey(prefs)
                else -> localKey(prefs)
            }
            cachedKey = key
            return key
        }
    }

    private fun androidKey(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: createAndroidKey()
    } catch (e: Exception) {
        Log.e(TAG, "android keystore unavailable", e)
        null
    }

    private fun createAndroidKey(): SecretKey? = try {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        generator.generateKey()
    } catch (e: Exception) {
        Log.e(TAG, "cannot create keystore key", e)
        null
    }

    private fun localKey(prefs: android.content.SharedPreferences): SecretKey? = try {
        val stored = prefs.getString(FIELD_FALLBACK, null)
        if (stored != null) {
            SecretKeySpec(Base64.decode(stored, Base64.NO_WRAP), "AES")
        } else {
            val bytes = ByteArray(32).also { random.nextBytes(it) }
            prefs.edit().putString(FIELD_FALLBACK, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
            SecretKeySpec(bytes, "AES")
        }
    } catch (e: Exception) {
        Log.e(TAG, "no usable key", e)
        null
    }
}

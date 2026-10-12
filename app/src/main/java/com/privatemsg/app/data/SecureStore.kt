package com.privatemsg.app.data

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

class SecureStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)

    /**
     * Address-derived keys used to hold the normalized number in their name, so even with
     * ciphered values the file still listed which numbers are hidden. Every such key now
     * goes through this, which is the same opaque hash hidden.db uses for its address index.
     */
    private fun addrKey(address: String): String {
        ensureAddressKeyMigration()
        return DbCipher.lookupHash(appContext, normalize(address))
    }

    fun hasPin(): Boolean = prefs.contains(KEY_PIN_HASH)

    fun setPin(pin: String) {
        val salt = newSalt()
        prefs.edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, derive(pin.toLatinDigits(), salt, PIN_ITERATIONS))
            .putInt(KEY_PIN_ITERATIONS, PIN_ITERATIONS)
            .remove(KEY_PIN_FAILS)
            .remove(KEY_PIN_LOCK_UNTIL)
            .apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val iterations = prefs.getInt(KEY_PIN_ITERATIONS, 0)
        val computed = derive(pin.toLatinDigits(), salt, iterations)
        val ok = MessageDigest.isEqual(stored.toByteArray(Charsets.UTF_8), computed.toByteArray(Charsets.UTF_8))
        if (ok && iterations == 0) {
            // A PIN stored by an older build: transparently re-hash it with the
            // slower key derivation now that the PIN is known to be correct.
            setPin(pin)
        }
        return ok
    }

    /**
     * Cooldown until which the code is refused everywhere - on the unlock screen and in
     * the search box. Only wrong taps on the unlock screen extend it: a parent searching
     * with an ordinary phone number must never be locked out of their own app.
     */
    fun pinLockUntil(): Long = prefs.getLong(KEY_PIN_LOCK_UNTIL, 0L)

    fun recordPinFailure(): Int {
        val fails = prefs.getInt(KEY_PIN_FAILS, 0) + 1
        val edit = prefs.edit().putInt(KEY_PIN_FAILS, fails)
        if (fails >= PIN_FAIL_LIMIT) {
            val waitMs = PIN_BASE_WAIT_MS * (1L shl (fails - PIN_FAIL_LIMIT).coerceAtMost(4))
            edit.putLong(KEY_PIN_LOCK_UNTIL, System.currentTimeMillis() + waitMs)
        }
        edit.apply()
        return fails
    }

    fun clearPinFailures() {
        prefs.edit().remove(KEY_PIN_FAILS).remove(KEY_PIN_LOCK_UNTIL).apply()
    }

    var decoyName: String
        get() = DbCipher.decrypt(appContext, prefs.getString(KEY_DECOY_NAME, "") ?: "")
        set(v) = prefs.edit().putString(KEY_DECOY_NAME, DbCipher.encrypt(appContext, v)).apply()

    var decoyText: String
        get() = DbCipher.decrypt(appContext, prefs.getString(KEY_DECOY_TEXT, "") ?: "")
        set(v) = prefs.edit().putString(KEY_DECOY_TEXT, DbCipher.encrypt(appContext, v)).apply()

    var decoyTarget: String
        get() = DbCipher.decrypt(appContext, prefs.getString(KEY_DECOY_TARGET, "") ?: "")
        set(v) = prefs.edit().putString(KEY_DECOY_TARGET, DbCipher.encrypt(appContext, v)).apply()

    fun decoyNameFor(address: String): String =
        decoyEntryFor(address)?.get(0)?.takeIf { it.isNotEmpty() } ?: decoyName

    fun decoyTextFor(address: String): String =
        decoyEntryFor(address)?.get(1)?.takeIf { it.isNotEmpty() } ?: decoyText

    fun decoyTargetFor(address: String): String =
        decoyEntryFor(address)?.get(2) ?: decoyTarget

    fun hasCustomDecoy(address: String): Boolean = decoyEntryFor(address) != null

    fun setDecoyFor(address: String, name: String, text: String, target: String) {
        val entries = readDecoys().toMutableMap()
        entries[addrKey(address)] = listOf(
            DbCipher.encrypt(appContext, name),
            DbCipher.encrypt(appContext, text),
            DbCipher.encrypt(appContext, target)
        )
        writeDecoys(entries)
    }

    fun clearDecoyFor(address: String) {
        val entries = readDecoys().toMutableMap()
        entries.remove(addrKey(address))
        writeDecoys(entries)
    }

    /** The three saved decoy fields of one address, already decrypted. */
    private fun decoyEntryFor(address: String): List<String>? =
        readDecoys()[addrKey(address)]?.map { DbCipher.decrypt(appContext, it) }

    /**
     * Decoy settings used to be three separate entries per address, which spelled the
     * hidden number out in the preference file's key names. They now live as one opaque
     * blob under a single neutral key, migrated from the old shape on first use.
     */
    private fun readDecoys(): Map<String, List<String>> {
        ensureDecoyMigration()
        return readDecoysRaw()
    }

    /** Parses the blob without running the migration, so the migration can extend it. */
    private fun readDecoysRaw(): Map<String, List<String>> {
        val blob = prefs.getString(KEY_DECOY_BLOB, "") ?: ""
        if (blob.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        for (line in blob.split("\n")) {
            if (line.isBlank()) continue
            val parts = line.split("\t")
            if (parts.size < 4) continue
            out[parts[0]] = listOf(parts[1], parts[2], parts[3])
        }
        return out
    }

    private fun writeDecoys(entries: Map<String, List<String>>) {
        val blob = entries.entries.joinToString("\n") { (key, values) ->
            "$key\t${values[0]}\t${values[1]}\t${values[2]}"
        }
        prefs.edit().putString(KEY_DECOY_BLOB, blob).apply()
    }

    private fun ensureDecoyMigration() {
        if (decoyMigrated) return
        synchronized(decoyMigrationLock) {
            if (decoyMigrated) return
            val entries = LinkedHashMap<String, List<String>>()
            val legacyNames = mutableSetOf<String>()
            for (prefix in listOf(KEY_DECOY_NAME, KEY_DECOY_TEXT, KEY_DECOY_TARGET)) {
                for (key in prefs.all.keys) {
                    if (key.length > prefix.length + 1 && key.startsWith(prefix + "_")) {
                        legacyNames.add(key.substring(prefix.length + 1))
                    }
                }
            }
            for (n in legacyNames) {
                // Ciphering again is a no-op for values the new build already wrote,
                // and it upgrades the ones left over from the plain-text build.
                entries[DbCipher.lookupHash(appContext, normalize(n))] = listOf(
                    DbCipher.encrypt(appContext, prefs.getString("${KEY_DECOY_NAME}_$n", "") ?: ""),
                    DbCipher.encrypt(appContext, prefs.getString("${KEY_DECOY_TEXT}_$n", "") ?: ""),
                    DbCipher.encrypt(appContext, prefs.getString("${KEY_DECOY_TARGET}_$n", "") ?: "")
                )
            }
            if (legacyNames.isNotEmpty()) {
                // Only touch the blob when there is something to fold into it; rewriting
                // unconditionally would drop the entries of an already-migrated install.
                val edit = prefs.edit()
                val merged = LinkedHashMap(readDecoysRaw()).apply { putAll(entries) }
                edit.putString(
                    KEY_DECOY_BLOB,
                    merged.entries.joinToString("\n") { (key, values) ->
                        "$key\t${values[0]}\t${values[1]}\t${values[2]}"
                    }
                )
                for (n in legacyNames) {
                    edit.remove("${KEY_DECOY_NAME}_$n")
                        .remove("${KEY_DECOY_TEXT}_$n")
                        .remove("${KEY_DECOY_TARGET}_$n")
                }
                edit.apply()
            }
            decoyMigrated = true
        }
    }

    /**
     * One-time rewrite of every address-suffixed preference key and of the pinned and
     * archived address sets, whose members were the numbers themselves. Values are moved
     * as stored; only the names change. Runs before any address read, so a key is never
     * looked up under both shapes.
     */
    private fun ensureAddressKeyMigration() {
        if (addressKeysMigrated) return
        synchronized(addressKeyMigrationLock) {
            if (addressKeysMigrated) return
            // The flag is part of the edit below, so a finished migration is recognised
            // by every later process. Without it a second run would hash the hashes.
            if (prefs.getBoolean(KEY_ADDRESS_KEYS_V2, false)) {
                addressKeysMigrated = true
                return
            }
            val edit = prefs.edit()
            for ((key, value) in prefs.all) {
                val prefix = ADDRESS_KEY_PREFIXES.firstOrNull {
                    key.length > it.length + 1 && key.startsWith("${it}_")
                } ?: continue
                val hashed = "${prefix}_${DbCipher.lookupHash(appContext, key.substring(prefix.length + 1))}"
                when (value) {
                    is String -> edit.putString(hashed, value)
                    is Int -> edit.putInt(hashed, value)
                    is Long -> edit.putLong(hashed, value)
                    is Boolean -> edit.putBoolean(hashed, value)
                    is Float -> edit.putFloat(hashed, value)
                }
                edit.remove(key)
            }
            for (key in listOf(KEY_PINNED_ADDR, KEY_ARCHIVED_ADDR)) {
                val members = prefs.getStringSet(key, emptySet()) ?: emptySet()
                if (members.isNotEmpty()) {
                    edit.putStringSet(
                        key,
                        members.map { DbCipher.lookupHash(appContext, normalize(it)) }.toSet()
                    )
                }
            }
            edit.putBoolean(KEY_ADDRESS_KEYS_V2, true).apply()
            addressKeysMigrated = true
        }
    }

    fun getHiddenNumbers(): Set<String> =
        (prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet())
            .map { DbCipher.decrypt(appContext, it) }
            .filter { it.isNotBlank() }
            .toSet()

    fun addHiddenNumber(number: String) {
        val set = getHiddenNumbers().toMutableSet()
        set.add(number.trim())
        writeHiddenNumbers(set)
    }

    fun removeHiddenNumber(number: String) {
        val target = normalize(number)
        val set = getHiddenNumbers().filterNot { normalize(it) == target }.toSet()
        writeHiddenNumbers(set)
    }

    /**
     * The hidden list is the one artefact that names this section outright, so every
     * entry is stored ciphered. Old plain entries keep working: they read back as-is
     * and are rewritten ciphered by the next add or remove.
     */
    private fun writeHiddenNumbers(numbers: Set<String>) {
        val stored = numbers.filter { it.isNotBlank() }
            .map { DbCipher.encrypt(appContext, it) }
            .toSet()
        prefs.edit().putStringSet(KEY_HIDDEN, stored).apply()
    }

    fun isHidden(address: String): Boolean {
        val n = normalize(address)
        return getHiddenNumbers().any { normalize(it) == n }
    }

    // متدهای نشانی‌محور پایدار
    fun isPinned(address: String): Boolean {
        if (address.isBlank()) return false
        return prefs.getStringSet(KEY_PINNED_ADDR, emptySet())?.contains(addrKey(address)) == true
    }

    fun setPinned(address: String, pinned: Boolean) {
        if (address.isBlank()) return
        val norm = addrKey(address)
        val set = (prefs.getStringSet(KEY_PINNED_ADDR, emptySet()) ?: emptySet()).toMutableSet()
        if (pinned) set.add(norm) else set.remove(norm)
        prefs.edit().putStringSet(KEY_PINNED_ADDR, set).apply()
    }

    fun togglePin(address: String): Boolean {
        if (address.isBlank()) return false
        val norm = addrKey(address)
        val set = (prefs.getStringSet(KEY_PINNED_ADDR, emptySet()) ?: emptySet()).toMutableSet()
        val next = set.add(norm)
        if (!next) set.remove(norm)
        prefs.edit().putStringSet(KEY_PINNED_ADDR, set).apply()
        return next
    }

    // متدهای سازگاری موقت برای threadId
    fun isPinned(threadId: Long): Boolean =
        prefs.getStringSet(KEY_PINNED, emptySet())?.contains(threadId.toString()) == true

    fun togglePin(threadId: Long): Boolean {
        val set = (prefs.getStringSet(KEY_PINNED, emptySet()) ?: emptySet()).toMutableSet()
        val key = threadId.toString()
        val next = set.add(key)
        if (!next) set.remove(key)
        prefs.edit().putStringSet(KEY_PINNED, set).apply()
        return next
    }

    fun isArchived(address: String): Boolean {
        if (address.isBlank()) return false
        return prefs.getStringSet(KEY_ARCHIVED_ADDR, emptySet())?.contains(addrKey(address)) == true
    }

    fun setArchived(address: String, archived: Boolean) {
        if (address.isBlank()) return
        val norm = addrKey(address)
        val set = (prefs.getStringSet(KEY_ARCHIVED_ADDR, emptySet()) ?: emptySet()).toMutableSet()
        if (archived) set.add(norm) else set.remove(norm)
        prefs.edit().putStringSet(KEY_ARCHIVED_ADDR, set).apply()
    }

    fun isArchived(threadId: Long): Boolean =
        prefs.getStringSet(KEY_ARCHIVED, emptySet())?.contains(threadId.toString()) == true

    fun setArchived(threadId: Long, archived: Boolean) {
        val set = (prefs.getStringSet(KEY_ARCHIVED, emptySet()) ?: emptySet()).toMutableSet()
        val key = threadId.toString()
        if (archived) set.add(key) else set.remove(key)
        prefs.edit().putStringSet(KEY_ARCHIVED, set).apply()
    }

    fun getArchived(): Set<Long> =
        prefs.getStringSet(KEY_ARCHIVED, emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()

    var deliveryReportEnabled: Boolean
        get() = prefs.getBoolean(KEY_DELIVERY, true)
        set(v) = prefs.edit().putBoolean(KEY_DELIVERY, v).apply()

    fun deliveryReportForSub(subId: Int): Boolean =
        prefs.getBoolean(KEY_DELIVERY + "_" + subId, deliveryReportEnabled)

    fun setDeliveryReportForSub(subId: Int, enabled: Boolean) =
        prefs.edit().putBoolean(KEY_DELIVERY + "_" + subId, enabled).apply()

    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1f)
        set(v) = prefs.edit().putFloat(KEY_FONT_SCALE, v).apply()

    var uiScale: Float
        get() = prefs.getFloat(KEY_UI_SCALE, 1f)
        set(v) = prefs.edit().putFloat(KEY_UI_SCALE, v).apply()

    var fingerprintEnabled: Boolean
        get() = prefs.getBoolean(KEY_FINGERPRINT, false)
        set(v) = prefs.edit().putBoolean(KEY_FINGERPRINT, v).apply()

    var decoySoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_DECOY_SOUND, false)
        set(v) = prefs.edit().putBoolean(KEY_DECOY_SOUND, v).apply()

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME, THEME_DARK)
        set(v) = prefs.edit().putInt(KEY_THEME, v).apply()

    var appIcon: String
        get() = prefs.getString(KEY_APP_ICON, ICON_DEFAULT) ?: ICON_DEFAULT
        set(v) = prefs.edit().putString(KEY_APP_ICON, v).apply()

    fun hiddenAliasFor(address: String): String? =
        DbCipher.decrypt(
            appContext,
            prefs.getString("${KEY_HIDDEN_ALIAS}_${addrKey(address)}", "") ?: ""
        ).takeIf { it.isNotBlank() }

    fun setHiddenAlias(address: String, name: String) {
        val key = "${KEY_HIDDEN_ALIAS}_${addrKey(address)}"
        if (name.isBlank()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, DbCipher.encrypt(appContext, name.trim())).apply()
    }

    var sentBubbleColor: Int
        get() = prefs.getInt(KEY_SENT_COLOR, DEFAULT_SENT_COLOR)
        set(v) = prefs.edit().putInt(KEY_SENT_COLOR, v).apply()

    var receivedBubbleColor: Int
        get() = prefs.getInt(KEY_RECEIVED_COLOR, DEFAULT_RECEIVED_COLOR)
        set(v) = prefs.edit().putInt(KEY_RECEIVED_COLOR, v).apply()

    fun recordConversationOpened(address: String) {
        if (address.isBlank()) return
        prefs.edit().putLong("${KEY_OPENED}_${addrKey(address)}", System.currentTimeMillis()).apply()
    }

    fun openedAt(address: String): Long =
        if (address.isBlank()) 0L
        else prefs.getLong("${KEY_OPENED}_${addrKey(address)}", 0L)

    fun getDraft(address: String): String =
        if (address.isBlank()) "" else DbCipher.decrypt(
            appContext,
            prefs.getString("${KEY_DRAFT}_${addrKey(address)}", "") ?: ""
        )

    fun setDraft(address: String, text: String) {
        if (address.isBlank()) return
        val key = "${KEY_DRAFT}_${addrKey(address)}"
        if (text.isBlank()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, DbCipher.encrypt(appContext, text)).apply()
    }

    fun getThreadSim(address: String): Int =
        if (address.isBlank()) -1 else prefs.getInt("${KEY_THREAD_SIM}_${addrKey(address)}", -1)

    fun setThreadSim(address: String, subId: Int) {
        if (address.isBlank()) return
        prefs.edit().putInt("${KEY_THREAD_SIM}_${addrKey(address)}", subId).apply()
    }

    fun isMuted(address: String): Boolean =
        if (address.isBlank()) false
        else prefs.getBoolean("${KEY_MUTED}_${addrKey(address)}", false)

    fun setMuted(address: String, muted: Boolean) {
        if (address.isBlank()) return
        val key = "${KEY_MUTED}_${addrKey(address)}"
        if (muted) prefs.edit().putBoolean(key, true).apply()
        else prefs.edit().remove(key).apply()
    }

    fun toggleMute(address: String): Boolean {
        val next = !isMuted(address)
        setMuted(address, next)
        return next
    }

    fun isEncryptionEnabled(address: String): Boolean =
        if (address.isBlank()) true
        else prefs.getBoolean("${KEY_ENC_MODE}_${addrKey(address)}", true)

    fun setEncryptionEnabled(address: String, enabled: Boolean) {
        if (address.isBlank()) return
        prefs.edit().putBoolean("${KEY_ENC_MODE}_${addrKey(address)}", enabled).apply()
    }

    fun toggleEncryption(address: String): Boolean {
        val next = !isEncryptionEnabled(address)
        setEncryptionEnabled(address, next)
        return next
    }

    fun isHoneypotEnabled(address: String): Boolean =
        if (address.isBlank()) true
        else prefs.getBoolean("${KEY_HONEYPOT_MODE}_${addrKey(address)}", true)

    fun setHoneypotEnabled(address: String, enabled: Boolean) {
        if (address.isBlank()) return
        prefs.edit().putBoolean("${KEY_HONEYPOT_MODE}_${addrKey(address)}", enabled).apply()
    }

    fun toggleHoneypot(address: String): Boolean {
        val next = !isHoneypotEnabled(address)
        setHoneypotEnabled(address, next)
        return next
    }

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_ITERATIONS = "pin_iterations"
        private const val KEY_PIN_FAILS = "pin_fails"
        private const val KEY_PIN_LOCK_UNTIL = "pin_lock_until"
        private const val PIN_ITERATIONS = 20000
        private const val PIN_FAIL_LIMIT = 5
        private const val PIN_BASE_WAIT_MS = 30_000L
        private const val KEY_DECOY_NAME = "decoy_name"
        private const val KEY_DECOY_TEXT = "decoy_text"
        private const val KEY_DECOY_TARGET = "decoy_target"
        private const val KEY_DECOY_BLOB = "decoy_entries"
        private val decoyMigrationLock = Any()

        @Volatile
        private var decoyMigrated = false

        private const val KEY_DRAFT = "draft"
        private const val KEY_THREAD_SIM = "sim"
        private const val KEY_OPENED = "opened"
        private const val KEY_ENC_MODE = "enc_mode"
        private const val KEY_HONEYPOT_MODE = "honeypot_mode"
        private const val KEY_ADDRESS_KEYS_V2 = "address_keys_v2"
        private val addressKeyMigrationLock = Any()

        @Volatile
        private var addressKeysMigrated = false
        private const val KEY_HIDDEN = "hidden_numbers"
        private const val KEY_PINNED = "pinned_threads"
        private const val KEY_PINNED_ADDR = "pinned_addresses"
        private const val KEY_ARCHIVED = "archived_threads"
        private const val KEY_ARCHIVED_ADDR = "archived_addresses"
        private const val KEY_DELIVERY = "delivery_report"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_UI_SCALE = "ui_scale"
        private const val KEY_FINGERPRINT = "fingerprint_unlock"
        private const val KEY_DECOY_SOUND = "decoy_sound"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_APP_ICON = "app_icon"
        private const val KEY_HIDDEN_ALIAS = "hidden_alias"
        private const val KEY_MUTED = "muted_thread"

        /** Key names that used to end in the normalized number itself. */
        private val ADDRESS_KEY_PREFIXES = listOf(
            KEY_HIDDEN_ALIAS, KEY_MUTED, KEY_DRAFT, KEY_THREAD_SIM,
            KEY_OPENED, KEY_ENC_MODE, KEY_HONEYPOT_MODE
        )

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        const val ICON_DEFAULT = "IconDefault"
        private const val KEY_SENT_COLOR = "sent_bubble_color"
        private const val KEY_RECEIVED_COLOR = "received_bubble_color"

        const val DEFAULT_SENT_COLOR = 0xFF1FA055.toInt()
        const val DEFAULT_RECEIVED_COLOR = 0xFF2C2C2E.toInt()

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
            return if (nonDigitNonSep == 0 && digits.length >= 7) {
                digits.takeLast(10)
            } else {
                latin.lowercase()
            }
        }

        private fun newSalt(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Old builds stored a single salted SHA-256; that maps to iterations = 0. */
        private fun hash(pin: String, salt: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val out = md.digest((salt + pin).toByteArray(Charsets.UTF_8))
            return out.joinToString("") { "%02x".format(it) }
        }

        private fun derive(pin: String, salt: String, iterations: Int): String {
            if (iterations <= 0) return hash(pin, salt)
            val keySpec = javax.crypto.spec.PBEKeySpec(
                pin.toCharArray(),
                salt.toByteArray(Charsets.UTF_8),
                iterations,
                256
            )
            val factory = try {
                javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            } catch (_: Exception) {
                javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            }
            return factory.generateSecret(keySpec).encoded.joinToString("") { "%02x".format(it) }
        }
    }
}

fun String.toLatinDigits(): String {
    val sb = java.lang.StringBuilder(length)
    for (ch in this) {
        sb.append(
            when (ch) {
                in '۰'..'۹' -> '0' + (ch - '۰')
                in '٠'..'٩' -> '0' + (ch - '٠')
                else -> ch
            }
        )
    }
    return sb.toString()
}

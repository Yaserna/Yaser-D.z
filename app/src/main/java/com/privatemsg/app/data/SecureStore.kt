package com.privatemsg.app.data

import android.content.Context
import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom

class SecureStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)

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
            .remove(KEY_PIN_LOCK_BOOT_MS)
            .remove(KEY_PIN_LOCK_BOOT_WRITTEN)
            .apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return false
        // A corrupted preference must not turn one unlock into a multi-second freeze.
        val iterations = prefs.getInt(KEY_PIN_ITERATIONS, 0).coerceIn(0, MAX_PIN_ITERATIONS)
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
     * Milliseconds still to wait before the code is accepted again - on the unlock screen
     * and in the search box. Only wrong taps there extend it: a parent searching with an
     * ordinary phone number must never be locked out of their own app.
     *
     * The wait is measured on the clock that counts up since the phone booted, which no
     * setting can move; measuring it with the wall clock let anyone skip it by winding the
     * date forward. A reboot restarts that clock from zero, so after one the wall clock is
     * the only thing left to judge the wait by.
     */
    fun pinLockRemaining(): Long {
        val wallLeft = (prefs.getLong(KEY_PIN_LOCK_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
        val bootDeadline = prefs.getLong(KEY_PIN_LOCK_BOOT_MS, 0L)
        if (bootDeadline == 0L) return wallLeft
        val elapsed = SystemClock.elapsedRealtime()
        return if (elapsed < prefs.getLong(KEY_PIN_LOCK_BOOT_WRITTEN, 0L)) wallLeft
        else (bootDeadline - elapsed).coerceAtLeast(0L)
    }

    fun recordPinFailure(): Int {
        val fails = prefs.getInt(KEY_PIN_FAILS, 0) + 1
        val edit = prefs.edit().putInt(KEY_PIN_FAILS, fails)
        if (fails >= PIN_FAIL_LIMIT) {
            val waitMs = PIN_BASE_WAIT_MS * (1L shl (fails - PIN_FAIL_LIMIT).coerceAtMost(4))
            val elapsed = SystemClock.elapsedRealtime()
            edit.putLong(KEY_PIN_LOCK_UNTIL, System.currentTimeMillis() + waitMs)
                .putLong(KEY_PIN_LOCK_BOOT_MS, elapsed + waitMs)
                .putLong(KEY_PIN_LOCK_BOOT_WRITTEN, elapsed)
        }
        edit.apply()
        return fails
    }

    fun clearPinFailures() {
        prefs.edit().remove(KEY_PIN_FAILS)
            .remove(KEY_PIN_LOCK_UNTIL)
            .remove(KEY_PIN_LOCK_BOOT_MS)
            .remove(KEY_PIN_LOCK_BOOT_WRITTEN)
            .apply()
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
            val parts = DecoyCodec.split(line)
            out[parts[0]] = DecoyCodec.valuesOf(parts) ?: continue
        }
        return out
    }

    /** One blob, every record escaped: a tab or newline in a decoy text cannot split it. */
    private fun blobOf(entries: Map<String, List<String>>): String =
        entries.entries.joinToString("\n") { (key, values) -> DecoyCodec.line(key, values) }

    private fun writeDecoys(entries: Map<String, List<String>>) {
        prefs.edit().putString(KEY_DECOY_BLOB, blobOf(entries)).apply()
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
                edit.putString(KEY_DECOY_BLOB, blobOf(merged))
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

    /**
     * The hidden list is the one artefact that names this section outright, so every
     * entry is stored ciphered. Old plain entries keep working: they read back as-is
     * and are rewritten ciphered by the next add or remove.
     */
    fun getHiddenNumbers(): Set<String> = hiddenEntries()
        .map { DbCipher.decrypt(appContext, it) }
        .filter { it.isNotBlank() }
        .toSet()

    /** The stored forms, ciphered and legacy-plain alike. */
    private fun hiddenEntries(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()

    fun addHiddenNumber(number: String) {
        val clean = number.trim()
        if (clean.isEmpty()) return
        // Re-adding the same line in another spelling ("+98 912…", "۰۹۱۲…") must replace
        // the old entry instead of listing the number twice in the hidden section.
        val target = normalize(clean)
        val set = getHiddenNumbers().filterNot { normalize(it) == target }.toMutableSet()
        set.add(clean)
        writeHiddenNumbers(set)
    }

    fun removeHiddenNumber(number: String) {
        val target = normalize(number)
        val set = getHiddenNumbers().filterNot { normalize(it) == target }.toSet()
        writeHiddenNumbers(set)
    }

    private fun writeHiddenNumbers(numbers: Set<String>) {
        val stored = numbers.filter { it.isNotBlank() }
            .map { DbCipher.encrypt(appContext, it) }
            .toSet()
        // Entries this key can no longer open decrypt to nothing and would be dropped by
        // the rewrite above; keep them so an unrelated edit cannot erase them.
        val kept = hiddenEntries().filter {
            DbCipher.isUndecryptable(appContext, it)
        }.toSet()
        prefs.edit().putStringSet(KEY_HIDDEN, stored + kept).apply()
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

    var deliveryReportEnabled: Boolean
        get() = prefs.getBoolean(KEY_DELIVERY, true)
        set(v) = prefs.edit().putBoolean(KEY_DELIVERY, v).apply()

    fun deliveryReportForSub(subId: Int): Boolean =
        prefs.getBoolean(KEY_DELIVERY + "_" + subId, deliveryReportEnabled)

    fun setDeliveryReportForSub(subId: Int, enabled: Boolean) =
        prefs.edit().putBoolean(KEY_DELIVERY + "_" + subId, enabled).apply()

    // These three feed every screen's configuration (attachBaseContext). A value the
    // pickers can never produce - a corrupted preference file, a future build with a
    // different option table - would otherwise be re-applied on each recreate and leave
    // the app unreadable with no in-app way to fix it, so each getter clamps.
    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1f).coerceIn(0.5f, 2f)
        set(v) = prefs.edit().putFloat(KEY_FONT_SCALE, v.coerceIn(0.5f, 2f)).apply()

    var uiScale: Float
        get() = prefs.getFloat(KEY_UI_SCALE, 1f).coerceIn(0.5f, 2f)
        set(v) = prefs.edit().putFloat(KEY_UI_SCALE, v.coerceIn(0.5f, 2f)).apply()

    var fingerprintEnabled: Boolean
        get() = prefs.getBoolean(KEY_FINGERPRINT, false)
        set(v) = prefs.edit().putBoolean(KEY_FINGERPRINT, v).apply()

    var decoySoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_DECOY_SOUND, false)
        set(v) = prefs.edit().putBoolean(KEY_DECOY_SOUND, v).apply()

    /** 0 = system, 1 = light, 2 = dark; anything else falls back to dark. */
    var themeMode: Int
        get() = prefs.getInt(KEY_THEME, THEME_DARK).coerceIn(THEME_SYSTEM, THEME_DARK)
        set(v) = prefs.edit().putInt(KEY_THEME, v.coerceIn(THEME_SYSTEM, THEME_DARK)).apply()

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

    /**
     * Ids of rows the honeypot deliberately wrote into the public store: a message from a
     * hidden number that arrived without the private wire mark is shown as an ordinary
     * SMS so an intruder finds a believable chat. The sweep that clears hidden numbers out
     * of the provider must skip them, or the lure would vanish from the visible app (the
     * one trace the intruder is meant to keep) and resurface as a real conversation inside
     * the private section.
     */
    fun rememberLure(messageId: Long) {
        if (messageId <= 0) return
        val ids = lureIds().toMutableSet()
        ids.add(messageId)
        // Only the newest ids matter; an unbounded set would grow for the life of the
        // install, and a lure is read or deleted within days.
        val trimmed = ids.sorted().takeLast(LURE_LIMIT).toSet()
        prefs.edit().putStringSet(KEY_LURE_IDS, trimmed.map { it.toString() }.toSet()).apply()
    }

    /**
     * The ids of every lure row, read once. The sweep walks the whole message store and
     * would otherwise re-read the preferences for every single row it looks at.
     */
    fun lureIds(): Set<Long> =
        (prefs.getStringSet(KEY_LURE_IDS, emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_ITERATIONS = "pin_iterations"
        private const val KEY_PIN_FAILS = "pin_fails"
        private const val KEY_PIN_LOCK_UNTIL = "pin_lock_until"
        private const val KEY_PIN_LOCK_BOOT_MS = "pin_lock_boot_ms"
        private const val KEY_PIN_LOCK_BOOT_WRITTEN = "pin_lock_boot_written"
        private const val PIN_ITERATIONS = 20000

        /** Upper bound when reading the stored count back; anything above is corruption. */
        private const val MAX_PIN_ITERATIONS = 200_000
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
        private const val KEY_PINNED_ADDR = "pinned_addresses"
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
        private const val KEY_LURE_IDS = "lure_message_ids"

        /** How many lure ids are remembered before the oldest are dropped. */
        private const val LURE_LIMIT = 200

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

        /** Kept as the app-wide entry point; the rules themselves live in [PhoneNumbers]. */
        fun normalize(address: String): String = PhoneNumbers.normalize(address)

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

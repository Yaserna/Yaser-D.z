package com.privatemsg.app.data

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

class SecureStore(context: Context) {

    private val prefs = context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)

    fun hasPin(): Boolean = prefs.contains(KEY_PIN_HASH)

    fun setPin(pin: String) {
        val salt = newSalt()
        prefs.edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, hash(pin.toLatinDigits(), salt))
            .apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val computed = hash(pin.toLatinDigits(), salt)
        return MessageDigest.isEqual(stored.toByteArray(Charsets.UTF_8), computed.toByteArray(Charsets.UTF_8))
    }

    var decoyName: String
        get() = prefs.getString(KEY_DECOY_NAME, "") ?: ""
        set(v) = prefs.edit().putString(KEY_DECOY_NAME, v).apply()

    var decoyText: String
        get() = prefs.getString(KEY_DECOY_TEXT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_DECOY_TEXT, v).apply()

    var decoyTarget: String
        get() = prefs.getString(KEY_DECOY_TARGET, "") ?: ""
        set(v) = prefs.edit().putString(KEY_DECOY_TARGET, v).apply()

    fun decoyNameFor(address: String): String =
        prefs.getString(KEY_DECOY_NAME + "_" + normalize(address), null) ?: decoyName

    fun decoyTextFor(address: String): String =
        prefs.getString(KEY_DECOY_TEXT + "_" + normalize(address), null) ?: decoyText

    fun decoyTargetFor(address: String): String =
        prefs.getString(KEY_DECOY_TARGET + "_" + normalize(address), null) ?: decoyTarget

    fun hasCustomDecoy(address: String): Boolean {
        val n = normalize(address)
        return prefs.contains(KEY_DECOY_NAME + "_" + n) ||
            prefs.contains(KEY_DECOY_TEXT + "_" + n) ||
            prefs.contains(KEY_DECOY_TARGET + "_" + n)
    }

    fun setDecoyFor(address: String, name: String, text: String, target: String) {
        val n = normalize(address)
        prefs.edit()
            .putString(KEY_DECOY_NAME + "_" + n, name)
            .putString(KEY_DECOY_TEXT + "_" + n, text)
            .putString(KEY_DECOY_TARGET + "_" + n, target)
            .apply()
    }

    fun clearDecoyFor(address: String) {
        val n = normalize(address)
        prefs.edit()
            .remove(KEY_DECOY_NAME + "_" + n)
            .remove(KEY_DECOY_TEXT + "_" + n)
            .remove(KEY_DECOY_TARGET + "_" + n)
            .apply()
    }

    fun getHiddenNumbers(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN, emptySet())?.toSet() ?: emptySet()

    fun addHiddenNumber(number: String) {
        val set = getHiddenNumbers().toMutableSet()
        set.add(number.trim())
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    fun removeHiddenNumber(number: String) {
        val target = normalize(number)
        val set = getHiddenNumbers().filterNot { normalize(it) == target }.toSet()
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    fun isHidden(address: String): Boolean {
        val n = normalize(address)
        return getHiddenNumbers().any { normalize(it) == n }
    }

    // متدهای نشانی‌محور پایدار
    fun isPinned(address: String): Boolean {
        if (address.isBlank()) return false
        return prefs.getStringSet(KEY_PINNED_ADDR, emptySet())?.contains(normalize(address)) == true
    }

    fun togglePin(address: String): Boolean {
        if (address.isBlank()) return false
        val norm = normalize(address)
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
        return prefs.getStringSet(KEY_ARCHIVED_ADDR, emptySet())?.contains(normalize(address)) == true
    }

    fun setArchived(address: String, archived: Boolean) {
        if (address.isBlank()) return
        val norm = normalize(address)
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

    var archiveKeyword: String
        get() = prefs.getString(KEY_ARCHIVE_WORD, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ARCHIVE_WORD
        set(v) = prefs.edit().putString(KEY_ARCHIVE_WORD, v.trim()).apply()

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
        prefs.getString(KEY_HIDDEN_ALIAS + "_" + normalize(address), null)?.takeIf { it.isNotBlank() }

    fun setHiddenAlias(address: String, name: String) {
        val key = KEY_HIDDEN_ALIAS + "_" + normalize(address)
        if (name.isBlank()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, name.trim()).apply()
    }

    var sentBubbleColor: Int
        get() = prefs.getInt(KEY_SENT_COLOR, DEFAULT_SENT_COLOR)
        set(v) = prefs.edit().putInt(KEY_SENT_COLOR, v).apply()

    var receivedBubbleColor: Int
        get() = prefs.getInt(KEY_RECEIVED_COLOR, DEFAULT_RECEIVED_COLOR)
        set(v) = prefs.edit().putInt(KEY_RECEIVED_COLOR, v).apply()

    fun recordConversationOpened(address: String) {
        if (address.isBlank()) return
        prefs.edit().putLong("opened_" + normalize(address), System.currentTimeMillis()).apply()
    }

    fun openedAt(address: String): Long =
        if (address.isBlank()) 0L else prefs.getLong("opened_" + normalize(address), 0L)

    fun getDraft(address: String): String =
        if (address.isBlank()) "" else prefs.getString("draft_" + normalize(address), "") ?: ""

    fun setDraft(address: String, text: String) {
        if (address.isBlank()) return
        val key = "draft_" + normalize(address)
        if (text.isBlank()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, text).apply()
    }

    fun getThreadSim(address: String): Int =
        if (address.isBlank()) -1 else prefs.getInt("sim_" + normalize(address), -1)

    fun setThreadSim(address: String, subId: Int) {
        if (address.isBlank()) return
        prefs.edit().putInt("sim_" + normalize(address), subId).apply()
    }

    fun isMuted(address: String): Boolean =
        if (address.isBlank()) false else prefs.getBoolean(KEY_MUTED + "_" + normalize(address), false)

    fun setMuted(address: String, muted: Boolean) {
        if (address.isBlank()) return
        val key = KEY_MUTED + "_" + normalize(address)
        if (muted) prefs.edit().putBoolean(key, true).apply()
        else prefs.edit().remove(key).apply()
    }

    fun toggleMute(address: String): Boolean {
        val next = !isMuted(address)
        setMuted(address, next)
        return next
    }

    fun isEncryptionEnabled(address: String): Boolean =
        if (address.isBlank()) true else prefs.getBoolean("enc_mode_" + normalize(address), true)

    fun setEncryptionEnabled(address: String, enabled: Boolean) {
        if (address.isBlank()) return
        prefs.edit().putBoolean("enc_mode_" + normalize(address), enabled).apply()
    }

    fun toggleEncryption(address: String): Boolean {
        val next = !isEncryptionEnabled(address)
        setEncryptionEnabled(address, next)
        return next
    }

    fun isHoneypotEnabled(address: String): Boolean =
        if (address.isBlank()) true else prefs.getBoolean("honeypot_mode_" + normalize(address), true)

    fun setHoneypotEnabled(address: String, enabled: Boolean) {
        if (address.isBlank()) return
        prefs.edit().putBoolean("honeypot_mode_" + normalize(address), enabled).apply()
    }

    fun toggleHoneypot(address: String): Boolean {
        val next = !isHoneypotEnabled(address)
        setHoneypotEnabled(address, next)
        return next
    }

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_DECOY_NAME = "decoy_name"
        private const val KEY_DECOY_TEXT = "decoy_text"
        private const val KEY_DECOY_TARGET = "decoy_target"
        private const val KEY_HIDDEN = "hidden_numbers"
        private const val KEY_PINNED = "pinned_threads"
        private const val KEY_PINNED_ADDR = "pinned_addresses"
        private const val KEY_ARCHIVED = "archived_threads"
        private const val KEY_ARCHIVED_ADDR = "archived_addresses"
        private const val KEY_ARCHIVE_WORD = "archive_keyword"
        const val DEFAULT_ARCHIVE_WORD = "بایگانی"
        private const val KEY_DELIVERY = "delivery_report"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_UI_SCALE = "ui_scale"
        private const val KEY_FINGERPRINT = "fingerprint_unlock"
        private const val KEY_DECOY_SOUND = "decoy_sound"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_APP_ICON = "app_icon"
        private const val KEY_HIDDEN_ALIAS = "hidden_alias"
        private const val KEY_MUTED = "muted_thread"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        const val ICON_DEFAULT = "IconDefault"
        private const val KEY_SENT_COLOR = "sent_bubble_color"
        private const val KEY_RECEIVED_COLOR = "received_bubble_color"

        const val DEFAULT_SENT_COLOR = 0xFF1FA055.toInt()
        const val DEFAULT_RECEIVED_COLOR = 0xFF2C2C2E.toInt()

        fun normalize(address: String): String {
            val trimmed = address.trim()
            val digits = trimmed.filter { it.isDigit() }
            val nonDigitNonSep = trimmed.count { !it.isDigit() && it !in "+-() " }
            return if (nonDigitNonSep == 0 && digits.length >= 7) {
                digits.takeLast(10)
            } else {
                trimmed.lowercase()
            }
        }

        private fun newSalt(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun hash(pin: String, salt: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val out = md.digest((salt + pin).toByteArray(Charsets.UTF_8))
            return out.joinToString("") { "%02x".format(it) }
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

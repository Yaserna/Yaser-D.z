package com.privatemsg.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/** Resolves a phone number to its saved contact name. */
class ContactsHelper(private val context: Context) {

    /** Contact name if known, otherwise the number exactly as it was dialled. */
    fun displayFor(number: String): String {
        if (number.isBlank()) return "نامشخص"
        if (!hasReadAccess()) {
            // Nothing cached under a revoked permission is trustworthy.
            clearCaches()
            return number
        }
        val key = keyFor(number)
        cache[key]?.let { return it }
        val resolved = lookupName(number) ?: number
        // Only a real name is stored: a miss may just mean "this number was written in a
        // form the lookup didn't match", and that must be re-asked after every change.
        if (resolved != number) cache[key] = resolved
        return resolved
    }

    /** Uncached lookup, for one-off questions such as the tap-a-number menu. */
    fun nameFor(number: String): String? {
        if (number.isBlank()) return null
        if (!hasReadAccess()) {
            clearCaches()
            return null
        }
        return lookupName(number)
    }

    /** Contact photo (thumbnail) URI for a number, or null if none/unknown. */
    fun photoUriFor(number: String): String? {
        if (number.isBlank() || !hasReadAccess()) return null
        val key = keyFor(number)
        photoCache[key]?.let { return it.ifEmpty { null } }
        var uri: String? = null
        try {
            uri = photoQuery(number)
        } catch (e: SecurityException) {
            // Permission was revoked mid-query; the next call clears through the check.
            return null
        }
        if (uri != null) photoCache[key] = uri
        return uri
    }

    private fun hasReadAccess(): Boolean = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    private fun lookupName(number: String): String? = try {
        nameQuery(number)
    } catch (e: SecurityException) {
        null
    }

    private fun nameQuery(number: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    private fun photoQuery(number: String): String? {
        val lookup = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
        )
        context.contentResolver.query(
            lookup, arrayOf(ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    private fun keyFor(number: String): String = SecureStore.normalize(number)

    private val invalidator = object : ContentObserver(Handler()) {
        override fun onChange(selfChange: Boolean) {
            val access = hasReadAccess()
            if (!access || access != lastAccess) {
                lastAccess = access
                clearCaches()
            }
        }
    }

    init {
        // One listener per process: any contact edit lands on the contacts Uri, and a
        // revoked permission already clears everything on the next read - so what this
        // listener is really for is a name that was edited or added while the app slept.
        if (!watchInstalled) {
            watchInstalled = true
            lastAccess = hasReadAccess()
            context.applicationContext.contentResolver.registerContentObserver(
                ContactsContract.CONTENT_URI, true, invalidator
            )
        }
    }

    companion object {
        // Shared across instances so a number is looked up at most once per session.
        // Keyed by the normalized number, so "0912..." and "+98912..." share one entry.
        private val cache = ConcurrentHashMap<String, String>()
        private val photoCache = ConcurrentHashMap<String, String>()
        private var watchInstalled = false
        private var lastAccess = true

        fun clearCaches() {
            cache.clear()
            photoCache.clear()
        }
    }
}

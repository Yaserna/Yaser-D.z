package com.privatemsg.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/** Resolves a phone number to its saved contact name. */
class ContactsHelper(context: Context) {

    /**
     * Only the application context is kept: instances live in the process-wide caches
     * below (through the content observer), so holding an Activity here would leak the
     * whole screen that created it.
     */
    private val appContext = context.applicationContext

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
        if (number.isBlank()) return null
        if (!hasReadAccess()) {
            clearCaches()
            return null
        }
        val key = keyFor(number)
        // "" is the stored form of "this number has no photo" - without it every list row
        // would re-query the contacts provider on each bind.
        photoCache[key]?.let { return it.ifEmpty { null } }
        val uri = try {
            photoQuery(number)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the query: drop what was cached
            // under the old grant so no stale photo survives the next bind.
            clearCaches()
            return null
        }
        photoCache[key] = uri ?: ""
        return uri
    }

    private fun hasReadAccess(): Boolean = ContextCompat.checkSelfPermission(
        appContext, android.Manifest.permission.READ_CONTACTS
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
        appContext.contentResolver.query(
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
        appContext.contentResolver.query(
            lookup, arrayOf(ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    private fun keyFor(number: String): String = SecureStore.normalize(number)

    init {
        installWatcher(appContext)
    }

    companion object {
        // Shared across instances so a number is looked up at most once per session.
        // Keyed by the normalized number, so "0912..." and "+98912..." share one entry.
        private val cache = ConcurrentHashMap<String, String>()
        private val photoCache = ConcurrentHashMap<String, String>()

        /**
         * Bumped whenever the shared caches are dropped, which happens when the
         * contacts change or the permission is revoked. A list that already tried
         * and found no name for a number uses this to know it may ask again.
         */
        private val generation = java.util.concurrent.atomic.AtomicInteger(0)

        /** The current cache generation; a different value means the answers may differ. */
        fun cacheGeneration(): Int = generation.get()

        @Volatile
        private var watchInstalled = false
        private val watchLock = Any()

        /** One listener per process; two racing constructors must not install two. */
        private fun installWatcher(context: Context) {
            if (watchInstalled) return
            synchronized(watchLock) {
                if (watchInstalled) return
                watchInstalled = true
                val app = context.applicationContext
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        cache.clear()
                        photoCache.clear()
                        generation.incrementAndGet()
                    }
                }
                app.contentResolver.registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI, true, observer
                )
            }
        }

        fun clearCaches() {
            cache.clear()
            photoCache.clear()
            generation.incrementAndGet()
        }
    }
}

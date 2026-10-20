package com.privatemsg.app.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.privatemsg.app.data.HiddenDbHelper
import java.util.concurrent.Executors

/**
 * Receives the "sent" and "delivered" callbacks for an outgoing SMS and updates
 * that message's row, so the chat can show one tick (sent) / two ticks (delivered).
 */
class SmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Everything the worker needs must be read here: the extras object is dead once
        // onReceive returns, and the row updates below are disk work that would eat the
        // ten seconds Android allows a broadcast - a long SMS fires one callback per part.
        val appContext = context.applicationContext
        val action = intent.action
        val id = intent.getLongExtra(EXTRA_HIDDEN_ID, -1)
        val uri = intent.data
        val partTotal = intent.getIntExtra(EXTRA_PART_TOTAL, 1)
        val ok = resultCode == Activity.RESULT_OK
        val pkg = context.packageName

        val pending = goAsync()
        executor.execute {
            try {
                handle(appContext, action, id, uri, partTotal, ok, pkg)
            } catch (_: Exception) {
                // A lost status report leaves the previous tick; it must not crash the app.
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(
        context: Context,
        action: String?,
        id: Long,
        uri: android.net.Uri?,
        partTotal: Int,
        ok: Boolean,
        pkg: String
    ) {
        // Delivery report for a hidden message: update the private DB (no provider Uri).
        if (action == ACTION_HIDDEN_DELIVERED) {
            if (id < 0) return
            val (complete, failed) = PartTracker(context)
                .report("hdel_$id", partTotal, ok)
            if (complete && failed == 0) {
                HiddenDbHelper.getInstance(context).updateStatus(id, 0)
                context.sendBroadcast(Intent(ACTION_HIDDEN_REFRESH).setPackage(pkg))
            }
            return
        }

        // Sent callback for a hidden message: queued → sent (or failed), in the private DB.
        if (action == ACTION_HIDDEN_SENT) {
            if (id < 0) return
            val (complete, failed) = PartTracker(context)
                .report("hsent_$id", partTotal, ok)
            if (complete) {
                HiddenDbHelper.getInstance(context).updateType(
                    id,
                    if (failed == 0) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED
                )
                context.sendBroadcast(Intent(ACTION_HIDDEN_REFRESH).setPackage(pkg))
            }
            return
        }

        if (uri == null) return
        when (action) {
            // The visible row is written as "sent" up front, so a success report adds
            // nothing; only a failure changes it. Waiting for every part also stops a
            // late success from overwriting a part that failed.
            ACTION_SENT -> if (!ok) {
                tryUpdate(context, uri, ContentValues().apply {
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
                })
                val address = try {
                    context.contentResolver.query(
                        uri, arrayOf(Telephony.Sms.ADDRESS), null, null, null
                    )?.use { if (it.moveToFirst()) it.getString(0) else null }
                } catch (e: Exception) {
                    null
                }
                if (!address.isNullOrBlank()) Notifier.showSendFailed(context, address)
            }

            ACTION_DELIVERED -> {
                // A multi-part message is "delivered" only when every part reported in.
                val (complete, failed) = PartTracker(context)
                    .report("del_$uri", partTotal, ok)
                if (complete && failed == 0) {
                    tryUpdate(context, uri, ContentValues().apply { put(Telephony.Sms.STATUS, 0) })
                }
            }

            else -> return
        }
    }

    private fun tryUpdate(context: Context, uri: android.net.Uri, values: ContentValues) {
        try {
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // Row may be gone; ignore.
        }
    }

    /**
     * Counts status callbacks per outgoing message. A long SMS fires one callback per
     * radio part, so the row must only flip when all parts have reported; failures are
     * accumulated so a single bad part marks the whole message.
     *
     * A part whose callback never arrives (radio drop, phone rebooted) would otherwise
     * leave its tally in the preference file forever, so each message also records when
     * its first report landed and stale tallies are thrown away.
     */
    private class PartTracker(context: Context) {
        private val prefs =
            context.applicationContext.getSharedPreferences(TRACK_PREFS, Context.MODE_PRIVATE)

        fun report(key: String, partTotal: Int, ok: Boolean): Pair<Boolean, Int> {
            val total = partTotal.coerceAtLeast(1)
            val now = System.currentTimeMillis()
            val startedAt = prefs.getLong(key + "_t", 0L)
            // No timestamp, or one older than the drop window: this is a fresh message,
            // not another part of something abandoned long ago.
            val fresh = startedAt == 0L || now - startedAt > STALE_MS
            val done = if (fresh) 0 else prefs.getInt(key + "_d", 0)
            val failed = if (fresh) 0 else prefs.getInt(key + "_f", 0)
            val completed = done + 1 >= total
            val edit = prefs.edit()
            if (completed) edit.remove(key + "_d").remove(key + "_f").remove(key + "_t")
            else edit.putLong(key + "_t", if (fresh) now else startedAt)
                .putInt(key + "_d", done + 1)
                .putInt(key + "_f", failed + if (ok) 0 else 1)
            edit.apply()
            if (prefs.all.size > PRUNE_AT) pruneStale(now)
            return completed to (failed + if (ok) 0 else 1)
        }

        /** Drops tallies whose message stopped reporting. */
        private fun pruneStale(now: Long) {
            val stale = prefs.all.keys
                .filter { it.endsWith("_t") && now - (prefs.all[it] as? Long ?: 0L) > STALE_MS }
                .map { it.removeSuffix("_t") }
            if (stale.isEmpty()) return
            val edit = prefs.edit()
            for (key in stale) edit.remove(key + "_d").remove(key + "_f").remove(key + "_t")
            edit.apply()
        }

        companion object {
            private const val TRACK_PREFS = "part_track"

            /** A message still waiting for a report after this long is considered gone. */
            private const val STALE_MS = 6 * 60 * 60 * 1000L

            /** How many tracked tallies are tolerated before a sweep. */
            private const val PRUNE_AT = 200
        }
    }

    companion object {
        const val ACTION_SENT = "com.privatemsg.app.SMS_SENT"
        const val ACTION_DELIVERED = "com.privatemsg.app.SMS_DELIVERED"
        const val ACTION_HIDDEN_DELIVERED = "com.privatemsg.app.HIDDEN_SMS_DELIVERED"
        const val ACTION_HIDDEN_SENT = "com.privatemsg.app.HIDDEN_SMS_SENT"
        const val ACTION_HIDDEN_REFRESH = "com.privatemsg.app.HIDDEN_SMS_REFRESH"
        const val ACTION_SMS_REFRESH = "com.privatemsg.app.SMS_REFRESH"
        const val EXTRA_HIDDEN_ID = "hidden_id"
        const val EXTRA_PART_TOTAL = "part_total"

        /**
         * Identity-only Uri for hidden-row callbacks. Nothing is ever resolved against
         * it; it only makes each message's pending intent unique (the intent is explicit,
         * so no content provider is consulted).
         */
        private val HIDDEN_STATUS_URI: android.net.Uri =
            android.net.Uri.parse("content://com.privatemsg.app.hidden/status")

        // One worker keeps the per-message part tallies strictly ordered.
        private val executor = Executors.newSingleThreadExecutor()

        /**
         * Callback for one row of the system store. [partTotal] must be the number of
         * radio parts that row produced, otherwise the first part to report decides the
         * fate of the whole message.
         */
        fun statusIntent(
            context: android.content.Context,
            action: String,
            uri: android.net.Uri,
            partTotal: Int
        ): android.app.PendingIntent {
            val intent = android.content.Intent(context, SmsStatusReceiver::class.java)
                .setAction(action)
                .setData(uri)
                .putExtra(EXTRA_PART_TOTAL, partTotal)
            // The data Uri makes the filter unique, so a colliding request code cannot
            // hand one message's callback to another row.
            val code = (uri.toString() + action).hashCode() and 0x7FFFFFFF
            return android.app.PendingIntent.getBroadcast(
                context, code, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** Callback for a row of the private hidden database (no provider Uri exists). */
        fun hiddenStatusIntent(
            context: android.content.Context,
            action: String,
            rowId: Long,
            partTotal: Int
        ): android.app.PendingIntent {
            val intent = android.content.Intent(context, SmsStatusReceiver::class.java)
                .setAction(action)
                // A distinct data Uri per message: without it the row id alone as the
                // request code can collide across the 32-bit boundary and, worse,
                // FLAG_UPDATE_CURRENT would rewrite an older pending intent.
                .setData(android.content.ContentUris.withAppendedId(HIDDEN_STATUS_URI, rowId))
                .putExtra(EXTRA_HIDDEN_ID, rowId)
                .putExtra(EXTRA_PART_TOTAL, partTotal)
            val code = ("$action#$rowId").hashCode() and 0x7FFFFFFF
            return android.app.PendingIntent.getBroadcast(
                context, code, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}

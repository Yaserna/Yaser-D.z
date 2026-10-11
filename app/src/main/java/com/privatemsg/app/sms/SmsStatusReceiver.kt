package com.privatemsg.app.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.privatemsg.app.data.HiddenDbHelper

/**
 * Receives the "sent" and "delivered" callbacks for an outgoing SMS and updates
 * that message's row, so the chat can show one tick (sent) / two ticks (delivered).
 */
class SmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Delivery report for a hidden message: update the private DB (no provider Uri).
        if (intent.action == ACTION_HIDDEN_DELIVERED) {
            val id = intent.getLongExtra(EXTRA_HIDDEN_ID, -1)
            if (id < 0) return
            val (complete, failed) = PartTracker(context)
                .report("hdel_$id", intent.getIntExtra(EXTRA_PART_TOTAL, 1), resultCode == Activity.RESULT_OK)
            if (complete && failed == 0) {
                HiddenDbHelper.getInstance(context).updateStatus(id, 0)
                context.sendBroadcast(Intent(ACTION_HIDDEN_REFRESH).setPackage(context.packageName))
            }
            return
        }

        // Sent callback for a hidden message: queued → sent (or failed), in the private DB.
        if (intent.action == ACTION_HIDDEN_SENT) {
            val id = intent.getLongExtra(EXTRA_HIDDEN_ID, -1)
            if (id < 0) return
            val (complete, failed) = PartTracker(context)
                .report("hsent_$id", intent.getIntExtra(EXTRA_PART_TOTAL, 1), resultCode == Activity.RESULT_OK)
            if (complete) {
                HiddenDbHelper.getInstance(context).updateType(
                    id,
                    if (failed == 0) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED
                )
                context.sendBroadcast(Intent(ACTION_HIDDEN_REFRESH).setPackage(context.packageName))
            }
            return
        }

        val uri = intent.data ?: return
        val ok = resultCode == Activity.RESULT_OK
        when (intent.action) {
            // The visible row is written as "sent" up front, so a success report adds
            // nothing; only a failure changes it. Waiting for every part also stops a
            // late success from overwriting a part that failed.
            ACTION_SENT -> if (!ok) {
                val values = ContentValues().apply {
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
                }
                tryUpdate(context, uri, values)
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
                    .report("del_$uri", intent.getIntExtra(EXTRA_PART_TOTAL, 1), ok)
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
     */
    private class PartTracker(context: Context) {
        private val prefs =
            context.applicationContext.getSharedPreferences(TRACK_PREFS, Context.MODE_PRIVATE)

        fun report(key: String, partTotal: Int, ok: Boolean): Pair<Boolean, Int> {
            val total = partTotal.coerceAtLeast(1)
            val done = prefs.getInt(key + "_d", 0) + 1
            val failed = prefs.getInt(key + "_f", 0) + if (ok) 0 else 1
            val complete = done >= total
            val edit = prefs.edit()
            if (complete) edit.remove(key + "_d").remove(key + "_f")
            else edit.putInt(key + "_d", done).putInt(key + "_f", failed)
            edit.apply()
            return complete to failed
        }

        companion object {
            private const val TRACK_PREFS = "part_track"
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
    }
}

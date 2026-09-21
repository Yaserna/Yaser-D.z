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
            if (id >= 0) {
                HiddenDbHelper(context).updateStatus(id, 0)
                context.sendBroadcast(Intent(ACTION_HIDDEN_REFRESH).setPackage(context.packageName))
            }
            return
        }

        val uri = intent.data ?: return
        val failed = intent.action == ACTION_SENT && resultCode != Activity.RESULT_OK
        val values = ContentValues()
        when (intent.action) {
            ACTION_SENT -> values.put(
                Telephony.Sms.TYPE,
                if (resultCode == Activity.RESULT_OK) Telephony.Sms.MESSAGE_TYPE_SENT
                else Telephony.Sms.MESSAGE_TYPE_FAILED
            )
            ACTION_DELIVERED -> values.put(Telephony.Sms.STATUS, 0)
            else -> return
        }
        try {
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // Row may be gone; ignore.
        }
        // A send failed → alert the user (the conversation also turns red in the list).
        if (failed) {
            val address = try {
                context.contentResolver.query(
                    uri, arrayOf(Telephony.Sms.ADDRESS), null, null, null
                )?.use { if (it.moveToFirst()) it.getString(0) else null }
            } catch (e: Exception) {
                null
            }
            if (!address.isNullOrBlank()) Notifier.showSendFailed(context, address)
        }
    }

    companion object {
        const val ACTION_SENT = "com.privatemsg.app.SMS_SENT"
        const val ACTION_DELIVERED = "com.privatemsg.app.SMS_DELIVERED"
        const val ACTION_HIDDEN_DELIVERED = "com.privatemsg.app.HIDDEN_SMS_DELIVERED"
        const val ACTION_HIDDEN_REFRESH = "com.privatemsg.app.HIDDEN_SMS_REFRESH"
        const val ACTION_SMS_REFRESH = "com.privatemsg.app.SMS_REFRESH"
        const val EXTRA_HIDDEN_ID = "hidden_id"
    }
}

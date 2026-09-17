package com.privatemsg.app.sms

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.privatemsg.app.data.HiddenDbHelper
import com.privatemsg.app.data.SecureStore

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val address = messages[0].originatingAddress ?: ""
        val body = StringBuilder()
        var date = System.currentTimeMillis()
        for (m in messages) {
            body.append(m.messageBody)
            date = m.timestampMillis
        }
        val text = body.toString()

        val subId = listOf("subscription", "android.telephony.extra.SUBSCRIPTION_INDEX", "subscription_index", "sub_id").firstNotNullOfOrNull { key -> intent.getIntExtra(key, -1).takeIf { it >= 0 } } ?: -1

        val secure = SecureStore(context)
        if (secure.isHidden(address)) {
            // Hidden sender: store privately, never touch the system store,
            // and show only the decoy notification.
            HiddenDbHelper(context).insert(address, text, date, INBOX, subId)
            if (SecureStore.normalize(address) ==
                com.privatemsg.app.ui.HiddenConversationActivity.activeNormalizedAddress
            ) {
                Notifier.vibrateTiny(context)
            } else {
                Notifier.showDecoy(context, secure, address)
            }
            // Tell an open hidden conversation to reload, so the new message shows live.
            context.sendBroadcast(
                Intent(SmsStatusReceiver.ACTION_HIDDEN_REFRESH).setPackage(context.packageName)
            )
            return
        }

        // Normal sender: as the default SMS app we write to the system store.
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, text)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            if (subId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        }
        val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        val messageId = uri?.lastPathSegment?.toLongOrNull() ?: -1

        // If this very conversation is open on screen, don't post a notification —
        // the open chat updates live; just give a tiny vibration instead.
        if (SecureStore.normalize(address) ==
            com.privatemsg.app.ui.ConversationActivity.activeNormalizedAddress
        ) {
            Notifier.vibrateTiny(context)
        } else {
            Notifier.showIncoming(context, address, text, messageId)
        }
    }

    companion object {
        private const val INBOX = 1
    }
}

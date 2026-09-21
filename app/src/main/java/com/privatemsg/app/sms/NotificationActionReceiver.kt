package com.privatemsg.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.privatemsg.app.R
import com.privatemsg.app.data.SmsRepository

/** Handles the Reply / Mark-read / Delete buttons on a real (non-decoy) notification. */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra("address") ?: ""
        val threadId = intent.getLongExtra("thread_id", -1)
        val messageId = intent.getLongExtra("message_id", -1)
        val notifId = intent.getIntExtra("notif_id", 0)
        val subId = intent.getIntExtra("sub_id", -1)
        val repo = SmsRepository(context)

        when (intent.action) {
            ACTION_REPLY -> {
                val reply = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
                if (reply.isEmpty() || address.isEmpty()) return
                try {
                    val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val base = context.getSystemService(SmsManager::class.java)
                        if (subId >= 0) base.createForSubscriptionId(subId) else base
                    } else {
                        @Suppress("DEPRECATION")
                        if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
                    }
                    val parts = sm.divideMessage(reply)
                    if (parts.size > 1) {
                        sm.sendMultipartTextMessage(address, null, parts, null, null)
                    } else {
                        sm.sendTextMessage(address, null, reply, null, null)
                    }
                    repo.storeSentMessage(address, reply, subId)
                    context.sendBroadcast(
                        Intent(SmsStatusReceiver.ACTION_SMS_REFRESH).setPackage(context.packageName)
                    )
                    Toast.makeText(context, R.string.reply_sent, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, R.string.reply_failed, Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_MARK_READ -> {
                if (threadId >= 0) {
                    repo.markThreadRead(threadId)
                    context.sendBroadcast(
                        Intent(SmsStatusReceiver.ACTION_SMS_REFRESH).setPackage(context.packageName)
                    )
                }
            }
            ACTION_DELETE -> {
                if (messageId >= 0) {
                    repo.deleteMessage(messageId)
                    context.sendBroadcast(
                        Intent(SmsStatusReceiver.ACTION_SMS_REFRESH).setPackage(context.packageName)
                    )
                }
            }
        }
        NotificationManagerCompat.from(context).cancel(notifId)
    }

    companion object {
        const val ACTION_REPLY = "com.privatemsg.app.NOTIF_REPLY"
        const val ACTION_MARK_READ = "com.privatemsg.app.NOTIF_READ"
        const val ACTION_DELETE = "com.privatemsg.app.NOTIF_DELETE"
        const val KEY_REPLY = "key_reply"
    }
}
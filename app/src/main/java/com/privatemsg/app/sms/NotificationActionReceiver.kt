package com.privatemsg.app.sms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.privatemsg.app.R
import com.privatemsg.app.data.SecureStore
import com.privatemsg.app.data.SmsRepository
import java.util.concurrent.Executors

/** Handles the Reply / Mark-read / Delete buttons on a real (non-decoy) notification. */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Read everything on this thread; the extras must not be touched after returning.
        val appContext = context.applicationContext
        val action = intent.action
        val address = intent.getStringExtra("address") ?: ""
        val threadId = intent.getLongExtra("thread_id", -1)
        val messageId = intent.getLongExtra("message_id", -1)
        val notifId = intent.getIntExtra("notif_id", 0)
        val subId = intent.getIntExtra("sub_id", -1)
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()

        val pending = goAsync()
        executor.execute {
            try {
                handle(appContext, action, address, threadId, messageId, notifId, subId, reply)
            } catch (_: Exception) {
                // The notification is already gone; a failed action leaves the store untouched.
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(
        context: Context,
        action: String?,
        address: String,
        threadId: Long,
        messageId: Long,
        notifId: Int,
        subId: Int,
        reply: String
    ) {
        val repo = SmsRepository(context)
        when (action) {
            ACTION_REPLY -> {
                if (reply.isEmpty() || address.isEmpty()) return
                // Store first: if the process dies mid-send the reply is still in the
                // conversation instead of vanished while the other side already has it.
                val uri = repo.storeSentMessage(address, reply, subId)
                context.sendBroadcast(
                    Intent(SmsStatusReceiver.ACTION_SMS_REFRESH).setPackage(context.packageName)
                )
                val sent = try {
                    sendViaSms(context, address, reply, subId, uri)
                    true
                } catch (e: Exception) {
                    android.util.Log.e("NotifAction", "canned reply send failed", e)
                    false
                }
                toast(
                    context,
                    if (sent) R.string.reply_sent else R.string.reply_failed
                )
            }

            ACTION_MARK_READ -> if (threadId >= 0) {
                repo.markThreadRead(threadId)
                context.sendBroadcast(
                    Intent(SmsStatusReceiver.ACTION_SMS_REFRESH).setPackage(context.packageName)
                )
            }

            ACTION_DELETE -> if (messageId >= 0) {
                repo.deleteMessage(messageId)
                context.sendBroadcast(
                    Intent(SmsStatusReceiver.ACTION_SMS_REFRESH).setPackage(context.packageName)
                )
            }
        }
        NotificationManagerCompat.from(context).cancel(notifId)
    }

    private fun sendViaSms(
        context: Context,
        address: String,
        body: String,
        subId: Int,
        rowUri: android.net.Uri?
    ) {
        val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subId >= 0) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
        // Same status callbacks the chat screen registers, so a failed reply is marked
        // here too; without them the row would keep an optimistic tick forever.
        val sentPi = rowUri?.let {
            statusIntent(context, SmsStatusReceiver.ACTION_SENT, it, 1)
        }
        val deliveredPi = if (rowUri != null && SecureStore(context).deliveryReportForSub(subId)) {
            statusIntent(context, SmsStatusReceiver.ACTION_DELIVERED, rowUri, 1)
        } else {
            null
        }
        val parts = sm.divideMessage(body)
        if (parts.size > 1) {
            sm.sendMultipartTextMessage(
                address, null, parts,
                sentPi?.let { List(parts.size) { it } },
                deliveredPi?.let { List(parts.size) { it } }
            )
        } else {
            sm.sendTextMessage(address, null, body, sentPi, deliveredPi)
        }
    }

    private fun statusIntent(
        context: Context,
        action: String,
        uri: android.net.Uri,
        partTotal: Int
    ): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java)
            .setAction(action)
            .setData(uri)
            .putExtra(SmsStatusReceiver.EXTRA_PART_TOTAL, partTotal)
        return PendingIntent.getBroadcast(
            context, uri.toString().hashCode() and 0x7FFFFFFF, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun toast(context: Context, textRes: Int) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, textRes, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_REPLY = "com.privatemsg.app.NOTIF_REPLY"
        const val ACTION_MARK_READ = "com.privatemsg.app.NOTIF_READ"
        const val ACTION_DELETE = "com.privatemsg.app.NOTIF_DELETE"
        const val KEY_REPLY = "key_reply"
        private val executor = Executors.newSingleThreadExecutor()
    }
}

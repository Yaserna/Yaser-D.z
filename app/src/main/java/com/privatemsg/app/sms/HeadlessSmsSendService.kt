package com.privatemsg.app.sms

import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import com.privatemsg.app.data.HiddenDbHelper
import com.privatemsg.app.data.NumericCipher
import com.privatemsg.app.data.SecureStore
import com.privatemsg.app.data.SimHelper
import com.privatemsg.app.data.SmsRepository
import java.util.ArrayList
import java.util.concurrent.Executors

/**
 * Android's lock-screen "Respond via message" entry point. The service must exist for the
 * app to qualify as the default SMS app, but it also has to actually send: a canned reply
 * the system accepts and this app silently drops tells the user the other side was answered
 * when nothing ever left the phone.
 */
class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val addresses = intent?.getStringArrayExtra(EXTRA_DESTINATIONS)?.toList()
            ?: listOfNotNull(intent?.data?.schemeSpecificPart)
        val body = intent?.getStringExtra(EXTRA_SMS_BODY)?.trim()
        if (addresses.none { it.isNotBlank() } || body.isNullOrEmpty()) return START_NOT_STICKY

        // Writing the row and handing the message to the radio is disk + IPC work, and a
        // started service runs on the main thread, so the send goes to a worker.
        val app = applicationContext
        executor.execute {
            addresses.filter { it.isNotBlank() }.forEach { address ->
                try {
                    reply(app, address, body)
                } catch (_: Exception) {
                    // One unreachable number must not stop the others.
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun reply(context: Context, address: String, body: String) {
        val secure = SecureStore(context)
        val normalized = SecureStore.normalize(address)
        val isHidden = secure.isHidden(address) ||
            (normalized.isNotBlank() && secure.isHidden(normalized))
        val savedSim = secure.getThreadSim(address)
        val subId = if (savedSim >= 0) savedSim else SimHelper(context).defaultSubId()
        val manager = smsManagerFor(context, subId)

        if (isHidden) {
            // A reply on a hidden line must not leave a trace in the public store: it goes
            // into the private DB in the same wire format the other side expects.
            val hiddenDb = HiddenDbHelper.getInstance(context)
            val textToSend = if (secure.isEncryptionEnabled(address)) {
                NumericCipher.encryptToNumeric(body, fromHidden = true)
            } else {
                "$body a+"
            }
            val partTotal = manager.divideMessage(textToSend).size.coerceAtLeast(1)
            val rowId = hiddenDb.insert(address, textToSend, System.currentTimeMillis(), 4, subId)
            send(
                manager, address, textToSend,
                hiddenIntent(context, SmsStatusReceiver.ACTION_HIDDEN_SENT, rowId, partTotal),
                if (secure.deliveryReportForSub(subId))
                    hiddenIntent(context, SmsStatusReceiver.ACTION_HIDDEN_DELIVERED, rowId, partTotal)
                else null
            )
            context.sendBroadcast(
                Intent(SmsStatusReceiver.ACTION_HIDDEN_REFRESH).setPackage(context.packageName)
            )
            return
        }

        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
        val uri = SmsRepository(context).storeSentMessage(address, body, subId)
        val partTotal = manager.divideMessage(body).size.coerceAtLeast(1)
        val sent = send(
            manager, address, body,
            uri?.let { statusIntent(context, SmsStatusReceiver.ACTION_SENT, it, partTotal) },
            uri?.takeIf { secure.deliveryReportForSub(subId) }
                ?.let { statusIntent(context, SmsStatusReceiver.ACTION_DELIVERED, it, partTotal) }
        )
        if (sent || uri == null) return
        // The radio refused it: replace the stored "sent" row with a queued outbox entry
        // so the text is kept and visibly pending instead of silently marked as sent.
        queueForRetry(context, uri, address, body, threadId, subId)
    }

    /** @return true when the radio accepted the message. */
    private fun send(
        manager: SmsManager,
        address: String,
        body: String,
        sentPi: PendingIntent?,
        deliveredPi: PendingIntent?
    ): Boolean = try {
        val parts = manager.divideMessage(body)
        if (parts.size > 1) {
            val sentList = ArrayList<PendingIntent>()
            val delList = ArrayList<PendingIntent>()
            for (i in parts.indices) {
                sentPi?.let { sentList.add(it) }
                deliveredPi?.let { delList.add(it) }
            }
            manager.sendMultipartTextMessage(
                address, null, parts,
                sentList.ifEmpty { null },
                delList.ifEmpty { null }
            )
        } else {
            manager.sendTextMessage(address, null, body, sentPi, deliveredPi)
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun queueForRetry(
        context: Context,
        sentUri: Uri,
        address: String,
        body: String,
        threadId: Long,
        subId: Int
    ) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE)
                if (threadId > 0) put(Telephony.Sms.THREAD_ID, threadId)
                if (subId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
            }
            // Remove the optimistic "sent" row first, or the same reply would show twice.
            context.contentResolver.delete(sentUri, null, null)
            context.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values)
            context.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
            context.sendBroadcast(
                Intent(SmsStatusReceiver.ACTION_SMS_REFRESH).setPackage(context.packageName)
            )
        } catch (_: Exception) {
            // The stored row simply stays marked unsent.
        }
    }

    private fun statusIntent(context: Context, action: String, uri: Uri, partTotal: Int): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java)
            .setAction(action)
            .setData(uri)
            .putExtra(SmsStatusReceiver.EXTRA_PART_TOTAL, partTotal)
        return PendingIntent.getBroadcast(
            context, uri.toString().hashCode() and 0x7FFFFFFF, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun hiddenIntent(context: Context, action: String, rowId: Long, partTotal: Int): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java)
            .setAction(action)
            .putExtra(SmsStatusReceiver.EXTRA_HIDDEN_ID, rowId)
            .putExtra(SmsStatusReceiver.EXTRA_PART_TOTAL, partTotal)
        return PendingIntent.getBroadcast(
            context, rowId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun smsManagerFor(context: Context, subId: Int): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subId >= 0) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    companion object {
        private const val EXTRA_DESTINATIONS = "destinations"
        private const val EXTRA_SMS_BODY = "sms_body"
        private val executor = Executors.newSingleThreadExecutor()
    }
}

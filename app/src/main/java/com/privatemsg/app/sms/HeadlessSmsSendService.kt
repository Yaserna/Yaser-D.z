package com.privatemsg.app.sms

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import com.privatemsg.app.data.HiddenDbHelper
import com.privatemsg.app.data.NumericCipher
import com.privatemsg.app.data.SecureStore
import com.privatemsg.app.data.SimHelper
import com.privatemsg.app.data.SmsRepository
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

        if (isHidden) {
            // A reply on a hidden line must not leave a trace in the public store: it goes
            // into the private DB in the same wire format the other side expects.
            val hiddenDb = HiddenDbHelper.getInstance(context)
            val textToSend = if (secure.isEncryptionEnabled(address)) {
                NumericCipher.encryptToNumeric(body, fromHidden = true)
            } else {
                "$body a+"
            }
            val parts = SmsSender.split(context, subId, textToSend)
            val rowId = hiddenDb.insert(address, textToSend, System.currentTimeMillis(), 4, subId)
            SmsSender.send(
                context, subId, address, parts,
                SmsStatusReceiver.hiddenStatusIntent(
                    context, SmsStatusReceiver.ACTION_HIDDEN_SENT, rowId, parts.size
                ),
                if (secure.deliveryReportForSub(subId))
                    SmsStatusReceiver.hiddenStatusIntent(
                        context, SmsStatusReceiver.ACTION_HIDDEN_DELIVERED, rowId, parts.size
                    )
                else null
            )
            context.sendBroadcast(
                Intent(SmsStatusReceiver.ACTION_HIDDEN_REFRESH).setPackage(context.packageName)
            )
            return
        }

        val threadId = try {
            Telephony.Threads.getOrCreateThreadId(context, address)
        } catch (_: Exception) {
            0L
        }
        val repo = SmsRepository(context)
        val uri = repo.storeSentMessage(address, body, subId)
        val parts = SmsSender.split(context, subId, body)
        val sent = SmsSender.send(
            context, subId, address, parts,
            uri?.let {
                SmsStatusReceiver.statusIntent(context, SmsStatusReceiver.ACTION_SENT, it, parts.size)
            },
            uri?.takeIf { secure.deliveryReportForSub(subId) }?.let {
                SmsStatusReceiver.statusIntent(
                    context, SmsStatusReceiver.ACTION_DELIVERED, it, parts.size
                )
            }
        )
        if (sent || uri == null) return
        // The radio refused it: replace the stored "sent" row with a queued outbox entry
        // so the text is kept and visibly pending instead of silently marked as sent.
        repo.queueFailedSend(uri, address, body, threadId, subId)
    }

    companion object {
        private const val EXTRA_DESTINATIONS = "destinations"
        private const val EXTRA_SMS_BODY = "sms_body"
        private val executor = Executors.newSingleThreadExecutor()
    }
}

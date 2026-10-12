package com.privatemsg.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.concurrent.Executors

/**
 * Required so the app is eligible to be the default SMS app.
 *
 * While it is the default app nothing else downloads incoming MMS, so a silent stub
 * would make picture messages vanish without a trace. This cannot yet open them
 * (parsing the media PDU is a separate milestone), but it does tell the user one
 * arrived. The notification deliberately carries no sender and no content: it looks
 * the same whatever number the MMS came from, so it cannot expose the hidden section.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val appContext = context.applicationContext
        // Building the first notification of a session creates its channel on disk,
        // so this does not belong on the broadcast's main thread.
        val pending = goAsync()
        executor.execute {
            try {
                Notifier.showMmsArrived(appContext)
            } catch (e: Exception) {
                android.util.Log.e("MmsReceiver", "mms notice failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }
}

package com.privatemsg.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.privatemsg.app.data.HiddenDbHelper
import com.privatemsg.app.data.NumericCipher
import com.privatemsg.app.data.SecureStore
import com.privatemsg.app.data.SmsRepository
import com.privatemsg.app.ui.ConversationActivity
import com.privatemsg.app.ui.HiddenConversationActivity
import java.util.concurrent.Executors

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Telephony.Sms.Intents.SMS_DELIVER_ACTION &&
            action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION
        ) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val pendingResult = goAsync()
        val asyncExecutor = Executors.newSingleThreadExecutor()

        asyncExecutor.execute {
            try {
                val address = messages[0].displayOriginatingAddress ?: ""
                val body = messages.joinToString("") { it.displayMessageBody ?: "" }
                val date = messages[0].timestampMillis
                val subId = intent.getIntExtra("subscription", -1)
                val serviceCenter = messages[0].serviceCenterAddress ?: ""

                val secure = SecureStore(context)
                val isHidden = secure.isHidden(address)

                if (isHidden) {
                    val isEncrypted = NumericCipher.isNumericEncrypted(body)
                    val isSigned = body.endsWith(" a+") || body.endsWith("a+")
                    val isFromSafe = isEncrypted || isSigned
                    val honeypotActive = secure.isHoneypotEnabled(address)

                    if (honeypotActive && !isFromSafe) {
                        val repo = SmsRepository(context)
                        val msgId = repo.insertInboxMessage(address, body, date, subId, serviceCenter)
                        val active = ConversationActivity.activeNormalizedAddress
                        val inChat = active != null && active == SecureStore.normalize(address)

                        if (!inChat && !secure.isMuted(address)) {
                            Notifier.showIncoming(context, address, body, msgId)
                            Notifier.vibrateTiny(context)
                        }
                        return@execute
                    }

                    val hiddenDb = HiddenDbHelper(context)
                    hiddenDb.insert(address, body, date, 1, subId)

                    val active = HiddenConversationActivity.activeNormalizedAddress
                    val inChat = active != null && active == SecureStore.normalize(address)

                    if (inChat) {
                        context.sendBroadcast(
                            Intent(SmsStatusReceiver.ACTION_HIDDEN_REFRESH).setPackage(context.packageName)
                        )
                    } else if (!secure.isMuted(address)) {
                        Notifier.showDecoy(context, secure, address)
                    }
                } else {
                    val repo = SmsRepository(context)
                    val msgId = repo.insertInboxMessage(address, body, date, subId, serviceCenter)
                    val active = ConversationActivity.activeNormalizedAddress
                    val inChat = active != null && active == SecureStore.normalize(address)

                    if (!inChat && !secure.isMuted(address)) {
                        Notifier.showIncoming(context, address, body, msgId)
                        Notifier.vibrateTiny(context)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

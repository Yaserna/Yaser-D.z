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

        asyncExecutor.execute {
            try {
                val rawAddress = messages[0].displayOriginatingAddress ?: ""
                val normAddress = SecureStore.normalize(rawAddress)
                val body = messages.joinToString("") { it.displayMessageBody ?: "" }
                val cleanBody = body.trim()
                val date = messages[0].timestampMillis
                val subId = intent.getIntExtra("subscription", -1)
                val serviceCenter = messages[0].serviceCenterAddress ?: ""

                val secure = SecureStore(context)
                val isHidden = secure.isHidden(rawAddress) || (normAddress.isNotBlank() && secure.isHidden(normAddress))

                if (isHidden) {
                    val isEncrypted = NumericCipher.isNumericEncrypted(cleanBody)
                    val isSigned = cleanBody.endsWith(" a+") || cleanBody.endsWith("a+")
                    val isFromSafe = isEncrypted || isSigned
                    val honeypotActive = secure.isHoneypotEnabled(rawAddress) || (normAddress.isNotBlank() && secure.isHoneypotEnabled(normAddress))

                    if (honeypotActive && !isFromSafe) {
                        val repo = SmsRepository(context)
                        val msgId = repo.insertInboxMessage(rawAddress, body, date, subId, serviceCenter)
                        val active = ConversationActivity.activeNormalizedAddress
                        val inChat = active != null && (active == normAddress || active == SecureStore.normalize(rawAddress))

                        if (!inChat && !secure.isMuted(rawAddress)) {
                            Notifier.showIncoming(context, rawAddress, body, msgId)
                            Notifier.vibrateTiny(context)
                        }
                        return@execute
                    }

                    val hiddenDb = HiddenDbHelper(context)
                    hiddenDb.insert(rawAddress, body, date, 1, subId)

                    val active = HiddenConversationActivity.activeNormalizedAddress
                    val inChat = active != null && (active == normAddress || active == SecureStore.normalize(rawAddress))

                    if (inChat) {
                        context.sendBroadcast(
                            Intent(SmsStatusReceiver.ACTION_HIDDEN_REFRESH).setPackage(context.packageName)
                        )
                    } else if (!secure.isMuted(rawAddress)) {
                        Notifier.showDecoy(context, secure, rawAddress)
                    }
                } else {
                    val repo = SmsRepository(context)
                    val msgId = repo.insertInboxMessage(rawAddress, body, date, subId, serviceCenter)
                    val active = ConversationActivity.activeNormalizedAddress
                    val inChat = active != null && (active == normAddress || active == SecureStore.normalize(rawAddress))

                    if (!inChat && !secure.isMuted(rawAddress)) {
                        Notifier.showIncoming(context, rawAddress, body, msgId)
                        Notifier.vibrateTiny(context)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val asyncExecutor = Executors.newSingleThreadExecutor()
    }
}

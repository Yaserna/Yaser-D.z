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

        // The worker outlives onReceive, and a receiver context is invalid after that
        // first call returns - everything below runs on the application context.
        val app = context.applicationContext
        asyncExecutor.execute {
            try {
                val rawAddress = messages[0].displayOriginatingAddress ?: ""
                val normAddress = SecureStore.normalize(rawAddress)
                val body = messages.joinToString("") { it.displayMessageBody ?: "" }
                val cleanBody = body.trim()
                val date = messages[0].timestampMillis
                val subId = intent.getIntExtra("subscription", -1)
                val serviceCenter = messages[0].serviceCenterAddress ?: ""

                val secure = SecureStore(app)
                val isHidden = secure.isHidden(rawAddress) || (normAddress.isNotBlank() && secure.isHidden(normAddress))
                // A number can be muted under either spelling; checking only the raw one
                // let a normalized mute alert anyway.
                val isMuted = secure.isMuted(rawAddress) ||
                    (normAddress.isNotBlank() && secure.isMuted(normAddress))

                if (isHidden) {
                    val isEncrypted = NumericCipher.isNumericEncrypted(cleanBody)
                    val isSigned = cleanBody.endsWith(" a+") || cleanBody.endsWith("a+")
                    val isFromSafe = isEncrypted || isSigned
                    val honeypotActive = secure.isHoneypotEnabled(rawAddress) || (normAddress.isNotBlank() && secure.isHoneypotEnabled(normAddress))

                    if (honeypotActive && !isFromSafe) {
                        val repo = SmsRepository(app)
                        val msgId = repo.insertInboxMessage(rawAddress, body, date, subId, serviceCenter)
                        // This row is the lure the intruder is meant to find. Remember it so
                        // the hidden-section sweep leaves it in the public store.
                        secure.rememberLure(msgId)
                        val msgThreadId = threadIdFor(app, rawAddress)
                        val active = ConversationActivity.activeNormalizedAddress
                        val activeThread = ConversationActivity.activeThreadId
                        val inChat = (activeThread > 0 && activeThread == msgThreadId) ||
                                     (active != null && (active == normAddress || active == SecureStore.normalize(rawAddress)))

                        app.sendBroadcast(
                            Intent(SmsStatusReceiver.ACTION_SMS_REFRESH).setPackage(app.packageName)
                        )

                        if (inChat) {
                            Notifier.vibrateTiny(app)
                            if (msgThreadId > 0) repo.markThreadRead(msgThreadId)
                        } else if (!isMuted) {
                            Notifier.showIncoming(app, rawAddress, body, msgId, subId)
                            Notifier.vibrateTiny(app)
                        }
                        return@execute
                    }

                    val hiddenDb = HiddenDbHelper.getInstance(app)
                    hiddenDb.insert(rawAddress, body, date, 1, subId)

                    val active = HiddenConversationActivity.activeNormalizedAddress
                    val inChat = active != null && (active == normAddress || active == SecureStore.normalize(rawAddress))

                    // A live refresh always goes to the open hidden screen.
                    app.sendBroadcast(
                        Intent(SmsStatusReceiver.ACTION_HIDDEN_REFRESH).setPackage(app.packageName)
                    )

                    if (inChat) {
                        Notifier.vibrateTiny(app)
                        hiddenDb.markRead(rawAddress)
                        hiddenDb.markRead(normAddress)
                    } else if (!isMuted) {
                        Notifier.showDecoy(app, secure, rawAddress)
                    }
                } else {
                    val repo = SmsRepository(app)
                    val msgId = repo.insertInboxMessage(rawAddress, body, date, subId, serviceCenter)
                    val msgThreadId = threadIdFor(app, rawAddress)
                    val active = ConversationActivity.activeNormalizedAddress
                    val activeThread = ConversationActivity.activeThreadId
                    val inChat = (activeThread > 0 && activeThread == msgThreadId) ||
                                 (active != null && (active == normAddress || active == SecureStore.normalize(rawAddress)))

                    // Push the live refresh to the open public chat.
                    app.sendBroadcast(
                        Intent(SmsStatusReceiver.ACTION_SMS_REFRESH).setPackage(app.packageName)
                    )

                    if (inChat) {
                        Notifier.vibrateTiny(app)
                        if (msgThreadId > 0) repo.markThreadRead(msgThreadId)
                    } else if (!isMuted) {
                        Notifier.showIncoming(app, rawAddress, body, msgId, subId)
                        Notifier.vibrateTiny(app)
                    }
                }
            } catch (e: Exception) {
                // An unhandled error on this thread would kill the process and drop the
                // message for good. Swallow it; the system still holds the SMS on the SIM.
                android.util.Log.e(TAG, "incoming sms handling failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
        private val asyncExecutor = Executors.newSingleThreadExecutor()
    }

    /** -1 when the sender has no resolvable thread; the callers all treat that as "no chat". */
    private fun threadIdFor(context: Context, address: String): Long = try {
        Telephony.Threads.getOrCreateThreadId(context, address)
    } catch (e: Exception) {
        android.util.Log.e(TAG, "no thread for this sender", e)
        -1L
    }
}
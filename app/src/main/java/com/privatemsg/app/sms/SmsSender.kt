package com.privatemsg.app.sms

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.telephony.SmsManager

/**
 * The single place that hands a message to the radio.
 *
 * Every screen used to carry its own copy of this code, and the copies had drifted
 * apart: some swallowed the radio's exceptions (so a failed send stayed marked as
 * delivered), others registered one status callback for a multi-part message (so the
 * first part's report decided the fate of the whole message).
 */
object SmsSender {

    fun managerFor(context: Context, subId: Int): SmsManager {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // getSystemService is declared nullable and some ROMs really do answer null
            // here; falling back to the deprecated static keeps a send from crashing.
            val base = app.getSystemService(SmsManager::class.java)
                ?: @Suppress("DEPRECATION") SmsManager.getDefault()
            if (subId >= 0) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    /**
     * The radio parts a message into segments; a long or Persian (Unicode) text needs
     * more than one, and sending it as a single part silently fails to deliver.
     * Never throws: a device whose telephony stack is unavailable falls back to one
     * part, and [send] then reports the refusal instead of crashing the caller.
     */
    fun split(context: Context, subId: Int, body: String): List<String> = try {
        managerFor(context, subId).divideMessage(body) ?: listOf(body)
    } catch (e: Exception) {
        android.util.Log.e("SmsSender", "no SMS manager on this device", e)
        listOf(body)
    }

    /**
     * Hands [parts] to the radio.
     *
     * @param sentPi one callback per part, so the caller learns when the last part
     *   reported in ([parts] was produced by [split], so the counts always agree).
     * @return true when the radio accepted the message; false when it refused it (bad
     *   number, no signal, missing permission) and the caller should queue or mark it.
     */
    fun send(
        context: Context,
        subId: Int,
        to: String,
        parts: List<String>,
        sentPi: PendingIntent?,
        deliveredPi: PendingIntent?
    ): Boolean {
        if (to.isBlank() || parts.isEmpty()) return false
        return try {
            val manager = managerFor(context, subId)
            if (parts.size > 1) {
                val sentList = sentPi?.let { pi -> ArrayList(parts.map { pi }) }
                val deliveredList =
                    deliveredPi?.let { pi -> ArrayList(parts.map { pi }) }
                manager.sendMultipartTextMessage(to, null, ArrayList(parts), sentList, deliveredList)
            } else {
                manager.sendTextMessage(to, null, parts[0], sentPi, deliveredPi)
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("SmsSender", "radio refused the message", e)
            false
        }
    }
}

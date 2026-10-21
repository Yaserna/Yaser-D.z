package com.privatemsg.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager

/** Lists the active SIM cards so the user can choose which one sends a message. */
class SimHelper(context: Context) {

    // Sends and status callbacks reach this helper from worker threads that can outlive
    // the screen that created it, so only the application context is kept.
    private val appContext = context.applicationContext

    data class Sim(val subId: Int, val slot: Int)

    // Reading the subscription list needs READ_PHONE_STATE, which the user can revoke at
    // any time; the catch below turns that refusal (and a missing telephony service) into
    // "no SIMs", which every caller already handles.
    @SuppressLint("MissingPermission")
    fun sims(): List<Sim> = try {
        val sm = appContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        (sm.activeSubscriptionInfoList ?: emptyList())
            .map { Sim(it.subscriptionId, it.simSlotIndex + 1) }
            .sortedBy { it.slot }
    } catch (e: Exception) {
        emptyList()
    }

    @SuppressLint("MissingPermission")
    fun defaultSubId(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            SubscriptionManager.getDefaultSmsSubscriptionId()
        else -1
    } catch (e: Throwable) {
        -1
    }
}

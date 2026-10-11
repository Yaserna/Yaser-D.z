package com.privatemsg.app.ui

import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.privatemsg.app.R
import com.privatemsg.app.data.ContactsHelper
import com.privatemsg.app.data.Message
import com.privatemsg.app.data.SimHelper
import com.privatemsg.app.data.SmsRepository
import com.privatemsg.app.databinding.ActivityQuickReplyBinding

/**
 * A small floating window opened from the notification's Reply button: shows the
 * last two messages and a reply box, so the user can answer without opening the app.
 */
class QuickReplyActivity : BaseActivity() {

    private lateinit var binding: ActivityQuickReplyBinding
    private var address: String = ""
    private var threadId: Long = -1
    private var notifId: Int = 0
    private var subId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickReplyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Dock the floating panel to the bottom, full width, and close on tap-outside.
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        setFinishOnTouchOutside(true)

        address = intent.getStringExtra("address") ?: ""
        threadId = intent.getLongExtra("thread_id", -1)
        notifId = intent.getIntExtra("notif_id", 0)
        subId = intent.getIntExtra("sub_id", -1)

        binding.title.text = ContactsHelper(this).displayFor(address)
        showLastMessages()

        binding.sendButton.setOnClickListener { send() }
        binding.input.requestFocus()
    }

    /** Renders the last 5 messages as small chat bubbles (conversation colors). */
    private fun showLastMessages() {
        if (threadId <= 0) return
        val secure = com.privatemsg.app.data.SecureStore(this)
        val sentColor = secure.sentBubbleColor
        val recvColor = secure.receivedBubbleColor
        val d = resources.displayMetrics.density
        for (m in SmsRepository(this).getMessages(threadId).takeLast(5)) {
            val sent = m.type != 1
            val color = if (sent) sentColor else recvColor
            val bubble = android.widget.TextView(this).apply {
                text = m.body.toLatinDigits()
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = (220 * d).toInt()
                textSize = 13f
                setTextColor(if (isLight(color)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14f * d
                    setColor(color)
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (3 * d).toInt()
                // Sent on the right (START in RTL), received on the left (END).
                gravity = if (sent) Gravity.START else Gravity.END
            }
            binding.messagesContainer.addView(bubble, lp)
        }
    }

    private fun isLight(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) > 150
    }

    private fun send() {
        val body = binding.input.text.toString().trim()
        if (body.isEmpty() || address.isEmpty()) return
        try {
            val sub = if (subId >= 0) subId else SimHelper(this).defaultSubId()
            SmsRepository(this).storeSentMessage(address, body, sub)
            val sm = smsManager(sub)
            val parts = sm.divideMessage(body)
            if (parts.size > 1) sm.sendMultipartTextMessage(address, null, parts, null, null)
            else sm.sendTextMessage(address, null, body, null, null)
            Toast.makeText(this, R.string.reply_sent, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.reply_failed, Toast.LENGTH_SHORT).show()
        }
        NotificationManagerCompat.from(this).cancel(notifId)
        finish()
    }

    private fun smsManager(subId: Int): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = getSystemService(SmsManager::class.java)
            if (subId >= 0) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }
}

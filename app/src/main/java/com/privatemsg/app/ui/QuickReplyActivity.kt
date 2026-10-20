package com.privatemsg.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import com.privatemsg.app.R
import com.privatemsg.app.data.ContactsHelper
import com.privatemsg.app.data.SecureStore
import com.privatemsg.app.data.SimHelper
import com.privatemsg.app.data.SmsRepository
import com.privatemsg.app.databinding.ActivityQuickReplyBinding
import com.privatemsg.app.sms.SmsSender
import com.privatemsg.app.sms.SmsStatusReceiver
import java.util.concurrent.Executors

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

    // Reading the SMS store and handing a message to the radio are both slow calls, and
    // this window opens straight on top of a notification tap: they go to a worker and
    // only the finished result is posted back to the UI.
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

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

        val contacts = ContactsHelper(this)
        val repo = SmsRepository(this)
        val wantedThread = threadId
        worker.execute {
            val title = runCatching { contacts.displayFor(address) }.getOrNull()
            val last = if (wantedThread > 0)
                runCatching { repo.getMessages(wantedThread).takeLast(5) }.getOrNull()
            else null
            main.post { if (!isDestroyed) renderLast(title, last) }
        }

        binding.sendButton.setOnClickListener { send() }
        binding.input.requestFocus()
    }

    private fun renderLast(title: String?, messages: List<com.privatemsg.app.data.Message>?) {
        if (!title.isNullOrEmpty()) binding.title.text = title
        if (messages.isNullOrEmpty()) return
        val secure = SecureStore(this)
        val sentColor = secure.sentBubbleColor
        val recvColor = secure.receivedBubbleColor
        val d = resources.displayMetrics.density
        for (m in messages) {
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
        binding.sendButton.isEnabled = false // one tap = one message, even on a slow radio
        val app = applicationContext
        val rowThread = threadId
        worker.execute {
            val secure = SecureStore(app)
            val sub = if (subId >= 0) subId else SimHelper(app).defaultSubId()
            val repo = SmsRepository(app)
            val uri = repo.storeSentMessage(address, body, sub)
            val parts = SmsSender.split(app, sub, body)
            val sent = SmsSender.send(
                app, sub, address, parts,
                uri?.let {
                    SmsStatusReceiver.statusIntent(app, SmsStatusReceiver.ACTION_SENT, it, parts.size)
                },
                uri?.takeIf { secure.deliveryReportForSub(sub) }?.let {
                    SmsStatusReceiver.statusIntent(app, SmsStatusReceiver.ACTION_DELIVERED, it, parts.size)
                }
            )
            if (!sent && uri != null) {
                // The radio refused it: keep the text as a queued outbox row instead of a
                // row that shows a tick for a message that never left the phone.
                repo.queueFailedSend(uri, address, body, rowThread, sub)
            }
            main.post {
                if (isDestroyed) return@post
                if (!sent) {
                    binding.sendButton.isEnabled = true
                    Toast.makeText(this, R.string.reply_failed, Toast.LENGTH_SHORT).show()
                    return@post
                }
                androidx.core.app.NotificationManagerCompat.from(this).cancel(notifId)
                Toast.makeText(this, R.string.reply_sent, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        // Let a reply that is already on its way to the radio finish; only new work is
        // refused. The UI callbacks check isDestroyed before touching views.
        worker.shutdown()
        super.onDestroy()
    }
}

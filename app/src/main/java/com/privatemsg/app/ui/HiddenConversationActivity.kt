package com.privatemsg.app.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.text.format.DateUtils
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.privatemsg.app.R
import com.privatemsg.app.data.ContactsHelper
import com.privatemsg.app.data.FavoritesDbHelper
import com.privatemsg.app.data.HiddenDbHelper
import com.privatemsg.app.data.Message
import com.privatemsg.app.data.SecureStore
import com.privatemsg.app.data.SimHelper
import com.privatemsg.app.databinding.ActivityConversationBinding
import com.privatemsg.app.sms.Notifier
import com.privatemsg.app.sms.SmsStatusReceiver

/** A hidden conversation; messages live only in the private database. */
class HiddenConversationActivity : BaseActivity() {

    override val leavesToMainOnBackground = true

    private lateinit var binding: ActivityConversationBinding
    private lateinit var hiddenDb: HiddenDbHelper
    private lateinit var adapter: MessageAdapter
    private var address: String = ""
    private var sims: List<SimHelper.Sim> = emptyList()
    private var simIndex: Int = 0
    private var resumedNow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.navBack.setOnClickListener { finish() }

        hiddenDb = HiddenDbHelper(this)
        address = intent.getStringExtra("address") ?: ""
        binding.recipientRow.visibility = android.view.View.GONE
        binding.attachButton.visibility = android.view.View.GONE
        updateTitle()
        // Tap the name to give this hidden number a custom display name (app-only).
        binding.titleBox.setOnClickListener { showRenameDialog() }

        setupSim()
        val slotMap = sims.associate { it.subId to it.slot }
        val colorStore = SecureStore(this)
        adapter = MessageAdapter(
            showSim = sims.size >= 2,
            slotForSub = { subId -> slotMap[subId] },
            onLongClick = { showMessageMenu(it) },
            onNumberClick = { showNumberMenu(it) },
            onSelectionChanged = { updateSelectionUi() },
            sentColor = colorStore.sentBubbleColor,
            receivedColor = colorStore.receivedBubbleColor
        )
        binding.recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recycler.adapter = adapter

        binding.selCancel.setOnClickListener { adapter.exitSelection() }
        binding.selSelectAll.setOnClickListener { adapter.selectAll() }
        binding.selDelete.setOnClickListener { deleteSelected() }
        binding.selCopy.setOnClickListener { copySelected() }

        binding.sendButton.setOnClickListener { send() }
        // Restore any unsent draft for this hidden conversation.
        if (address.isNotEmpty()) {
            binding.input.setText(SecureStore(this).getDraft(address))
            binding.input.setSelection(binding.input.text?.length ?: 0)
        }
        loadMessages()

        // Reload when a hidden delivery report updates a message's status.
        ContextCompat.registerReceiver(
            this, refreshReceiver,
            IntentFilter(SmsStatusReceiver.ACTION_HIDDEN_REFRESH),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            loadMessages()
            // A new message arrived while the chat is open: keep it read and drop its decoy.
            if (resumedNow) {
                hiddenDb.markRead(address)
                Notifier.cancelDecoy(this@HiddenConversationActivity, address)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(refreshReceiver)
        } catch (e: Exception) {
            // not registered; ignore
        }
    }

    private fun showMessageMenu(m: Message) {
        val options = arrayOf(
            getString(R.string.choose),
            getString(R.string.add_favorite),
            getString(R.string.copy),
            getString(R.string.delete),
            getString(R.string.details)
        )
        showListMenu(options) { which ->
            when (which) {
                0 -> adapter.startSelection(m)
                1 -> {
                    FavoritesDbHelper(this).add(m.address, m.body, m.date)
                    Toast.makeText(this, R.string.favorite_added, Toast.LENGTH_SHORT).show()
                }
                2 -> {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("message", m.body))
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
                }
                3 -> {
                    hiddenDb.deleteById(m.id)
                    loadMessages()
                }
                4 -> {
                    val date = DateUtils.formatDateTime(
                        this, m.date,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR
                    )
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.details)
                        .setMessage(getString(R.string.details_body, m.address, date).toLatinDigits())
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun updateSelectionUi() {
        val on = adapter.selectionMode
        binding.selectionBar.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        binding.navBack.visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE
        binding.titleBox.visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE
        if (on) binding.selCount.text = getString(R.string.n_selected, adapter.selectedCount())
    }

    private fun deleteSelected() {
        adapter.selectedMessages().forEach { hiddenDb.deleteById(it.id) }
        adapter.exitSelection()
        loadMessages()
    }

    private fun copySelected() {
        val text = adapter.selectedMessages().joinToString("\n") { it.body }
        if (text.isNotEmpty()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("messages", text))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        adapter.exitSelection()
    }

    override fun onBackPressed() {
        if (adapter.selectionMode) adapter.exitSelection() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        resumedNow = true
        activeNormalizedAddress = if (address.isNotEmpty()) SecureStore.normalize(address) else null
        // Opening the conversation clears its unread state and removes its decoy notification.
        hiddenDb.markRead(address)
        Notifier.cancelDecoy(this, address)
    }

    override fun onPause() {
        super.onPause()
        resumedNow = false
        activeNormalizedAddress = null
        // Keep whatever is typed as a draft so it isn't lost on back/exit.
        if (address.isNotEmpty()) SecureStore(this).setDraft(address, binding.input.text.toString())
    }

    private fun loadMessages() {
        val msgs = hiddenDb.getMessages(address)
        adapter.submit(msgs)
        binding.recycler.scrollToPosition(adapter.itemCount - 1)
        autoSelectSim(msgs)
    }

    /** Reply SIM follows the SIM of the most recent received message (manual change still works). */
    private var lastIncomingId = -1L
    private fun autoSelectSim(msgs: List<Message>) {
        if (sims.size < 2) return
        val lastIn = msgs.lastOrNull { it.type == 1 && it.subId >= 0 } ?: return
        if (lastIn.id != lastIncomingId) {
            lastIncomingId = lastIn.id
            val idx = sims.indexOfFirst { it.subId == lastIn.subId }
            if (idx >= 0) {
                simIndex = idx
                binding.simBadge.text = sims[simIndex].slot.toString()
            }
        }
    }

    /** Display name = the app-only alias if set, otherwise the contact/number. */
    private fun updateTitle() {
        binding.titleName.text =
            SecureStore(this).hiddenAliasFor(address) ?: ContactsHelper(this).displayFor(address)
        binding.titleNumber.text = address
    }

    /** Rename the hidden contact for display inside the app only (contacts untouched). */
    private fun showRenameDialog() {
        if (address.isEmpty()) return
        val input = android.widget.EditText(this).apply {
            setText(SecureStore(this@HiddenConversationActivity).hiddenAliasFor(address) ?: "")
            setSelection(text?.length ?: 0)
            hint = getString(R.string.rename_hint)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_contact)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                SecureStore(this).setHiddenAlias(address, input.text.toString())
                updateTitle()
                Toast.makeText(this, R.string.rename_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun send() {
        val body = binding.input.text.toString().trim()
        if (body.isEmpty() || address.isEmpty()) return

        val subId = if (sims.isNotEmpty()) sims[simIndex].subId else -1
        if (subId >= 0) SecureStore(this).setThreadSim(address, subId)
        // Store the sent message privately (type 2 = sent); never in the system store.
        val rowId = hiddenDb.insert(address, body, System.currentTimeMillis(), 2, subId)
        val deliveredPi =
            if (SecureStore(this).deliveryReportForSub(subId)) hiddenDeliveryIntent(rowId) else null
        sendViaSms(address, body, null, deliveredPi)

        binding.input.setText("")
        SecureStore(this).setDraft(address, "")
        loadMessages()
    }

    /** Delivery report for a hidden message → updates its private-DB row. */
    private fun hiddenDeliveryIntent(rowId: Long): PendingIntent {
        val intent = Intent(this, SmsStatusReceiver::class.java)
            .setAction(SmsStatusReceiver.ACTION_HIDDEN_DELIVERED)
            .putExtra(SmsStatusReceiver.EXTRA_HIDDEN_ID, rowId)
        return PendingIntent.getBroadcast(
            this, rowId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Splits long/Unicode messages into multiple parts so they actually deliver. */
    private fun sendViaSms(to: String, body: String, sentPi: PendingIntent?, deliveredPi: PendingIntent?) {
        val sm = smsManager()
        val parts = sm.divideMessage(body)
        if (parts.size > 1) {
            val sentList = ArrayList<PendingIntent>()
            val delList = ArrayList<PendingIntent>()
            for (i in parts.indices) {
                sentPi?.let { sentList.add(it) }
                deliveredPi?.let { delList.add(it) }
            }
            sm.sendMultipartTextMessage(
                to, null, parts,
                if (sentList.isEmpty()) null else sentList,
                if (delList.isEmpty()) null else delList
            )
        } else {
            sm.sendTextMessage(to, null, body, sentPi, deliveredPi)
        }
    }

    private fun setupSim() {
        val helper = SimHelper(this)
        sims = helper.sims()
        if (sims.size >= 2) {
            // Prefer the SIM remembered for this conversation, else the default.
            val saved = SecureStore(this).getThreadSim(address)
            val preferred = if (saved >= 0) saved else helper.defaultSubId()
            simIndex = sims.indexOfFirst { it.subId == preferred }.let { if (it >= 0) it else 0 }
            binding.simBadge.visibility = android.view.View.VISIBLE
            binding.simBadge.text = sims[simIndex].slot.toString()
            binding.simBadge.setOnClickListener {
                simIndex = (simIndex + 1) % sims.size
                binding.simBadge.text = sims[simIndex].slot.toString()
                if (address.isNotEmpty()) SecureStore(this).setThreadSim(address, sims[simIndex].subId)
            }
        } else {
            binding.simBadge.visibility = android.view.View.GONE
        }
    }

    private fun smsManager(): SmsManager {
        val subId = if (sims.isNotEmpty()) sims[simIndex].subId else -1
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = getSystemService(SmsManager::class.java)
            if (subId >= 0) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    companion object {
        @Volatile
        var activeNormalizedAddress: String? = null
    }
}
package com.privatemsg.app.ui

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.format.DateUtils
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.privatemsg.app.R
import com.privatemsg.app.data.ContactsHelper
import com.privatemsg.app.data.HiddenDbHelper
import com.privatemsg.app.data.Message
import com.privatemsg.app.data.SecureStore
import com.privatemsg.app.data.SimHelper
import com.privatemsg.app.data.StarredDbHelper
import com.privatemsg.app.databinding.ActivityConversationBinding
import com.privatemsg.app.data.NumericCipher
import com.privatemsg.app.data.SmsRepository
import com.privatemsg.app.sms.Notifier
import com.privatemsg.app.sms.SmsSender
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
    // The normalized address this screen wrote into the shared static; onPause clears
    // it only while it still matches, so a reversed resume/pause order can't unmark
    // the chat that is actually on screen.
    private var ownActiveNormalizedAddress: String? = null
    private var encryptToggle: android.widget.TextView? = null
    private var honeypotToggle: android.widget.TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.navBack.setOnClickListener { finish() }

        hiddenDb = HiddenDbHelper.getInstance(this)
        address = intent.getStringExtra("address") ?: ""
        binding.recipientRow.visibility = android.view.View.GONE
        binding.attachButton.visibility = android.view.View.GONE
        updateTitle()
        // Tap the name to give this hidden number a custom display name (app-only).
        binding.titleBox.setOnClickListener { showRenameDialog() }
        setupTopBarActions()

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
            receivedColor = colorStore.receivedBubbleColor,
            isStarred = { StarredDbHelper.getInstance(this).isStarred(it.id, isHidden = true) }
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

        // Back leaves selection mode first, otherwise it closes the screen.
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (adapter.selectionMode) adapter.exitSelection() else finish()
                }
            }
        )
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            loadMessages()
            // A new message arrived while the chat is open: keep it read and drop its decoy.
            if (resumedNow) {
                val app = applicationContext
                // A database write plus a notification cancel: never on the broadcast thread.
                runIo {
                    runCatching { hiddenDb.markRead(address) }
                    Notifier.cancelDecoy(app, address)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
        try {
            unregisterReceiver(refreshReceiver)
        } catch (e: Exception) {
            // not registered; ignore
        }
    }

    private fun showMessageMenu(m: Message) {
        val starredDb = StarredDbHelper.getInstance(this)
        val isStarredMsg = starredDb.isStarred(m.id, isHidden = true)
        val options = arrayOf(
            getString(R.string.choose),
            if (isStarredMsg) getString(R.string.star_remove) else getString(R.string.star_add),
            getString(R.string.copy),
            getString(R.string.delete),
            getString(R.string.details)
        )
        showListMenu(options) { which ->
            when (which) {
                0 -> adapter.startSelection(m)
                1 -> {
                    val app = applicationContext
                    runIo {
                        val newlyStarred = starredDb.toggleStar(
                            messageId = m.id,
                            isHidden = true,
                            threadId = 0,
                            address = m.address,
                            body = m.body,
                            date = m.date
                        )
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            Toast.makeText(
                                this,
                                getString(
                                    // A hidden star never reaches the favorites screen
                                    // (that list is public on purpose), so it must not
                                    // promise the message was added there.
                                    if (newlyStarred) R.string.star_added_hidden
                                    else R.string.star_removed
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                            loadMessages()
                        }
                        // The hidden list is only rebuilt by this screen, so tell the
                        // other hidden screens (and the favorites list) to re-read too.
                        app.sendBroadcast(
                            Intent(SmsStatusReceiver.ACTION_HIDDEN_REFRESH).setPackage(app.packageName)
                        )
                    }
                }
                2 -> {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("message", m.body))
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
                }
                3 -> {
                    // A starred hidden message is protected exactly like a public one.
                    if (isStarredMsg) {
                        Toast.makeText(this, R.string.starred_protected, Toast.LENGTH_LONG).show()
                    } else {
                        runIo {
                            hiddenDb.deleteById(m.id)
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) loadMessages()
                            }
                        }
                    }
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
        val selected = adapter.selectedMessages()
        adapter.exitSelection()
        runIo {
            val starredDb = StarredDbHelper.getInstance(this)
            var protectedCount = 0
            for (m in selected) {
                if (starredDb.isStarred(m.id, isHidden = true)) protectedCount++
                else hiddenDb.deleteById(m.id)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loadMessages()
                if (protectedCount > 0) {
                    Toast.makeText(
                        this, getString(R.string.n_starred_protected, protectedCount),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
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

    override fun onResume() {
        super.onResume()
        resumedNow = true
        ownActiveNormalizedAddress =
            if (address.isNotEmpty()) SecureStore.normalize(address) else null
        activeNormalizedAddress = ownActiveNormalizedAddress
        // Opening the conversation clears its unread state and removes its decoy
        // notification - both disk work, so they go to the worker.
        val app = applicationContext
        runIo {
            runCatching { hiddenDb.markRead(address) }
            Notifier.cancelDecoy(app, address)
        }
        updateEncryptToggleUi()
        updateHoneypotToggleUi()
    }

    override fun onPause() {
        super.onPause()
        resumedNow = false
        // Only erase the open-chat mark if it still names THIS screen; a new screen's
        // resume can arrive before this pause runs, and clearing blindly would let
        // notifications fire for the chat actually on screen.
        if (activeNormalizedAddress == ownActiveNormalizedAddress) {
            activeNormalizedAddress = null
            ownActiveNormalizedAddress = null
        }
        // Keep whatever is typed as a draft so it isn't lost on back/exit.
        if (address.isNotEmpty()) SecureStore(this).setDraft(address, binding.input.text.toString())
    }

    private fun loadMessages() {
        // Reading and decrypting the private table is disk + crypto work, so it happens
        // on a worker thread; only the finished list is handed to the recycler.
        runIo {
            val rawMsgs = try {
                hiddenDb.getMessages(address)
            } catch (e: Exception) {
                android.util.Log.e("HiddenConversation", "load failed", e)
                emptyList()
            }
            val displayMsgs = rawMsgs.map { m ->
                if (NumericCipher.isNumericEncrypted(m.body)) {
                    val dec = NumericCipher.decryptFromNumeric(m.body)
                    if (dec != null) {
                        m.copy(body = dec.text, isEncrypted = true, isFromHidden = dec.fromHidden)
                    } else {
                        m
                    }
                } else if (m.body.endsWith(" a+")) {
                    m.copy(body = m.body.removeSuffix(" a+"), isFromHidden = true)
                } else if (m.body.endsWith("a+")) {
                    m.copy(body = m.body.removeSuffix("a+"), isFromHidden = true)
                } else {
                    m
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.submit(displayMsgs)
                if (adapter.itemCount > 0) binding.recycler.scrollToPosition(adapter.itemCount - 1)
                autoSelectSim(rawMsgs)
            }
        }
    }

    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Queues [task]; false when the screen is already gone and the worker is stopped. */
    private fun runIo(task: () -> Unit): Boolean = try {
        ioExecutor.execute(task)
        true
    } catch (_: java.util.concurrent.RejectedExecutionException) {
        false
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
        val app = applicationContext
        val target = address
        val alias = SecureStore(app).hiddenAliasFor(target)
        binding.titleNumber.text = target
        if (alias != null) {
            binding.titleName.text = alias
            return
        }
        // The number holds the title while the (slow) contacts lookup runs on the worker.
        binding.titleName.text = target
        runIo {
            val name = runCatching { ContactsHelper(app).displayFor(target) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed || address != target) return@runOnUiThread
                // A rename typed while the lookup was running wins over the contact name.
                if (SecureStore(app).hiddenAliasFor(target) == null && !name.isNullOrEmpty()) {
                    binding.titleName.text = name
                }
            }
        }
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

        private fun sweepPublicMessages(context: Context, targetAddress: String) {
            try {
                val threadId = try {
                    android.provider.Telephony.Threads.getOrCreateThreadId(context, targetAddress)
                } catch (_: Exception) {
                    // No thread can be resolved for this number: there is nothing public
                    // to sweep, and the hidden write below must still go ahead.
                    return
                }
                if (threadId <= 0) return
                val repo = SmsRepository(context)
                val publicMsgs = repo.getMessages(threadId)
                if (publicMsgs.isEmpty()) return
                val starredDb = StarredDbHelper.getInstance(context)
                // A starred public row is protected from deletion, so it must not be
                // copied either: importing it and keeping the original would show the
                // same message twice in the hidden chat after the next sweep.
                val (protected, movable) = publicMsgs.partition {
                    starredDb.isStarred(it.id, isHidden = false)
                }
                // insertAllDistinct keeps a partly-finished move from importing a row
                // that is already in the private table.
                hiddenDb.insertAllDistinct(movable)
                // Delete exactly the rows that were moved: deleteThread() refuses a
                // starred thread, which would leave hidden-number messages in the system
                // store (and re-import them later as duplicates).
                if (movable.isNotEmpty()) repo.deleteMessages(movable.map { it.id })
                if (protected.isNotEmpty()) {
                    android.util.Log.w(
                        "HiddenConversation",
                        "${protected.size} starred public messages left in place for ${SecureStore.normalize(targetAddress)}"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("HiddenConversation", "public sweep failed", e)
            }
        }

    /**
     * A hidden send is crypto + private-DB + radio work, so none of it may run on the
     * UI thread; the radio call can also block for seconds and throws when it refuses
     * the message.
     */
    private fun send() {
        val body = binding.input.text.toString().trim()
        if (body.isEmpty() || address.isEmpty()) return

        val subId = currentSubId()
        val target = address
        binding.input.setText("")
        binding.sendButton.isEnabled = false
        val app = applicationContext
        runIo {
            val secure = SecureStore(app)
            // Encrypting a long Persian text is a AES/CBC pass over the whole string:
            // it belongs on the worker, not between two frames.
            val textToSend = if (secure.isEncryptionEnabled(target)) {
                NumericCipher.encryptToNumeric(body, fromHidden = true)
            } else {
                "$body a+"
            }
            if (subId >= 0) secure.setThreadSim(target, subId)
            sweepPublicMessages(app, target)
            val rowId =
                hiddenDb.insert(target, textToSend, System.currentTimeMillis(), 4, subId)
            val parts = SmsSender.split(app, subId, textToSend)
            val sent = SmsSender.send(
                app, subId, target, parts,
                SmsStatusReceiver.hiddenStatusIntent(
                    app, SmsStatusReceiver.ACTION_HIDDEN_SENT, rowId, parts.size
                ),
                if (secure.deliveryReportForSub(subId))
                    SmsStatusReceiver.hiddenStatusIntent(
                        app, SmsStatusReceiver.ACTION_HIDDEN_DELIVERED, rowId, parts.size
                    )
                else null
            )
            if (!sent) {
                // The radio refused it: mark the private row failed (the same type the
                // status callback would write) so it is visibly unsent.
                hiddenDb.updateType(rowId, android.provider.Telephony.Sms.MESSAGE_TYPE_FAILED)
                app.sendBroadcast(
                    Intent(SmsStatusReceiver.ACTION_HIDDEN_REFRESH).setPackage(app.packageName)
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.sendButton.isEnabled = true
                secure.setDraft(target, "")
                loadMessages()
                if (!sent) Toast.makeText(this, R.string.send_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Chosen SIM, read defensively: a SIM can be pulled between a badge tap and a send. */
    private fun currentSubId(): Int = sims.getOrNull(simIndex)?.subId ?: -1

    private fun setupTopBarActions() {
        val actionRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val encBtn = android.widget.TextView(this).apply {
            textSize = 18f
            setPadding(16, 8, 16, 8)
            gravity = android.view.Gravity.CENTER
        }
        encryptToggle = encBtn

        val hpBtn = android.widget.TextView(this).apply {
            setText("🎭")
            textSize = 18f
            setPadding(16, 8, 16, 8)
            gravity = android.view.Gravity.CENTER
        }
        honeypotToggle = hpBtn

        actionRow.addView(encBtn)
        actionRow.addView(hpBtn)

        val frame = binding.navBack.parent as? android.widget.FrameLayout
        frame?.addView(
            actionRow,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            ).apply {
                marginStart = (8 * resources.displayMetrics.density).toInt()
            }
        )

        updateEncryptToggleUi()
        updateHoneypotToggleUi()

        encBtn.setOnClickListener {
            val next = SecureStore(this).toggleEncryption(address)
            updateEncryptToggleUi()
            android.widget.Toast.makeText(
                this,
                if (next) "رمزنگاری عددی فعال شد 🔒" else "رمزنگاری عددی غیرفعال شد 🔓 (ارسال با امضای a+)",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        hpBtn.setOnClickListener {
            val next = SecureStore(this).toggleHoneypot(address)
            updateHoneypotToggleUi()
            android.widget.Toast.makeText(
                this,
                if (next) "حالت فریب نفوذگر فعال شد 🎭\n(پیام‌های بدون امضا به پیامک‌های عادی هدایت می‌شوند)"
                else "حالت فریب نفوذگر غیرفعال شد 🔒\n(تمام پیام‌ها بدون استثنا مخفی می‌شوند)",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateEncryptToggleUi() {
        val btn = encryptToggle ?: return
        val isEnc = SecureStore(this).isEncryptionEnabled(address)
        btn.setText(if (isEnc) "🔒" else "🔓")
        btn.setTextColor(if (isEnc) 0xFFFFB300.toInt() else 0xFF888888.toInt())
    }

    private fun updateHoneypotToggleUi() {
        val btn = honeypotToggle ?: return
        val isHp = SecureStore(this).isHoneypotEnabled(address)
        btn.setTextColor(if (isHp) 0xFF4DA3FF.toInt() else 0xFF666666.toInt())
        btn.setAlpha(if (isHp) 1.0f else 0.4f)
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

    companion object {
        @Volatile
        var activeNormalizedAddress: String? = null
    }
}
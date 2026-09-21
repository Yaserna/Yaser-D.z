package com.privatemsg.app.ui
import com.privatemsg.app.data.StarredDbHelper

import android.content.ClipData
import android.content.ClipboardManager
import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.privatemsg.app.R
import com.privatemsg.app.data.FavoritesDbHelper
import com.privatemsg.app.data.Message
import com.privatemsg.app.data.SecureStore
import com.privatemsg.app.data.SimHelper
import com.privatemsg.app.data.SmsRepository
import com.privatemsg.app.databinding.ActivityConversationBinding
import com.privatemsg.app.sms.SmsStatusReceiver

class ConversationActivity : BaseActivity() {

    private lateinit var binding: ActivityConversationBinding
    private lateinit var repo: SmsRepository
    private lateinit var adapter: MessageAdapter
    private var recentsAdapter: ConversationAdapter? = null
    private var threadId: Long = -1
    private var address: String = ""
    private var sims: List<SimHelper.Sim> = emptyList()
    private var simIndex: Int = 0
    private var pickedNumber: String? = null

    companion object {
        /** Normalized address of the conversation currently open on screen (or null). */
        @Volatile
        var activeNormalizedAddress: String? = null
        @Volatile
        var activeThreadId: Long = -1L
    }

    private fun setupRecipientAutocomplete() {
        binding.recipient.setAdapter(ContactSuggestAdapter(this))
        binding.recipient.setOnItemClickListener { parent, _, position, _ ->
            val item = parent.getItemAtPosition(position) as? ContactItem ?: return@setOnItemClickListener
            // Picking a contact jumps straight into their existing conversation.
            enterConversation(item.number)
        }
        binding.recipient.addTextChangedListener(
            onTextChanged = { text, _, _, _ ->
                if (text?.toString() != pickedNumber) pickedNumber = null
            }
        )
        // Typing a phone number and pressing the keyboard's Done also opens its chat.
        binding.recipient.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val num = binding.recipient.text.toString().filter { it.isDigit() || it == '+' }
                if (num.isNotEmpty()) { enterConversation(num); true } else false
            } else false
        }
    }

    /**
     * Switches the compose screen into the conversation with [number]: shows that
     * thread's existing messages (if any) and lets the user type from there.
     */
    private fun enterConversation(number: String) {
        val to = number.trim()
        if (to.isEmpty()) return
        address = to
        pickedNumber = to
        threadId = android.provider.Telephony.Threads.getOrCreateThreadId(this, to)
        binding.recipientRow.visibility = View.GONE
        binding.titleName.text = com.privatemsg.app.data.ContactsHelper(this).displayFor(to)
        binding.titleNumber.text = to
        // Swap the "recent suggestions" list for the real message list.
        binding.recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recycler.adapter = adapter
        setupSim()
        activeNormalizedAddress = SecureStore.normalize(to)
        com.privatemsg.app.sms.Notifier.cancelIncoming(this, to)
        SecureStore(this).recordConversationOpened(to)
        // If nothing is typed yet, bring back this conversation's saved draft.
        if (binding.input.text.isNullOrEmpty()) binding.input.setText(SecureStore(this).getDraft(to))
        repo.markThreadRead(threadId)
        loadMessages()
        binding.input.requestFocus()
    }

    /** Loads recent conversations to suggest as recipients while composing. */
    private fun loadRecents() {
        Thread {
            val secure = SecureStore(this)
            // Only conversations I recently opened, newest first, at most 3.
            val convos = repo.getConversations()
                .filter { secure.openedAt(it.address) > 0L }
                .sortedByDescending { secure.openedAt(it.address) }
                .take(3)
            runOnUiThread { recentsAdapter?.submit(convos) }
        }.start()
    }

    private val pickContact = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val number = c.getString(0) ?: ""
                    // Jump straight into this contact's existing conversation.
                    if (number.isNotEmpty()) enterConversation(number)
                }
            }
        }
    }

    private val pickContactToSend = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0) ?: ""
                    val number = c.getString(1) ?: ""
                    binding.input.setText("$name\n$number")
                }
            }
        }
    }

    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) prefillLocation() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.navBack.setOnClickListener { finish() }
        binding.attachButton.setOnClickListener { showAttachMenu() }
        binding.titleBox.setOnClickListener { openContact() }

        repo = SmsRepository(this)
        threadId = intent.getLongExtra("thread_id", -1)
        address = intent.getStringExtra("address") ?: ""

        if (address.isEmpty()) {
            binding.recipientRow.visibility = View.VISIBLE
            binding.titleName.text = getString(R.string.new_message)
            setupRecipientAutocomplete()
        } else {
            binding.titleName.text =
                com.privatemsg.app.data.ContactsHelper(this).displayFor(address)
            binding.titleNumber.text = address
            binding.recipientRow.visibility = View.GONE
        }

        binding.pickContactButton.setOnClickListener {
            pickContact.launch(
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            )
        }

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
            isStarred = { StarredDbHelper.getInstance(this).isStarred(it.id, isHidden = false) }
        )
        if (address.isEmpty()) {
            // Compose mode: the list area shows recent conversations as quick
            // recipient suggestions (people I recently messaged / opened).
            recentsAdapter = ConversationAdapter(
                contacts = com.privatemsg.app.data.ContactsHelper(this),
                onClick = { conv -> enterConversation(conv.address) }
            )
            binding.recycler.layoutManager = LinearLayoutManager(this)
            binding.recycler.adapter = recentsAdapter
            loadRecents()
        } else {
            binding.recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
            binding.recycler.adapter = adapter
        }

        binding.selCancel.setOnClickListener { adapter.exitSelection() }
        binding.selSelectAll.setOnClickListener { adapter.selectAll() }
        binding.selDelete.setOnClickListener { deleteSelected() }
        binding.selCopy.setOnClickListener { copySelected() }

        binding.sendButton.setOnClickListener { send() }
        intent.getStringExtra("prefill")?.let { binding.input.setText(it) }
        // Restore any unsent draft for this conversation.
        if (binding.input.text.isNullOrEmpty() && address.isNotEmpty()) {
            binding.input.setText(SecureStore(this).getDraft(address))
            binding.input.setSelection(binding.input.text?.length ?: 0)
        }

        // If opened from a decoy notification, drop its fake text into the cover
        // conversation as a received message (with the notification's time).
        val decoyText = intent.getStringExtra("decoy_fake_text")
        val decoyTime = intent.getLongExtra("decoy_fake_time", 0L)
        if (!decoyText.isNullOrBlank() && decoyTime > 0 && address.isNotEmpty()) {
            repo.insertDecoyInbox(address, decoyText, decoyTime)
        }

        // Opened from the notification's "Reply" button → pop the keyboard.
        if (intent.getBooleanExtra("focus_input", false)) {
            binding.input.requestFocus()
            binding.input.post {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(binding.input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        loadMessages()

        // Refresh live when message rows change (e.g. delivery status updates).
        contentResolver.registerContentObserver(
            android.provider.Telephony.Sms.CONTENT_URI, true, smsObserver
        )
        ContextCompat.registerReceiver(
            this, refreshReceiver,
            IntentFilter(SmsStatusReceiver.ACTION_SMS_REFRESH),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (threadId > 0) {
                repo.markThreadRead(threadId)
                loadMessages()
            }
        }
    }

    private val smsObserver = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) {
            if (threadId > 0) {
                // The chat is open, so keep its messages read while a new one arrives.
                repo.markThreadRead(threadId)
                loadMessages()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Mark this conversation as the one currently open, so a new message from it
        // shows live here (with a tiny vibration) instead of posting a notification.
        activeThreadId = threadId
        activeNormalizedAddress =
            if (address.isNotEmpty()) SecureStore.normalize(address) else null
        // Opening the conversation clears its notification (so it doesn't linger).
        if (address.isNotEmpty()) {
            com.privatemsg.app.sms.Notifier.cancelIncoming(this, address)
            // Remember this open so it ranks high in "new message" suggestions.
            SecureStore(this).recordConversationOpened(address)
        }
        if (threadId > 0) {
            repo.markThreadRead(threadId)
            loadMessages()
        }
    }

    override fun onPause() {
        super.onPause()
        activeThreadId = -1L
        activeNormalizedAddress = null
        // Keep whatever is typed as a draft so it isn't lost on back/exit.
        if (address.isNotEmpty()) SecureStore(this).setDraft(address, binding.input.text.toString())
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(smsObserver)
        try { unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
    }

    private fun setupSim() {
        val helper = SimHelper(this)
        sims = helper.sims()
        if (sims.size >= 2) {
            // Prefer the SIM remembered for this conversation, else the default.
            val saved = SecureStore(this).getThreadSim(address)
            val preferred = if (saved >= 0) saved else helper.defaultSubId()
            simIndex = sims.indexOfFirst { it.subId == preferred }.let { if (it >= 0) it else 0 }
            binding.simBadge.visibility = View.VISIBLE
            binding.simBadge.text = sims[simIndex].slot.toString()
            binding.simBadge.setOnClickListener {
                simIndex = (simIndex + 1) % sims.size
                binding.simBadge.text = sims[simIndex].slot.toString()
                if (address.isNotEmpty()) SecureStore(this).setThreadSim(address, sims[simIndex].subId)
            }
        } else {
            binding.simBadge.visibility = View.GONE
        }
    }

    /**
     * Sends [body], splitting it into multiple parts when needed. Long or
     * Persian (Unicode) messages exceed a single SMS, so a plain sendTextMessage
     * would silently fail to deliver — divideMessage + multipart fixes that.
     */
    private fun sendViaSms(
        to: String,
        body: String,
        sentPi: android.app.PendingIntent?,
        deliveredPi: android.app.PendingIntent?
    ) {
        val sm = smsManager()
        val parts = sm.divideMessage(body)
        if (parts.size > 1) {
            val sentList = ArrayList<android.app.PendingIntent>()
            val delList = ArrayList<android.app.PendingIntent>()
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

    private fun showAttachMenu() {
        val options = arrayOf(
            getString(R.string.attach_contact),
            getString(R.string.attach_location)
        )
        showListMenu(options) { which ->
            when (which) {
                0 -> pickContactToSend.launch(
                    Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                )
                1 -> if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                ) prefillLocation()
                else requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun updateSelectionUi() {
        val on = adapter.selectionMode
        binding.selectionBar.visibility = if (on) View.VISIBLE else View.GONE
        binding.navBack.visibility = if (on) View.GONE else View.VISIBLE
        binding.titleBox.visibility = if (on) View.GONE else View.VISIBLE
        if (on) binding.selCount.text = getString(R.string.n_selected, adapter.selectedCount())
    }

        private fun deleteSelected() {
        val selected = adapter.selectedMessages()
        if (selected.isEmpty()) return
        val starredDb = StarredDbHelper.getInstance(this)
        val (starred, unstarred) = selected.partition { starredDb.isStarred(it.id, isHidden = false) }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setMessage("در حال حذف پیام‌ها...")
            .setCancelable(false)
            .create()
        dialog.show()

        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            try {
                repo.deleteMessages(unstarred.map { it.id })
            } finally {
                runOnUiThread {
                    try { dialog.dismiss() } catch (_: Exception) {}
                    if (starred.isNotEmpty()) {
                        Toast.makeText(this, "${starred.size} پیام ستاره‌دار محافظت شدند.", Toast.LENGTH_LONG).show()
                    }
                    adapter.exitSelection()
                    loadMessages()
                }
            }
        }
    }

    private fun copySelected() {
        val text = adapter.selectedMessages().joinToString("\n") { it.body }
        if (text.isNotEmpty()) copyText(text)
        adapter.exitSelection()
    }

    override fun onBackPressed() {
        if (adapter.selectionMode) adapter.exitSelection() else super.onBackPressed()
    }

    private fun prefillLocation() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                val link = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
                binding.input.setText(link)
                binding.input.setSelection(link.length)
            } else {
                Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            // permission missing
        }
    }

    /**
     * Tapping the chat title/number opens the number in Contacts/Phone: if the
     * number is already saved, open its contact card to manage it; otherwise
     * open the dialer's number page (ACTION_VIEW tel:) where MIUI shows the
     * number with its call history and add/manage options.
     */
    private fun openContact() {
        if (address.isEmpty()) return
        val digits = address.filter { it.isDigit() || it == '+' }
        // Operator / alphanumeric senders have no real number → nothing to manage.
        if (digits.length < 4) return

        val lookupUri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(address)
        )
        var contactUri: android.net.Uri? = null
        contentResolver.query(
            lookupUri,
            arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                contactUri = ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1))
            }
        }

        val intent = contactUri?.let { Intent(Intent.ACTION_VIEW, it) }
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("tel:$digits"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // No app to handle it; ignore.
        }
    }

        private fun showMessageMenu(m: Message) {
        val starredDb = StarredDbHelper.getInstance(this)
        val isStarredMsg = starredDb.isStarred(m.id, isHidden = false)
        val options = arrayOf(
            getString(R.string.choose),
            if (isStarredMsg) "حذف ستاره" else "ستاره‌دار کردن (محافظت از حذف)",
            getString(R.string.copy),
            getString(R.string.forward),
            getString(R.string.delete),
            getString(R.string.details)
        )
        showListMenu(options) { which ->
            when (which) {
                0 -> adapter.startSelection(m)
                1 -> {
                    val newlyStarred = starredDb.toggleStar(
                        messageId = m.id,
                        isHidden = false,
                        threadId = threadId,
                        address = address,
                        body = m.body,
                        date = m.date
                    )
                    adapter.notifyDataSetChanged()
                    Toast.makeText(
                        this,
                        if (newlyStarred) "پیام ستاره‌دار شد و به برگزیده‌ها اضافه گردید" else "ستاره پیام برداشته شد",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                2 -> copyText(m.body)
                3 -> startActivity(
                    Intent(this, ConversationActivity::class.java)
                        .putExtra("prefill", m.body)
                )
                4 -> {
                    if (isStarredMsg) {
                        Toast.makeText(this, "این پیام ستاره‌دار است و محافظت شده است.", Toast.LENGTH_LONG).show()
                    } else {
                        repo.deleteMessage(m.id)
                        loadMessages()
                    }
                }
                5 -> showDetails(m)
            }
        }
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun showDetails(m: Message) {
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

    private fun statusPendingIntent(action: String, uri: android.net.Uri?): android.app.PendingIntent? {
        if (uri == null) return null
        val intent = Intent(this, SmsStatusReceiver::class.java).setAction(action).setData(uri)
        return android.app.PendingIntent.getBroadcast(
            this, (uri.toString() + action).hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

        private fun loadMessages() {
        if (threadId > 0) {
            val msgs = repo.getMessages(threadId)
            adapter.submit(msgs)
            binding.recycler.scrollToPosition(adapter.itemCount - 1)
            autoSelectSim(msgs)
        }
    }

    /**
     * The reply SIM automatically follows the SIM of the most recent RECEIVED
     * message: a new incoming message on SIM 2 selects SIM 2 for the reply, and a
     * later one on SIM 1 selects SIM 1. The user can still tap the badge to change
     * it; that choice holds until the next incoming message arrives.
     */
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

    private fun send() {
        val body = binding.input.text.toString().trim()
        val to = if (address.isNotEmpty()) address
        else (pickedNumber ?: binding.recipient.text.toString()).filter { it.isDigit() || it == '+' }
        if (body.isEmpty() || to.isEmpty()) return

        val subId = if (sims.isNotEmpty()) sims[simIndex].subId else -1
        if (subId >= 0) SecureStore(this).setThreadSim(to, subId)
        val uri = repo.storeSentMessage(to, body, subId)
        val sentPi = statusPendingIntent(SmsStatusReceiver.ACTION_SENT, uri)
        val deliveredPi =
            if (SecureStore(this).deliveryReportForSub(subId))
                statusPendingIntent(SmsStatusReceiver.ACTION_DELIVERED, uri)
            else null
        sendViaSms(to, body, sentPi, deliveredPi)

        binding.input.setText("")
        SecureStore(this).setDraft(to, "")
        address = to
        threadId = android.provider.Telephony.Threads.getOrCreateThreadId(this, to)
        binding.titleName.text =
            com.privatemsg.app.data.ContactsHelper(this).displayFor(to)
        binding.titleNumber.text = to
        binding.recipientRow.visibility = View.GONE
        // If we sent from the "new message" compose screen, the list still shows the
        // recipient suggestions — swap it for the real message list so the sent
        // message actually appears.
        if (binding.recycler.adapter !== adapter) {
            binding.recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
            binding.recycler.adapter = adapter
            activeNormalizedAddress = SecureStore.normalize(to)
        }
        loadMessages()
    }
}

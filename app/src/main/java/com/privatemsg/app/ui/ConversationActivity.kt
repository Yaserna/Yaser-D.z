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
import android.os.Bundle
import android.provider.ContactsContract
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
import com.privatemsg.app.data.ContactsHelper
import com.privatemsg.app.data.Message
import com.privatemsg.app.data.SecureStore
import com.privatemsg.app.data.SimHelper
import com.privatemsg.app.data.SmsRepository
import com.privatemsg.app.databinding.ActivityConversationBinding
import com.privatemsg.app.sms.SmsSender
import com.privatemsg.app.sms.SmsStatusReceiver

class ConversationActivity : BaseActivity() {

    private lateinit var binding: ActivityConversationBinding
    private lateinit var repo: SmsRepository
    private lateinit var contacts: ContactsHelper
    private lateinit var adapter: MessageAdapter
    private var recentsAdapter: ConversationAdapter? = null
    private var threadId: Long = -1
    private var address: String = ""
    private var sims: List<SimHelper.Sim> = emptyList()
    private var simIndex: Int = 0
    private var pickedNumber: String? = null
    // Values this instance wrote into the companion statics; onPause only erases
    // them if they still match, so a reversed onPause/onResume (multi-window,
    // direct activity-to-activity transitions) can't unmark the newly opened chat.
    private var ownActiveThreadId: Long = -1L
    private var ownActiveNormalizedAddress: String? = null
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** The blocking "working..." dialog, if one is showing (dismissed with the screen). */
    private var busyDialog: android.app.Dialog? = null

    /** Runs [task] on this screen's worker thread; false if the screen is already gone. */
    private fun runIo(task: () -> Unit): Boolean {
        return try {
            ioExecutor.execute(task)
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            false
        }
    }

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
        binding.recipientRow.visibility = View.GONE
        // The number is shown at once; the contact's name replaces it when the (slow)
        // contacts query answers, so the screen switch never waits on the provider.
        binding.titleName.text = to
        binding.titleNumber.text = to
        // Swap the "recent suggestions" list for the real message list.
        binding.recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recycler.adapter = adapter
        setupSim()
        ownActiveNormalizedAddress = SecureStore.normalize(to)
        activeNormalizedAddress = ownActiveNormalizedAddress
        com.privatemsg.app.sms.Notifier.cancelIncoming(this, to)
        val app = applicationContext
        runIo {
            val secure = SecureStore(app)
            secure.recordConversationOpened(to)
            val name = runCatching { contacts.displayFor(to) }.getOrNull()
            val draft = runCatching { secure.getDraft(to) }.getOrNull()
            val thread = threadIdFor(app, to)
            runOnUiThread {
                if (isFinishing || address != to) return@runOnUiThread
                if (!name.isNullOrEmpty()) binding.titleName.text = name
                if (thread > 0) {
                    threadId = thread
                    claimActive(thread, ownActiveNormalizedAddress)
                }
                // Only restore a draft while nothing has been typed yet.
                if (binding.input.text.isNullOrEmpty() && !draft.isNullOrEmpty()) {
                    binding.input.setText(draft)
                    binding.input.setSelection(binding.input.text?.length ?: 0)
                }
                markRead(threadId)
                loadMessages()
                binding.input.requestFocus()
            }
        }
    }

    /**
     * The system thread for a number. This is a provider call that can throw (an
     * alphanumeric sender, a number too short to resolve), and it must never run on
     * the UI thread — a failure leaves 0, which every caller already treats as
     * "no existing thread".
     */
    private fun threadIdFor(context: Context, number: String): Long = try {
        android.provider.Telephony.Threads.getOrCreateThreadId(context, number)
    } catch (e: Exception) {
        android.util.Log.e("ConversationActivity", "no thread for this number", e)
        0L
    }

    /** Loads recent conversations to suggest as recipients while composing. */
    private fun loadRecents() {
        runIo {
            try {
                val secure = SecureStore(this)
                // Only conversations I recently opened, newest first, at most 3.
                val convos = repo.getConversations()
                    .filter { secure.openedAt(it.address) > 0L }
                    .sortedByDescending { secure.openedAt(it.address) }
                    .take(3)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) recentsAdapter?.submit(convos)
                }
            } catch (e: Exception) {
                android.util.Log.e("ConversationActivity", "recents load failed", e)
            }
        }
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
                    binding.input.setText(
                        getString(R.string.contact_card_format, name, number)
                    )
                }
            }
        }
    }

    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Android 12+ lets the user hand over only the approximate level, so either
        // grant is usable; a flat refusal has to be said out loud, not swallowed.
        if (grants.values.any { it }) prefillLocation()
        else Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.navBack.setOnClickListener { finish() }
        binding.attachButton.setOnClickListener { showAttachMenu() }
        binding.titleBox.setOnClickListener { openContact() }

        repo = SmsRepository(this)
        contacts = ContactsHelper(this)
        threadId = intent.getLongExtra("thread_id", -1)
        address = intent.getStringExtra("address") ?: ""

        if (address.isEmpty()) {
            binding.recipientRow.visibility = View.VISIBLE
            binding.titleName.text = getString(R.string.new_message)
            setupRecipientAutocomplete()
        } else {
            // The contacts provider is not instant; the number holds the title until the
            // saved name arrives on the UI thread.
            binding.titleName.text = address
            binding.titleNumber.text = address
            binding.recipientRow.visibility = View.GONE
            val forAddress = address
            val app = applicationContext
            runIo {
                val name = runCatching { contacts.displayFor(forAddress) }.getOrNull()
                // Opened without a usable thread (an intent from another app): resolve it
                // here, otherwise the chat would look empty even though messages exist.
                val thread = if (threadId > 0) threadId else threadIdFor(app, forAddress)
                runOnUiThread {
                    if (isFinishing || address != forAddress) return@runOnUiThread
                    if (!name.isNullOrEmpty()) binding.titleName.text = name
                    if (thread > 0 && threadId <= 0) {
                        threadId = thread
                        claimActive(thread, SecureStore.normalize(forAddress))
                        markRead(thread)
                        loadMessages()
                    }
                }
            }
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
                contacts = contacts,
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
        // Restore any unsent draft for this conversation. The draft is stored ciphered,
        // and the first decryption of a process touches the hardware keystore - which can
        // hold the screen for hundreds of milliseconds right when the chat is opened. It
        // is read on the worker and painted when it answers, only if nothing was typed.
        if (address.isNotEmpty()) {
            val forDraft = address
            val app = applicationContext
            runIo {
                val draft = runCatching { SecureStore(app).getDraft(forDraft) }.getOrNull()
                runOnUiThread {
                    if (isFinishing || address != forDraft) return@runOnUiThread
                    if (binding.input.text.isNullOrEmpty() && !draft.isNullOrEmpty()) {
                        binding.input.setText(draft)
                        binding.input.setSelection(binding.input.text?.length ?: 0)
                    }
                }
            }
        }

        // If opened from a decoy notification, drop its fake text into the cover
        // conversation as a received message (with the notification's time).
        val decoyText = intent.getStringExtra("decoy_fake_text")
        val decoyTime = intent.getLongExtra("decoy_fake_time", 0L)
        if (!decoyText.isNullOrBlank() && decoyTime > 0 && address.isNotEmpty()) {
            // A provider insert on the way in: it belongs on the worker, and its failure
            // must not take the conversation down with it.
            val forDecoy = address
            runIo {
                try {
                    repo.insertDecoyInbox(forDecoy, decoyText, decoyTime)
                } catch (e: Exception) {
                    android.util.Log.e("ConversationActivity", "decoy insert failed", e)
                }
            }
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

        // Back leaves selection mode first, otherwise it closes the screen. Registered
        // with this activity's lifecycle, so it can never outlive the window.
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
            if (threadId > 0) {
                markRead(threadId)
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
                markRead(threadId)
                loadMessages()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Mark this conversation as the one currently open, so a new message from it
        // shows live here (with a tiny vibration) instead of posting a notification.
        claimActive(threadId, if (address.isNotEmpty()) SecureStore.normalize(address) else null)
        // Opening the conversation clears its notification (so it doesn't linger).
        if (address.isNotEmpty()) {
            com.privatemsg.app.sms.Notifier.cancelIncoming(this, address)
            // Remember this open so it ranks high in "new message" suggestions.
            SecureStore(this).recordConversationOpened(address)
        }
        if (threadId > 0) {
            markRead(threadId)
            loadMessages()
        }
    }

    override fun onPause() {
        super.onPause()
        // Erase the open-chat marks only if the statics still hold THIS screen's values.
        // Android can deliver a new screen's onResume before this onPause runs
        // (multi-window, direct activity-to-activity transitions); an unconditional
        // clear here would unmark the chat that is actually on screen and let its
        // notifications fire.
        if (activeThreadId == ownActiveThreadId) activeThreadId = -1L
        if (activeNormalizedAddress == ownActiveNormalizedAddress) {
            activeNormalizedAddress = null
            ownActiveNormalizedAddress = null
        }
        // Keep whatever is typed as a draft so it isn't lost on back/exit.
        if (address.isNotEmpty()) SecureStore(this).setDraft(address, binding.input.text.toString())
    }

    /** Write (and claim ownership of) the process-wide "this chat is open" statics. */
    private fun claimActive(thread: Long, normalized: String?) {
        ownActiveThreadId = thread
        ownActiveNormalizedAddress = normalized
        activeThreadId = thread
        activeNormalizedAddress = normalized
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
        // A progress dialog whose worker outlives the screen leaks its window.
        try { busyDialog?.dismiss() } catch (_: Exception) {}
        busyDialog = null
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
     * The SIM chosen for this screen, or -1 when the phone reports none. Indexed
     * defensively: a SIM can be pulled out between the badge tap and a send, and an
     * out-of-range index would crash the screen.
     */
    private fun currentSubId(): Int =
        sims.getOrNull(simIndex)?.subId ?: -1

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
                1 -> if (hasAnyLocationPermission()) prefillLocation()
                else requestLocation.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
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
            .setMessage(R.string.deleting_messages)
            .setCancelable(false)
            .create()
        dialog.show()
        busyDialog = dialog

        val started = runIo {
            try {
                repo.deleteMessages(unstarred.map { it.id })
            } catch (e: Exception) {
                android.util.Log.e("ConversationActivity", "delete failed", e)
            } finally {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (busyDialog === dialog) busyDialog = null
                    try { dialog.dismiss() } catch (_: Exception) {}
                    if (starred.isNotEmpty()) {
                        Toast.makeText(
                            this, getString(R.string.n_starred_protected, starred.size),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    adapter.exitSelection()
                    loadMessages()
                }
            }
        }
        if (!started) {
            if (busyDialog === dialog) busyDialog = null
            try { dialog.dismiss() } catch (_: Exception) {}
        }
    }

    private fun copySelected() {
        val text = adapter.selectedMessages().joinToString("\n") { it.body }
        if (text.isNotEmpty()) copyText(text)
        adapter.exitSelection()
    }

    private fun hasAnyLocationPermission(): Boolean =
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
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
            // Only the approximate level was granted, so a provider can refuse the read.
            Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_SHORT).show()
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

        val app = applicationContext
        // A contacts lookup is a provider query: run it on the worker and open the
        // result once it is back on the UI thread.
        runIo {
            val contactUri = runCatching { lookupContactUri(app) }.getOrNull()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val intent = contactUri?.let { Intent(Intent.ACTION_VIEW, it) }
                    ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("tel:$digits"))
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    // No app to handle it; ignore.
                }
            }
        }
    }

    private fun lookupContactUri(context: Context): android.net.Uri? {
        val lookupUri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(address)
        )
        var result: android.net.Uri? = null
        context.contentResolver.query(
            lookupUri,
            arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                result = ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1))
            }
        }
        return result
    }

        private fun showMessageMenu(m: Message) {
        val starredDb = StarredDbHelper.getInstance(this)
        val isStarredMsg = starredDb.isStarred(m.id, isHidden = false)
        val options = arrayOf(
            getString(R.string.choose),
            if (isStarredMsg) getString(R.string.star_remove) else getString(R.string.star_add),
            getString(R.string.copy),
            getString(R.string.forward),
            getString(R.string.delete),
            getString(R.string.details)
        )
        showListMenu(options) { which ->
            when (which) {
                0 -> adapter.startSelection(m)
                1 -> {
                    // The star row is written to the private database; that stays off the
                    // UI thread, and only the badge repaint comes back to it.
                    runIo {
                        val newlyStarred = starredDb.toggleStar(
                            messageId = m.id,
                            isHidden = false,
                            threadId = threadId,
                            address = address,
                            body = m.body,
                            date = m.date
                        )
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            adapter.notifyDataSetChanged()
                            Toast.makeText(
                                this,
                                getString(if (newlyStarred) R.string.star_added else R.string.star_removed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                2 -> copyText(m.body)
                3 -> startActivity(
                    Intent(this, ConversationActivity::class.java)
                        .putExtra("prefill", m.body)
                )
                4 -> {
                    if (isStarredMsg) {
                        Toast.makeText(this, R.string.starred_protected, Toast.LENGTH_LONG).show()
                    } else {
                        runIo {
                            try {
                                repo.deleteMessage(m.id)
                            } catch (e: Exception) {
                                android.util.Log.e("ConversationActivity", "delete failed", e)
                            }
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) loadMessages()
                            }
                        }
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

    /**
     * Marking the thread read is a provider write; on a chat with many unread messages it
     * blocks long enough to drop frames, so it runs on this screen's worker. Queued ahead of
     * the load on the same single thread, the list still reads the freshly-cleared state.
     */
    private fun markRead(id: Long) {
        if (id <= 0) return
        runIo {
            try {
                repo.markThreadRead(id)
            } catch (e: Exception) {
                android.util.Log.e("ConversationActivity", "mark read failed", e)
            }
        }
    }

    private fun loadMessages() {
        val id = threadId
        if (id <= 0) return
        // Reading a thread is a provider query: it can take long enough to drop
        // frames, so it never runs on the UI thread.
        runIo {
            val msgs = try {
                repo.getMessages(id)
            } catch (e: Exception) {
                android.util.Log.e("ConversationActivity", "load failed", e)
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || threadId != id) return@runOnUiThread
                adapter.submit(msgs)
                if (adapter.itemCount > 0) binding.recycler.scrollToPosition(adapter.itemCount - 1)
                autoSelectSim(msgs)
            }
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

    /**
     * Sending means three slow calls in a row — a provider insert, the radio's
     * segmentation, and the radio hand-over itself — so it runs on this screen's
     * worker. The radio throws for a bad number or an unavailable service; that must
     * end in a queued outbox row, not a crash or a message that looks sent.
     */
    private fun send() {
        val body = binding.input.text.toString().trim()
        val to = if (address.isNotEmpty()) address
        else (pickedNumber ?: binding.recipient.text.toString()).filter { it.isDigit() || it == '+' }
        if (body.isEmpty() || to.isEmpty()) return

        val subId = currentSubId()
        binding.input.setText("")
        binding.sendButton.isEnabled = false
        val app = applicationContext
        runIo {
            val secure = SecureStore(app)
            if (subId >= 0) secure.setThreadSim(to, subId)
            val thread = threadIdFor(app, to)
            val uri = repo.storeSentMessage(to, body, subId)
            val parts = SmsSender.split(app, subId, body)
            val sent = SmsSender.send(
                app, subId, to, parts,
                uri?.let {
                    SmsStatusReceiver.statusIntent(app, SmsStatusReceiver.ACTION_SENT, it, parts.size)
                },
                uri?.takeIf { secure.deliveryReportForSub(subId) }?.let {
                    SmsStatusReceiver.statusIntent(
                        app, SmsStatusReceiver.ACTION_DELIVERED, it, parts.size
                    )
                }
            )
            if (!sent && uri != null) {
                // The radio refused it: keep the text queued and visibly unsent instead
                // of leaving a row that carries a false sent tick.
                repo.queueFailedSend(uri, to, body, thread, subId)
            }
            secure.setDraft(to, "")
            val name = runCatching { contacts.displayFor(to) }.getOrNull()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                binding.sendButton.isEnabled = true
                if (!sent && uri == null) {
                    // Nothing was stored and nothing was sent: hand the text back so it
                    // is not silently lost, and leave the draft untouched.
                    binding.input.setText(body)
                    Toast.makeText(this, R.string.send_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (!sent) Toast.makeText(this, R.string.send_failed, Toast.LENGTH_LONG).show()
                address = to
                if (thread > 0) threadId = thread
                binding.titleName.text = if (name.isNullOrEmpty()) to else name
                binding.titleNumber.text = to
                binding.recipientRow.visibility = View.GONE
                // If we sent from the "new message" compose screen, the list still shows
                // the recipient suggestions — swap it for the real message list so the
                // sent message actually appears.
                if (binding.recycler.adapter !== adapter) {
                    binding.recycler.layoutManager =
                        LinearLayoutManager(this).apply { stackFromEnd = true }
                    binding.recycler.adapter = adapter
                }
                // This chat is now the one on screen: notifications for it must stay
                // silent, including the ones that arrive before the next onResume.
                claimActive(threadId, SecureStore.normalize(to))
                loadMessages()
            }
        }
    }
}

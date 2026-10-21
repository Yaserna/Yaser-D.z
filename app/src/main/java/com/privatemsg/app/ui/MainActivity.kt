package com.privatemsg.app.ui
import com.privatemsg.app.data.StarredDbHelper

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.privatemsg.app.R
import com.privatemsg.app.data.Conversation
import com.privatemsg.app.data.ContactsHelper
import com.privatemsg.app.data.SecureStore
import com.privatemsg.app.data.SmsRepository
import com.privatemsg.app.databinding.ActivityMainBinding

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: SmsRepository
    private lateinit var secure: SecureStore
    private lateinit var contacts: ContactsHelper
    private lateinit var adapter: ConversationAdapter
    private var askedDefault = false
    // Read from the worker thread during bulk actions, replaced on the UI thread.
    @Volatile private var allConvos: List<Conversation> = emptyList()
    private var archivedConvos: List<Conversation> = emptyList()
    private var searchIndex: List<Pair<Conversation, String>> = emptyList()
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** True once a conversation load has succeeded; gates the role re-offer below. */
    @Volatile private var everLoaded = false
    private var reaskRemaining = 2
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var refreshAgain = false

    /** Queues [task] on the worker thread; false once the screen has been torn down. */
    private fun runIo(task: () -> Unit): Boolean {
        return try {
            ioExecutor.execute(task)
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            false
        }
    }
    private val fabHandler = Handler(Looper.getMainLooper())
    private val mainReloadHandler = Handler(Looper.getMainLooper())
    private var longFired = false
    private val hiddenRunnable = Runnable {
        longFired = true
        // First-time setup ONLY: while no secret code exists yet, a long-press on +
        // opens the hidden section so the code can be created. Once a code is set,
        // this entrance is disabled — the only way in is typing the code in search.
        if (!secure.hasPin()) startActivity(Intent(this, PinActivity::class.java))
    }
    private val settingsHandler = Handler(Looper.getMainLooper())
    private var settingsLongFired = false
    private val settingsRunnable = Runnable {
        settingsLongFired = true
        // Skip the PIN: this backdoor opens the hidden section straight away.
        BaseActivity.hiddenLocked = false
        startActivity(Intent(this, HiddenActivity::class.java))
    }
    /** Coalesces provider notifications: one burst of row changes is one reload. */
    private val reloadRunnable = Runnable {
        if (!adapter.selectionMode) refresh()
    }
    private val smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (adapter.selectionMode) return
            mainReloadHandler.removeCallbacks(reloadRunnable)
            mainReloadHandler.postDelayed(reloadRunnable, 250L)
        }
    }

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = SmsRepository(this)
        secure = SecureStore(this)
        contacts = ContactsHelper(this)
        adapter = ConversationAdapter(
            contacts = contacts,
            onClick = { conv ->
                val i = Intent(this, ConversationActivity::class.java)
                i.putExtra("thread_id", conv.threadId)
                i.putExtra("address", conv.address)
                startActivity(i)
            },
            onLongClick = { conv -> adapter.startSelection(conv) },
            onSelectionChanged = { updateSelectionUi() },
            // The contact names arrive after the list is on screen; the search index
            // is rebuilt with them so a search by name still finds the row.
            onNamesResolved = { rebuildSearchIndex() }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fab.setOnClickListener {
            startActivity(Intent(this, ConversationActivity::class.java))
        }
        // Secret entrance to the hidden section: a long (~2s) press on the + button.
        binding.fab.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longFired = false
                    fabHandler.postDelayed(hiddenRunnable, 2000L)
                    false
                }
                MotionEvent.ACTION_UP -> {
                    fabHandler.removeCallbacks(hiddenRunnable)
                    longFired
                }
                MotionEvent.ACTION_CANCEL -> {
                    fabHandler.removeCallbacks(hiddenRunnable)
                    false
                }
                else -> false
            }
        }

        // Secret "Private" row (only visible once the exact code is typed in search):
        // opens the PIN screen (code / fingerprint) which then unlocks the hidden section.
        binding.privateEntry.setOnClickListener {
            clearSearch()
            startActivity(Intent(this, PinActivity::class.java))
        }

        binding.favoritesButton.setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // Backdoor: holding the settings gear ~20s opens the hidden section
        // directly, without the PIN.
        binding.settingsButton.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    settingsLongFired = false
                    settingsHandler.postDelayed(settingsRunnable, 20000L)
                    false
                }
                MotionEvent.ACTION_UP -> {
                    settingsHandler.removeCallbacks(settingsRunnable)
                    settingsLongFired
                }
                MotionEvent.ACTION_CANCEL -> {
                    settingsHandler.removeCallbacks(settingsRunnable)
                    false
                }
                else -> false
            }
        }

        binding.cancelButton.setOnClickListener { adapter.exitSelection() }
        binding.selectAllButton.setOnClickListener { adapter.selectAll() }
        binding.actionDelete.setOnClickListener { applyToSelection(Action.DELETE) }
        binding.actionRead.setOnClickListener { applyToSelection(Action.READ) }
        binding.actionPin.setOnClickListener { applyToSelection(Action.PIN) }
        binding.actionArchive.setOnClickListener { applyToSelection(Action.ARCHIVE) }
        binding.actionMute.setOnClickListener { applyToSelection(Action.MUTE) }

        // The archive button in the top bar opens the archive screen.
        binding.archiveButton.setOnClickListener {
            startActivity(Intent(this, ArchiveActivity::class.java))
        }

        binding.searchInput.addTextChangedListener { applyFilter(it?.toString().orEmpty()) }

        setupPullToPrivate()

        if (savedInstanceState == null) handleComposeIntent(intent)

        contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI, true, smsObserver
        )

        requestPermissionsIfNeeded()

        // If the app closed on its own last time, say so now and hand over the reason:
        // the trace is what identifies the failing line, and it is lost once the process
        // dies. Reading it consumes the stored copy, so it appears exactly once.
        com.privatemsg.app.App.takeLastCrash(this)?.let { trace ->
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.crash_report_title)
                .setMessage(trace)
                .setPositiveButton(R.string.copy) { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", trace))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.show()
            trackDialog(dialog)
        }

        // Back: leave selection mode, then clear an active search, then close the app.
        // (The same order the old onBackPressed override had, on the modern API.)
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        adapter.selectionMode -> adapter.exitSelection()
                        // A search is active → back clears it and returns to the full list,
                        // instead of leaving the screen stuck in the filtered state.
                        !binding.searchInput.text.isNullOrEmpty() -> clearSearch()
                        binding.searchInput.hasFocus() -> binding.searchInput.clearFocus()
                        else -> finish()
                    }
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(smsObserver)
        mainReloadHandler.removeCallbacks(reloadRunnable)
        ioExecutor.shutdown()
        cancelLongPresses()
    }

    override fun onPause() {
        super.onPause()
        // A half-done hold must not fire after the screen is gone (it would launch an
        // activity from a paused/destroyed context).
        cancelLongPresses()
        // If the pull gesture is stolen by the window (a call, a notification shade) the
        // up/cancel event never arrives, and the list would stay shifted down over the
        // private-folder hint. Snap it back whenever the screen leaves the foreground.
        binding.recycler.animate().cancel()
        binding.recycler.translationY = 0f
        pulling = false
    }

    private fun cancelLongPresses() {
        fabHandler.removeCallbacks(hiddenRunnable)
        settingsHandler.removeCallbacks(settingsRunnable)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleComposeIntent(intent)
    }

    /** When another app (e.g. Contacts) asks to send an SMS, open the compose screen. */
    private fun handleComposeIntent(intent: Intent?) {
        if (intent == null) return
        // Text shared from another app → open a new message with the text prefilled.
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val shared = (intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())?.trim().orEmpty()
            if (shared.isNotEmpty()) {
                startActivity(
                    Intent(this, ConversationActivity::class.java).putExtra("prefill", shared)
                )
            }
            return
        }
        val data = intent.data ?: return
        val scheme = data.scheme ?: return
        if (scheme !in listOf("sms", "smsto", "mms", "mmsto")) return
        val number = data.schemeSpecificPart?.substringBefore('?')?.trim().orEmpty()
        val body = intent.getStringExtra("sms_body").orEmpty()
        val i = Intent(this, ConversationActivity::class.java)
        if (number.isNotEmpty()) {
            i.putExtra("address", number)
            // An SMS: link from another app can carry anything as its "number"; the
            // thread lookup throws instead of guessing, and -1 lets the compose screen
            // resolve it itself once it is on a worker thread.
            val thread = try {
                Telephony.Threads.getOrCreateThreadId(this, number)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "no thread for ${number.take(4)}…", e)
                -1L
            }
            i.putExtra("thread_id", thread)
        }
        if (body.isNotEmpty()) i.putExtra("prefill", body)
        startActivity(i)
    }

    private enum class Action { DELETE, READ, PIN, ARCHIVE, MUTE }

    /**
     * A bulk flag switch must end in ONE state for the whole selection: clear the flag
     * only when every selected chat already has it, otherwise set it on all of them.
     * (Toggling item by item left a mixed result nobody expected.)
     */
    private fun bulkTarget(addresses: List<String>, isOn: (String) -> Boolean): Boolean =
        !addresses.all { isOn(it) }

    private fun applyToSelection(action: Action) {
        val ids = adapter.selectedThreadIds()
        val addresses = ids.mapNotNull { id ->
            allConvos.firstOrNull { it.threadId == id }?.address?.takeIf { it.isNotBlank() }
        }
        val wantPin = bulkTarget(addresses) { secure.isPinned(it) }
        val wantMute = bulkTarget(addresses) { secure.isMuted(it) }
        val wantArchive = bulkTarget(addresses) { secure.isArchived(it) }
        val starredDb = StarredDbHelper.getInstance(this)
        // Provider deletes/updates are far too slow for the UI thread.
        runIo {
            var protectedCount = 0
            try {
                for (id in ids) {
                    val addr = allConvos.firstOrNull { it.threadId == id }?.address.orEmpty()
                    when (action) {
                        Action.DELETE -> {
                            val isProtected = (addr.isNotBlank() && starredDb.isThreadStarred(addr)) ||
                                    starredDb.getStarredSystemMessageIds(id).isNotEmpty()
                            if (isProtected) {
                                protectedCount++
                            } else {
                                repo.deleteThread(id)
                            }
                        }
                        Action.READ -> repo.markThreadRead(id)
                        // Every flag is stored and read by address, so a thread without one
                        // has nowhere to record the change - writing it by thread id would go
                        // into a set no screen ever looks at.
                        Action.PIN -> if (addr.isNotBlank()) secure.setPinned(addr, wantPin)
                        Action.MUTE -> if (addr.isNotBlank()) secure.setMuted(addr, wantMute)
                        Action.ARCHIVE -> if (addr.isNotBlank()) secure.setArchived(addr, wantArchive)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "bulk action failed", e)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (protectedCount > 0) {
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.n_starred_threads_protected, protectedCount),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                adapter.exitSelection()
                refresh()
            }
        }
    }

    private fun updateSelectionUi() {
        val on = adapter.selectionMode
        binding.settingsButton.visibility = if (on) View.GONE else View.VISIBLE
        updateArchiveBadge()
        binding.selectAllButton.visibility = if (on) View.VISIBLE else View.GONE
        binding.cancelButton.visibility = if (on) View.VISIBLE else View.GONE
        binding.searchBar.visibility = if (on) View.GONE else View.VISIBLE
        binding.favoritesButton.visibility = if (on) View.GONE else View.VISIBLE
        binding.selectionBar.visibility = if (on) View.VISIBLE else View.GONE
        binding.fab.visibility = if (on) View.GONE else View.VISIBLE
        if (on) {
            binding.privateEntry.visibility = View.GONE
        } else {
            // Re-apply the search: it is what decides whether the "Private" row shows.
            applyFilter(binding.searchInput.text?.toString().orEmpty())
        }
        binding.title.text =
            if (on) getString(R.string.n_selected, adapter.selectedCount())
            else getString(R.string.app_name)
    }

    /** Empties the search box, drops focus, and hides the keyboard. */
    private fun clearSearch() {
        binding.searchInput.setText("")   // text watcher restores the full list
        binding.searchInput.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    // Pull the whole list down (revealing the lock + text behind it); release to open the decoy folder.
    private var pullStartY = 0f
    private var pulling = false

    private fun setupPullToPrivate() {
        val density = resources.displayMetrics.density
        val maxPull = 170f * density
        val threshold = 110f * density
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        binding.recycler.addOnItemTouchListener(object : androidx.recyclerview.widget.RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pullStartY = e.y
                        pulling = false
                    }
                    MotionEvent.ACTION_MOVE ->
                        if (!adapter.selectionMode && !rv.canScrollVertically(-1) && e.y - pullStartY > slop) {
                            pulling = true
                            return true
                        }
                }
                return false
            }

            override fun onTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: MotionEvent) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val dy = (e.y - pullStartY).coerceAtLeast(0f)
                        binding.recycler.translationY = (dy * 0.5f).coerceAtMost(maxPull)
                    }
                    MotionEvent.ACTION_UP -> {
                        val triggered = binding.recycler.translationY >= threshold
                        binding.recycler.animate().translationY(0f).setDuration(180).start()
                        pulling = false
                        if (triggered) {
                            startActivity(Intent(this@MainActivity, DecoyPrivateActivity::class.java))
                        }
                    }

                    // An aborted touch - the shade stealing the drag, an incoming call
                    // overlay, the app moving to the background - must never open the
                    // decoy folder on its own; it only springs the list back.
                    MotionEvent.ACTION_CANCEL -> {
                        binding.recycler.animate().translationY(0f).setDuration(180).start()
                        pulling = false
                    }
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
        })
    }

    /** Bumped per keystroke so a slow answer cannot reveal the entry after the user moved on. */
    private var revealToken = 0

    private fun applyFilter(query: String) {
        val raw = query.trim()
        // Reveal the hidden "Private" entry only when the typed text is exactly the
        // secret code. This is the sole entrance to the hidden section once a code is set.
        // Normalize to Latin digits so a Persian-keyboard "۱۲۳۴" still matches the code.
        val code = raw.toLatinDigits()
        // The code is always 4..12 digits, so an ordinary (or short) search never
        // pays for the slow key derivation.
        val plausible = code.length in 4..12 && code.all { it.isDigit() }
        // While the PIN screen is in its cooldown, guessing the code here is blocked too.
        val throttled = secure.pinLockRemaining() > 0L
        val token = ++revealToken
        binding.privateEntry.visibility = View.GONE
        if (!throttled && plausible && secure.hasPin()) {
            // Key derivation takes tens of milliseconds: it must not run between
            // keystrokes on the UI thread, so the answer arrives a moment later.
            runIo {
                val matches = secure.checkPin(code)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && token == revealToken &&
                        binding.searchInput.text?.toString()?.trim()?.toLatinDigits() == code
                    ) {
                        binding.privateEntry.visibility = if (matches) View.VISIBLE else View.GONE
                    }
                }
            }
        }

        val q = raw.lowercase()
        if (q.isEmpty()) {
            adapter.submit(allConvos)
            return
        }
        // Filter against precomputed strings (no contact lookups per keystroke).
        adapter.submit(searchIndex.filter { it.second.contains(q) }.map { it.first })
    }

    /**
     * Rebuilds the search index with the names that have been resolved by now.
     *
     * The index is built without the contact names so the list can appear at once,
     * but a search must still find a contact by name. The names arrive in the
     * background, so the index is refreshed with them once they are known.
     */
    private fun rebuildSearchIndex() {
        val names = adapter.resolvedNames()
        if (names.isEmpty()) return
        val q = binding.searchInput.text?.toString()?.trim()?.lowercase().orEmpty()
        val index = allConvos.map { conv ->
            val name = names[conv.address].orEmpty()
            conv to (name + " " + conv.address + " " + conv.snippet).lowercase()
        }
        searchIndex = index
        // A search that is already typed is applied again, so a name that arrived
        // late can still make a row show up.
        if (q.isNotEmpty()) {
            adapter.submit(index.filter { it.second.contains(q) }.map { it.first })
        }
    }

    override fun onResume() {
        super.onResume()
        ensureDefaultSmsApp()
        refresh()
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    private fun ensureDefaultSmsApp() {
        if (askedDefault) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS) && !rm.isRoleHeld(RoleManager.ROLE_SMS)) {
                askedDefault = true
                roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
            }
        } else {
            val current = Telephony.Sms.getDefaultSmsPackage(this)
            if (current != packageName) {
                askedDefault = true
                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                startActivity(intent)
            }
        }
    }

    private fun refresh() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Without the default-SMS role Android refuses READ_SMS, so a plain permission
            // request can never succeed here. A dead blank screen is worse than being asked
            // again: re-open the role offer, but only while nothing has ever loaded and only
            // twice, so a refusal does not turn into a nagging loop.
            if (!everLoaded && reaskRemaining > 0) {
                reaskRemaining--
                askedDefault = false
            }
            return
        }
        // One pass at a time: a delete or an incoming message fires the observer several
        // times in a row, and each extra pass is another full scan of the message store
        // queued on the single worker - which is what kept the list unresponsive for
        // seconds after one action.
        if (!refreshing.compareAndSet(false, true)) {
            refreshAgain = true
            return
        }
        if (!runIo {
            val all = try {
                repo.getConversations()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "conversation list load failed", e)
                refreshing.set(false)
                return@runIo
            }
            everLoaded = true
            // 1) The main list (archived chats excluded) shows immediately.
            //    The list flags of a row are read in ONE call: asking for "pinned"
            //    and "archived" separately hashed the address twice per row, so two
            //    hundred chats paid four hundred hashes before the first row could
            //    appear. The mute flag is not read here: it comes with the
            //    conversation itself, so the row shows it without another hash.
            val flags = HashMap<String, SecureStore.ListFlags>(all.size)
            all.forEach { flags[it.address] = secure.listFlagsOf(it.address) }
            val main = all.filter { flags[it.address]?.archived != true }
                .sortedByDescending { flags[it.address]?.pinned == true }
            // 2) The names are NOT waited for here. Resolving them needs one query
            //    to the contacts provider per row, and doing that before the list is
            //    handed over is what made the screen look empty for seconds on a
            //    cold start. The list goes up with the numbers it has, and the names
            //    arrive in the background (step 4) and patch themselves in.
            //    The index holds the number and the snippet, so a search by number
            //    works right away; the names are added to it when they arrive.
            val index = main.map {
                it to (it.address + " " + it.snippet).lowercase()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) { refreshing.set(false); return@runOnUiThread }
                allConvos = main
                searchIndex = index
                if (binding.searchInput.text.isNullOrEmpty()) adapter.submit(main)
                else applyFilter(binding.searchInput.text?.toString().orEmpty())
            }
            // 3) Archived chats are processed AFTER the main list is on screen, so
            //    startup stays light. They only feed the unread badge + archive view.
            val archived = all.filter { flags[it.address]?.archived == true }
                .sortedByDescending { it.date }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                archivedConvos = archived
                updateArchiveBadge()
            }
            refreshing.set(false)
            if (refreshAgain) {
                refreshAgain = false
                refresh()
            }
        }) refreshing.set(false)
    }

    /** The archive button is always shown; its dot appears only for unread archived chats. */
    private fun updateArchiveBadge() {
        val on = adapter.selectionMode
        binding.archiveButtonWrap.visibility = if (on) View.GONE else View.VISIBLE
        val unread = !on && archivedConvos.any { it.unread }
        binding.archiveBadge.visibility = if (unread) View.VISIBLE else View.GONE
    }
}

package com.privatemsg.app.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.privatemsg.app.sms.SmsStatusReceiver
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.privatemsg.app.R
import com.privatemsg.app.data.Conversation
import com.privatemsg.app.data.ContactsHelper
import com.privatemsg.app.data.HiddenDbHelper
import com.privatemsg.app.data.SecureStore
import com.privatemsg.app.data.SmsRepository
import com.privatemsg.app.databinding.ActivityHiddenBinding

/** The secret section: lists hidden conversations and decoy settings. */
class HiddenActivity : BaseActivity() {
    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun runAsyncOperation(message: String, task: () -> Unit) {
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setCancelable(false)
            .create()
        dialog.show()

        bgExecutor.execute {
            try {
                task()
            } finally {
                runOnUiThread {
                    try { dialog.dismiss() } catch (_: Exception) {}
                    refresh()
                }
            }
        }
    }

    override val leavesToMainOnBackground = true

    private lateinit var binding: ActivityHiddenBinding
    private lateinit var hiddenDb: HiddenDbHelper
    private lateinit var secure: SecureStore
    private lateinit var repo: SmsRepository
    private lateinit var adapter: ConversationAdapter

    private val addLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val address = result.data?.getStringExtra("address")
            if (!address.isNullOrBlank()) {
                secure.addHiddenNumber(address)
                repo.migrateToHidden(address, hiddenDb)
                refresh()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        binding = ActivityHiddenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        hiddenDb = HiddenDbHelper.getInstance(this)
        secure = SecureStore(this)
        repo = SmsRepository(this)

        adapter = ConversationAdapter(
            contacts = ContactsHelper(this),
            onClick = { conv ->
                val i = Intent(this, HiddenConversationActivity::class.java)
                i.putExtra("address", conv.address)
                startActivity(i)
            },
            onLongClick = { conv -> showRowMenu(conv.address) },
            // Show the app-only alias (if the user renamed this hidden number).
            nameOverride = { secure.hiddenAliasFor(it.address) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        ContextCompat.registerReceiver(
            this, refreshReceiver,
            IntentFilter(SmsStatusReceiver.ACTION_HIDDEN_REFRESH),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            refresh()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        // Belt-and-suspenders: sweep any hidden-number messages out of the system
        // store so no trace remains for another SMS app.
        Thread {
            repo.purgeHiddenFromProvider(hiddenDb)
            runOnUiThread { refresh() }
        }.start()
        refresh()
    }

    private fun refresh() {
        // Show every registered hidden number, with its latest hidden message (if any).
        val list = secure.getHiddenNumbers().map { address ->
            val last = hiddenDb.getMessages(address).lastOrNull()
            Conversation(
                threadId = 0,
                address = address,
                snippet = last?.body ?: getString(R.string.no_messages_yet),
                date = last?.date ?: 0L,
                unread = hiddenDb.hasUnread(address)
            )
        }.sortedByDescending { it.date }
        adapter.submit(list)
    }

    private fun showAddDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = getString(R.string.enter_number)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_hidden)
            .setView(container)
            .setPositiveButton(R.string.add) { _, _ ->
                val num = input.text.toString().trim()
                if (num.isNotEmpty()) {
                    secure.addHiddenNumber(num)
                    runAsyncOperation("در حال انتقال پیام‌ها به بخش مخفی...") {
                        repo.migrateToHidden(num, hiddenDb)
                    }
                }
            }
            .setNeutralButton(R.string.from_conversations) { _, _ ->
                val i = Intent(this, ConversationPickerActivity::class.java)
                i.putExtra("title", getString(R.string.add_hidden))
                addLauncher.launch(i)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showChangePinDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_create)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.change_pin)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length >= 4) {
                    secure.setPin(pin)
                    Toast.makeText(this, R.string.pin_changed, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.pin_too_short, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRowMenu(address: String) {
        val items = arrayOf(
            getString(R.string.custom_decoy),
            getString(R.string.unhide)
        )
        showListMenu(items) { which ->
            when (which) {
                0 -> {
                    val i = Intent(this, DecoySettingsActivity::class.java)
                    i.putExtra("address", address)
                    startActivity(i)
                }
                1 -> confirmUnhide(address)
            }
        }
    }

    private fun confirmUnhide(address: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.unhide))
            .setMessage(getString(R.string.unhide_msg))
            .setPositiveButton(getString(R.string.unhide)) { _, _ ->
                secure.removeHiddenNumber(address)
                runAsyncOperation("در حال خروج از مخفی و بازگردانی پیام‌ها...") {
                    repo.restoreFromHidden(address, hiddenDb)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.hidden_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_fingerprint)?.isChecked = secure.fingerprintEnabled
        menu.findItem(R.id.action_decoy_sound)?.isChecked = secure.decoySoundEnabled
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add -> {
                showAddDialog()
                true
            }
            R.id.action_decoy -> {
                startActivity(Intent(this, DecoySettingsActivity::class.java))
                true
            }
            R.id.action_fingerprint -> {
                toggleFingerprint(item)
                true
            }
            R.id.action_decoy_sound -> {
                secure.decoySoundEnabled = !secure.decoySoundEnabled
                item.isChecked = secure.decoySoundEnabled
                true
            }
            R.id.action_change_pin -> {
                showChangePinDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleFingerprint(item: MenuItem) {
        if (!secure.fingerprintEnabled) {
            // Enabling: make sure the device actually has usable biometrics.
            val available = BiometricManager.from(this).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            ) == BiometricManager.BIOMETRIC_SUCCESS
            if (!available) {
                Toast.makeText(this, R.string.fingerprint_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
        }
        secure.fingerprintEnabled = !secure.fingerprintEnabled
        item.isChecked = secure.fingerprintEnabled
    }
}

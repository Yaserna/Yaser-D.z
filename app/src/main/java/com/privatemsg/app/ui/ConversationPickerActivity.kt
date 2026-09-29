package com.privatemsg.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.privatemsg.app.data.ContactsHelper
import com.privatemsg.app.data.Conversation
import com.privatemsg.app.data.SmsRepository
import com.privatemsg.app.databinding.ActivityPickerBinding

/**
 * Lets the user pick one of their existing conversations and returns its address.
 * Only opened from the private/decoy screens, so it hides itself from screenshots
 * and from the recents thumbnail like the rest of that section.
 */
class ConversationPickerActivity : BaseActivity() {

    override val leavesToMainOnBackground = true

    private lateinit var binding: ActivityPickerBinding
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        intent.getStringExtra("title")?.let { binding.toolbar.title = it }

        val adapter = ConversationAdapter(
            contacts = ContactsHelper(this),
            onClick = { conv ->
                setResult(Activity.RESULT_OK, Intent().putExtra("address", conv.address))
                finish()
            }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        // Reading the system store is slow; show the list as soon as it arrives.
        val repo = SmsRepository(this)
        try {
            ioExecutor.execute {
                val rows: List<Conversation> = try {
                    repo.getConversations()
                } catch (e: Exception) {
                    android.util.Log.e("ConversationPicker", "load failed", e)
                    emptyList()
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) adapter.submit(rows)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
    }
}

package com.privatemsg.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.privatemsg.app.R
import com.privatemsg.app.data.ContactsHelper
import com.privatemsg.app.data.Conversation
import com.privatemsg.app.data.SecureStore
import com.privatemsg.app.data.SmsRepository
import com.privatemsg.app.databinding.ActivityArchiveBinding

/** The archive: shows conversations the user moved out of the main list. */
class ArchiveActivity : BaseActivity() {

    private lateinit var binding: ActivityArchiveBinding
    private lateinit var repo: SmsRepository
    private lateinit var secure: SecureStore
    private lateinit var adapter: ConversationAdapter
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArchiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        repo = SmsRepository(this)
        secure = SecureStore(this)
        adapter = ConversationAdapter(
            contacts = ContactsHelper(this),
            onClick = { conv ->
                val i = Intent(this, ConversationActivity::class.java)
                i.putExtra("thread_id", conv.threadId)
                i.putExtra("address", conv.address)
                startActivity(i)
            },
            onLongClick = { conv -> showRowMenu(conv) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        runIo {
            try {
                val archived = repo.getConversations()
                    .filter { secure.isArchived(it.address) }
                    .sortedByDescending { it.date }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    adapter.submit(archived)
                    binding.empty.visibility = if (archived.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                android.util.Log.e("ArchiveActivity", "archive load failed", e)
            }
        }
    }

    /** Queues [task] on the worker thread; a task after teardown is dropped. */
    private fun runIo(task: () -> Unit) {
        try {
            ioExecutor.execute(task)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
    }

    private fun showRowMenu(conv: Conversation) {
        showListMenu(arrayOf(getString(R.string.unarchive))) { which ->
            if (which == 0) {
                secure.setArchived(conv.address, false)
                refresh()
            }
        }
    }
}

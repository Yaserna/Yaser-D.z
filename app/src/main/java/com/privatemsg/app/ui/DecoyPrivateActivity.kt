package com.privatemsg.app.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.privatemsg.app.R
import com.privatemsg.app.data.ContactsHelper
import com.privatemsg.app.databinding.ActivityMainBinding

/**
 * Decoy "private folder" reached by pulling the conversation list down (mirrors
 * Mi Message's gesture). Looks like the main screen but is titled "Private
 * messages" and is intentionally empty. The real hidden section is elsewhere.
 */
class DecoyPrivateActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This screen pretends to hold private messages, so it must refuse screenshots
        // and the recent-apps preview exactly like every other screen that does.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.title.text = getString(R.string.private_folder_title)
        binding.favoritesButton.visibility = View.GONE
        binding.archiveButtonWrap.visibility = View.GONE

        val adapter = ConversationAdapter(
            contacts = ContactsHelper(this),
            onClick = {},
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        adapter.submit(emptyList())

        binding.fab.setOnClickListener {
            startActivity(android.content.Intent(this, ConversationActivity::class.java))
        }
        binding.settingsButton.visibility = View.GONE
    }
}

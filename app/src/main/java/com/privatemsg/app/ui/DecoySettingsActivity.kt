package com.privatemsg.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.privatemsg.app.R
import com.privatemsg.app.data.ContactsHelper
import com.privatemsg.app.data.SecureStore
import com.privatemsg.app.databinding.ActivityDecoyBinding

/** Edit the global decoy notification: fake name, fake text, and which existing
 *  conversation opens when the decoy notification is tapped. */
class DecoySettingsActivity : BaseActivity() {

    override val leavesToMainOnBackground = true

    private lateinit var binding: ActivityDecoyBinding
    private lateinit var secure: SecureStore
    private var pickedTarget: String = ""

    /** If set, we edit the decoy for this specific hidden number; otherwise the global one. */
    private var perNumber: String? = null

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val address = result.data?.getStringExtra("address")
            if (!address.isNullOrBlank()) {
                pickedTarget = address
                updateTargetLabel()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        binding = ActivityDecoyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        secure = SecureStore(this)
        perNumber = intent.getStringExtra("address")?.takeIf { it.isNotBlank() }

        val pn = perNumber
        if (pn == null) {
            binding.decoyName.setText(secure.decoyName)
            binding.decoyText.setText(secure.decoyText)
            pickedTarget = secure.decoyTarget
        } else {
            supportActionBar?.title = getString(R.string.decoy_title_for, pn)
            // Same reasoning as updateTargetLabel: the contact name is a provider query,
            // so the number is shown now and the name follows from the worker.
            val app = applicationContext
            runOffUi {
                val name = ContactsHelper(app).displayFor(pn)
                runOnUiThread {
                    if (isFinishing || isDestroyed || name == pn) return@runOnUiThread
                    supportActionBar?.title = getString(R.string.decoy_title_for, name)
                }
            }
            binding.decoyName.setText(secure.decoyNameFor(pn))
            binding.decoyText.setText(secure.decoyTextFor(pn))
            pickedTarget = secure.decoyTargetFor(pn)
        }
        updateTargetLabel()

        binding.chooseTarget.setOnClickListener {
            val i = Intent(this, ConversationPickerActivity::class.java)
            i.putExtra("title", getString(R.string.choose_conversation))
            pickLauncher.launch(i)
        }

        binding.save.setOnClickListener {
            val name = binding.decoyName.text.toString().trim()
            val text = binding.decoyText.text.toString().trim()
            val pn2 = perNumber
            if (pn2 == null) {
                secure.decoyName = name
                secure.decoyText = text
                secure.decoyTarget = pickedTarget
            } else {
                secure.setDecoyFor(pn2, name, text, pickedTarget)
            }
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateTargetLabel() {
        val target = pickedTarget
        if (target.isBlank()) {
            binding.decoyTargetValue.text = getString(R.string.not_set)
            return
        }
        // The label shows the saved contact name for the picked conversation, and that
        // name is a contacts-provider query. Show the number at once and let the name
        // replace it from the worker a moment later.
        binding.decoyTargetValue.text = target
        val app = applicationContext
        runOffUi {
            val display = ContactsHelper(app).displayFor(target)
            runOnUiThread {
                if (isFinishing || isDestroyed || pickedTarget != target) return@runOnUiThread
                binding.decoyTargetValue.text = display
            }
        }
    }
}

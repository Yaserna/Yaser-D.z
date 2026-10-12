package com.privatemsg.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.privatemsg.app.R
import com.privatemsg.app.data.SecureStore
import com.privatemsg.app.databinding.ActivityPinBinding

/** Lock-screen style PIN keypad. Sets a PIN on first use, otherwise asks for it. */
class PinActivity : BaseActivity() {

    private lateinit var binding: ActivityPinBinding
    private lateinit var secure: SecureStore
    private var settingUp = false
    private var promptedFingerprint = false
    private val entered = StringBuilder()
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Bumped per check so a slow answer cannot act after the user moved on. */
    private var checkToken = 0

    /** Set once the hidden section has been launched, so no screen opens twice. */
    private var opened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        binding = ActivityPinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        secure = SecureStore(this)
        settingUp = !secure.hasPin()
        binding.title.setText(if (settingUp) R.string.pin_create else R.string.pin_enter)

        val digits = mapOf(
            binding.key0 to '0', binding.key1 to '1', binding.key2 to '2',
            binding.key3 to '3', binding.key4 to '4', binding.key5 to '5',
            binding.key6 to '6', binding.key7 to '7', binding.key8 to '8',
            binding.key9 to '9'
        )
        digits.forEach { (view, d) -> view.setOnClickListener { append(d) } }
        binding.keyBack.setOnClickListener {
            if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
            updateDots()
        }
        binding.keyOk.setOnClickListener { submit() }
        showLockout()

        // Offer fingerprint unlock (when enabled and we're not creating a new PIN).
        if (!settingUp && secure.fingerprintEnabled && !promptedFingerprint) {
            promptedFingerprint = true
            tryFingerprint()
        }
    }

    private var lockoutTick: Runnable? = null

    private fun showLockout() {
        val left = secure.pinLockRemaining()
        lockoutTick?.let { binding.root.removeCallbacks(it) }
        lockoutTick = null
        if (left == 0L) {
            binding.error.text = ""
            return
        }
        binding.error.text = getString(R.string.pin_locked, ((left + 999) / 1000).toInt())
        val tick = Runnable { if (!isFinishing && !isDestroyed) showLockout() }
        lockoutTick = tick
        binding.root.postDelayed(tick, 1000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        // The countdown re-posts itself; drop the pending tick with the screen.
        lockoutTick?.let { binding.root.removeCallbacks(it) }
        lockoutTick = null
        ioExecutor.shutdown()
    }

    private fun tryFingerprint() {
        val canUse = BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
        if (!canUse) return

        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    openHidden()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.fingerprint_title))
            .setNegativeButtonText(getString(R.string.fingerprint_use_pin))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }

    private fun append(d: Char) {
        // Guessing is rate limited: while the cooldown runs the keypad does nothing.
        if (secure.pinLockRemaining() > 0L) {
            showLockout()
            return
        }
        if (entered.length < 12) entered.append(d)
        binding.error.text = ""
        updateDots()
        // When unlocking, enter as soon as the correct PIN is typed (no confirm tap).
        if (!settingUp) verify(entered.toString(), reportFailure = false)
    }

    /**
     * Checks [pin] on the worker thread. The key derivation deliberately takes tens of
     * milliseconds to slow guessing down, which is far too long to spend between two
     * keystrokes on the screen thread. A result that is no longer about the text currently
     * typed is dropped, and only the confirm tap may report a wrong PIN, so a half-typed
     * prefix never burns an attempt.
     */
    private fun verify(pin: String, reportFailure: Boolean) {
        val token = ++checkToken
        try {
            ioExecutor.execute {
                val matches = secure.checkPin(pin)
                runOnUiThread {
                    if (isFinishing || isDestroyed || token != checkToken) return@runOnUiThread
                    if (matches) {
                        openHidden()
                    } else if (reportFailure) {
                        binding.error.setText(R.string.pin_wrong)
                        entered.clear()
                        updateDots()
                        secure.recordPinFailure()
                        showLockout()
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Screen already torn down.
        }
    }

    private fun updateDots() {
        binding.dots.text = "●".repeat(entered.length)
    }

    private fun submit() {
        if (secure.pinLockRemaining() > 0L) {
            showLockout()
            return
        }
        val pin = entered.toString()
        if (settingUp) {
            if (pin.length < 4) {
                binding.error.setText(R.string.pin_too_short)
                return
            }
            val token = ++checkToken
            try {
                ioExecutor.execute {
                    secure.setPin(pin)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && token == checkToken) openHidden()
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
            }
        } else {
            verify(pin, reportFailure = true)
        }
    }

    private fun openHidden() {
        if (opened) return
        opened = true
        // A successful unlock re-opens the hidden section.
        secure.clearPinFailures()
        BaseActivity.hiddenLocked = false
        startActivity(Intent(this, HiddenActivity::class.java))
        finish()
    }
}

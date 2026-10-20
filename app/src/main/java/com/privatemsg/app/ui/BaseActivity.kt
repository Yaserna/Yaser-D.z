package com.privatemsg.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.app.Dialog
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.privatemsg.app.R
import com.privatemsg.app.data.ContactsHelper
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Forces the whole app to Persian + right-to-left layout, regardless of the
 * phone's system language. Also provides the shared "tap a number" menu.
 */
abstract class BaseActivity : AppCompatActivity() {

    private var appliedFontScale = 1f
    private var appliedUiScale = 1f
    private var appliedTheme = 2

    /** One worker per screen for the short off-UI-thread lookups this class does. */
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Every menu this screen showed and that is still open; dismissed with the window. */
    private val openDialogs: MutableList<Dialog> = Collections.synchronizedList(mutableListOf())

    /**
     * Adds a dialog built outside [showListMenu] to the list dismissed in onDestroy, so
     * a confirmation that is still up when the screen dies cannot leak its window.
     */
    protected fun trackDialog(dialog: Dialog) {
        openDialogs.add(dialog)
        dialog.setOnDismissListener { openDialogs.remove(dialog) }
    }

    /**
     * Runs [task] on this screen's worker, stopped with the screen. Anything that reads
     * SQLite, the telephony binder, or a content provider has to leave the UI thread,
     * and each screen used to hand-roll its own executor to do that. The task is
     * responsible for checking that the screen is still alive before touching views.
     */
    protected fun runOffUi(task: () -> Unit) {
        try {
            io.execute(task)
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
        // Clamped exactly like SecureStore's getters: this runs before any of them, and
        // an out-of-range scale would build an unusable configuration for every screen.
        appliedFontScale = prefs.getFloat("font_scale", 1f).coerceIn(0.5f, 2f)
        appliedUiScale = prefs.getFloat("ui_scale", 1f).coerceIn(0.5f, 2f)
        appliedTheme = prefs.getInt("theme_mode", 2).coerceIn(0, 2)

        val locale = Locale("fa")
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        // User display-size preferences: text-only scale + whole-UI (DPI-like) scale.
        config.fontScale = config.fontScale * appliedFontScale
        config.densityDpi = (config.densityDpi * appliedUiScale).toInt()
        // Light/dark theme: force the night bit so values-night resources resolve.
        val night = when (appliedTheme) {
            1 -> false // light
            2 -> true  // dark
            else -> (newBase.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onResume() {
        super.onResume()
        // If the hidden section was locked (app went to background), any hidden
        // screen that somehow survived must close itself immediately on return,
        // so the back button can never re-enter it.
        if (leavesToMainOnBackground && hiddenLocked) {
            finish()
            return
        }
        // Keep the status-bar icons legible against the current theme background.
        val night = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        // If the display size or theme changed elsewhere, rebuild this screen to apply it.
        val prefs = getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
        // Compare against the same clamped values attachBaseContext applied, or an
        // out-of-range preference would ask for a recreate on every resume, forever.
        if (prefs.getFloat("font_scale", 1f).coerceIn(0.5f, 2f) != appliedFontScale ||
            prefs.getFloat("ui_scale", 1f).coerceIn(0.5f, 2f) != appliedUiScale ||
            prefs.getInt("theme_mode", 2).coerceIn(0, 2) != appliedTheme
        ) {
            recreate()
        }
    }

    /** Hidden-section screens override this so they auto-close when the app is backgrounded. */
    protected open val leavesToMainOnBackground: Boolean get() = false

    override fun onStart() {
        super.onStart()
        // Count how many hidden-section screens are currently on screen.
        if (leavesToMainOnBackground) startedHiddenCount++
    }

    override fun onStop() {
        super.onStop()
        if (!leavesToMainOnBackground) return
        // Clamped at zero: a screen can be stopped without a matching start (a dialog
        // taking the foreground, the system killing the window), and a negative counter
        // would keep the section unlocked one stop too many.
        startedHiddenCount = (startedHiddenCount - 1).coerceAtLeast(0)
        // A configuration change (rotation / display-size) recreates the SAME screen —
        // that isn't leaving the hidden section, so don't lock.
        if (isChangingConfigurations) return
        // Lock the moment no hidden screen is on top anymore. This covers BOTH:
        //  - the app going to the background (home / app switch / screen lock), and
        //  - navigating to a NON-hidden screen inside the app (e.g. tapping a normal
        //    message's notification opens that conversation).
        // Any hidden screen still sitting in the back stack will finish itself in
        // onResume because hiddenLocked is now true — so Back can never re-enter it.
        window.decorView.post {
            if (!isFinishing && startedHiddenCount <= 0) {
                hiddenLocked = true
                finish()
            }
        }
    }

    companion object {
        /**
         * True once the app left the hidden section (backgrounded, or moved to a
         * non-hidden screen). Cleared only after a successful PIN/fingerprint unlock
         * (PinActivity), so a stray hidden activity can never be reached again with Back.
         * It starts LOCKED: Android restores a task's screens after its process was
         * killed, and a restored hidden screen must demand the code again rather than
         * rendering its messages on top of the lock screen.
         */
        @Volatile
        var hiddenLocked: Boolean = true

        /** Number of hidden-section screens currently started (visible). */
        @Volatile
        private var startedHiddenCount: Int = 0
    }

    /** Menu shown when a number (phone / card / OTP) inside a message is tapped. */
    fun showNumberMenu(raw: String) {
        val digits = raw.filter { it.isDigit() || it == '+' }
        val options = arrayOf(
            getString(R.string.copy),
            getString(R.string.call),
            getString(R.string.add_contact),
            getString(R.string.send_message)
        )
        // The menu opens at once with no title; the contact name is a provider query and
        // lands on the title a moment later, while the user is still choosing.
        val dialog = showListMenu(options) { which ->
            when (which) {
                0 -> {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("number", raw))
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
                }
                1 -> startSafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
                2 -> startSafely(Intent(Intent.ACTION_INSERT).apply {
                    type = ContactsContract.Contacts.CONTENT_TYPE
                    putExtra(ContactsContract.Intents.Insert.PHONE, digits)
                })
                3 -> {
                    val intent = Intent(this, ConversationActivity::class.java)
                        .putExtra("address", digits)
                    // An alphanumeric sender has no thread; leaving the extra out makes
                    // the chat resolve it on its own worker instead of throwing here.
                    val thread = try {
                        Telephony.Threads.getOrCreateThreadId(this, digits)
                    } catch (e: Exception) {
                        android.util.Log.e("BaseActivity", "no thread for this number", e)
                        -1L
                    }
                    if (thread > 0) intent.putExtra("thread_id", thread)
                    startSafely(intent)
                }
            }
        }
        val app = applicationContext
        io.execute {
            val name = runCatching { ContactsHelper(app).nameFor(digits) }.getOrNull()
            mainHandler.post {
                if (!name.isNullOrEmpty() && dialog.isShowing) dialog.setTitle(name)
            }
        }
    }

    /**
     * Shows a simple list menu that is always centered and a fixed width, so it
     * can't drift off the screen edge (a problem with wrap-content dialogs in RTL).
     * The dialog is tracked and dismissed with the screen: a window left showing when
     * the activity dies is a leaked window (and a crash on some ROMs).
     */
    fun showListMenu(items: Array<String>, title: String? = null, onItem: (Int) -> Unit): Dialog {
        val builder = MaterialAlertDialogBuilder(this).setItems(items) { _, which -> onItem(which) }
        if (!title.isNullOrBlank()) builder.setTitle(title)
        val dialog = builder.create()
        dialog.show()
        dialog.window?.let { w ->
            w.setGravity(android.view.Gravity.CENTER)
            val width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            w.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        openDialogs.add(dialog)
        dialog.setOnDismissListener { openDialogs.remove(dialog) }
        return dialog
    }

    override fun onDestroy() {
        // Close any menu still open, then stop the worker (whose UI callbacks check
        // isDestroyed, so nothing touches a dead window).
        openDialogs.toList().forEach { d -> try { d.dismiss() } catch (_: Exception) {} }
        openDialogs.clear()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // No app to handle it; ignore.
        }
    }
}

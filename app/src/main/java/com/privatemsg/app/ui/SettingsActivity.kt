package com.privatemsg.app.ui

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.privatemsg.app.R
import com.privatemsg.app.data.SecureStore
import com.privatemsg.app.databinding.ActivitySettingsBinding
import kotlin.math.abs

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    // Discrete display-size steps (kept moderate so layouts don't break).
    private val scaleValues = floatArrayOf(0.85f, 1.0f, 1.15f, 1.3f)
    private val scaleLabels by lazy {
        arrayOf(
            getString(R.string.size_small),
            getString(R.string.size_normal),
            getString(R.string.size_large),
            getString(R.string.size_xlarge)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Visible version so an update can be confirmed at a glance.
        binding.versionText.text =
            getString(R.string.app_version, com.privatemsg.app.BuildConfig.VERSION_NAME)

        val secure = SecureStore(this)
        buildDeliveryRows(secure)

        binding.textSizeValue.text = scaleLabels[nearestIndex(secure.fontScale)]
        binding.textSizeRow.setOnClickListener {
            pickScale(R.string.text_size, secure.fontScale) { secure.fontScale = it }
        }

        binding.uiSizeValue.text = scaleLabels[nearestIndex(secure.uiScale)]
        binding.uiSizeRow.setOnClickListener {
            pickScale(R.string.ui_size, secure.uiScale) { secure.uiScale = it }
        }

        // Bubble colors.
        setSwatch(binding.sentColorSwatch, secure.sentBubbleColor)
        binding.sentColorRow.setOnClickListener {
            pickColor(secure.sentBubbleColor) { c ->
                secure.sentBubbleColor = c
                setSwatch(binding.sentColorSwatch, c)
            }
        }
        setSwatch(binding.receivedColorSwatch, secure.receivedBubbleColor)
        binding.receivedColorRow.setOnClickListener {
            pickColor(secure.receivedBubbleColor) { c ->
                secure.receivedBubbleColor = c
                setSwatch(binding.receivedColorSwatch, c)
            }
        }

        // Theme (light / dark / system).
        binding.themeValue.text = themeLabels[secure.themeMode]
        binding.themeRow.setOnClickListener { pickTheme(secure) }

        // App icon.
        binding.appIconPreview.setImageResource(iconPreviewFor(secure.appIcon))
        binding.appIconRow.setOnClickListener { pickAppIcon(secure) }

        // The "About" details stay collapsed until the header is tapped.
        binding.aboutRow.setOnClickListener {
            val open = binding.aboutDetails.visibility != android.view.View.VISIBLE
            binding.aboutDetails.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            binding.aboutArrow.text = if (open) "▾" else "▸"
        }

        // Contact info: tap to dial / email. The values are built at runtime so the
        // packaged resources stay free of the readable number and address.
        binding.contactPhoneValue.text = com.privatemsg.app.data.SupportContact.phone
        binding.contactEmailValue.text = com.privatemsg.app.data.SupportContact.email
        binding.phoneRow.setOnClickListener {
            startSafely(
                android.content.Intent(
                    android.content.Intent.ACTION_DIAL,
                    android.net.Uri.parse("tel:" + com.privatemsg.app.data.SupportContact.phone)
                )
            )
        }
        binding.emailRow.setOnClickListener {
            startSafely(
                android.content.Intent(
                    android.content.Intent.ACTION_SENDTO,
                    android.net.Uri.parse("mailto:" + com.privatemsg.app.data.SupportContact.email)
                )
            )
        }
    }

    /** Builds one delivery-report toggle per SIM (or a single one when there's one SIM). */
    private fun buildDeliveryRows(secure: SecureStore) {
        // Asking the telephony service for the SIM list is a binder call, and doing it in
        // onCreate would hold up the first frame of this screen. The rows land a moment
        // later; everything below runs on the UI thread again because it builds views.
        val app = applicationContext
        runOffUi {
            val sims = com.privatemsg.app.data.SimHelper(app).sims()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.deliveryContainer.removeAllViews()
                if (sims.size <= 1) {
                    val subId = sims.firstOrNull()?.subId ?: -1
                    addDeliveryRow(getString(R.string.delivery_report), subId, secure)
                } else {
                    for (s in sims) addDeliveryRow(getString(R.string.delivery_report_sim, s.slot), s.subId, secure)
                }
            }
        }
    }

    private fun addDeliveryRow(label: String, subId: Int, secure: SecureStore) {
        val d = resources.displayMetrics.density
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
        }
        val tv = android.widget.TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.textPrimary))
            textSize = 16f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = secure.deliveryReportForSub(subId)
            setOnCheckedChangeListener { _, c -> secure.setDeliveryReportForSub(subId, c) }
        }
        row.addView(tv)
        row.addView(sw)
        binding.deliveryContainer.addView(
            row,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private val themeLabels by lazy {
        // Indexed by SecureStore.THEME_SYSTEM/LIGHT/DARK (0/1/2).
        arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
    }

    private fun pickTheme(secure: SecureStore) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.theme_label)
            .setSingleChoiceItems(themeLabels, secure.themeMode) { d, which ->
                d.dismiss()
                if (which != secure.themeMode) {
                    secure.themeMode = which
                    // Rebuild so the new theme applies immediately across the app.
                    recreate()
                }
            }
            .show()
        trackDialog(dialog)
    }

    private fun iconPreviewFor(alias: String): Int =
        AppIcons.options.firstOrNull { it.first == alias }?.second
            ?: com.privatemsg.app.R.drawable.ic_launcher

    /** Grid of the available launcher icons; picking one switches the app icon. */
    private fun pickAppIcon(secure: SecureStore) {
        val density = resources.displayMetrics.density
        val cell = (72 * density).toInt()
        val icon = (52 * density).toInt()
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 3
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_icon_label)
            .setView(grid)
            .create()
        for ((alias, drawable, _) in AppIcons.options) {
            val iv = android.widget.ImageView(this).apply {
                setImageResource(drawable)
                setPadding(4, 4, 4, 4)
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = icon
                    height = icon
                    setMargins((cell - icon) / 2, (cell - icon) / 2, (cell - icon) / 2, (cell - icon) / 2)
                }
                setOnClickListener {
                    secure.appIcon = alias
                    binding.appIconPreview.setImageResource(drawable)
                    AppIcons.apply(this@SettingsActivity, alias)
                    android.widget.Toast.makeText(
                        this@SettingsActivity, R.string.app_icon_changed, android.widget.Toast.LENGTH_LONG
                    ).show()
                    dialog.dismiss()
                }
            }
            grid.addView(iv)
        }
        dialog.show()
        trackDialog(dialog)
    }

    private fun startSafely(intent: android.content.Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // No app to handle it; ignore.
        }
    }

    /** A palette of preset bubble colors offered in the picker. */
    private val palette = intArrayOf(
        0xFF1FA055.toInt(), // green (default sent)
        0xFF1A73E8.toInt(), // blue
        0xFFF7A623.toInt(), // orange
        0xFF8E44AD.toInt(), // purple
        0xFFE53935.toInt(), // red
        0xFF009688.toInt(), // teal
        0xFFE91E63.toInt(), // pink
        0xFF795548.toInt(), // brown
        0xFF455A64.toInt(), // blue-grey
        0xFF2C2C2E.toInt(), // grey (default received)
        0xFF3A3A3C.toInt(), // light grey
        0xFF000000.toInt()  // black
    )

    private fun setSwatch(view: android.view.View, color: Int) {
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.OVAL
        d.setColor(color)
        d.setStroke((1 * resources.displayMetrics.density).toInt(), 0xFF666666.toInt())
        view.background = d
    }

    /** Shows a grid of color circles; calls [onPick] with the chosen color. */
    private fun pickColor(current: Int, onPick: (Int) -> Unit) {
        val density = resources.displayMetrics.density
        val cell = (56 * density).toInt()
        val dot = (40 * density).toInt()
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 4
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_color)
            .setView(grid)
            .create()
        for (color in palette) {
            val circle = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
                if (color == current) setStroke((3 * density).toInt(), 0xFFFFFFFF.toInt())
                else setStroke((1 * density).toInt(), 0xFF666666.toInt())
            }
            val v = android.view.View(this).apply {
                background = circle
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = dot
                    height = dot
                    setMargins((cell - dot) / 2, (cell - dot) / 2, (cell - dot) / 2, (cell - dot) / 2)
                }
                setOnClickListener {
                    onPick(color)
                    dialog.dismiss()
                }
            }
            grid.addView(v)
        }
        dialog.show()
        trackDialog(dialog)
    }

    private fun nearestIndex(value: Float): Int {
        var best = 0
        var bestDiff = Float.MAX_VALUE
        scaleValues.forEachIndexed { i, s ->
            val d = abs(s - value)
            if (d < bestDiff) { bestDiff = d; best = i }
        }
        return best
    }

    private fun pickScale(titleRes: Int, current: Float, onPick: (Float) -> Unit) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(scaleLabels, nearestIndex(current)) { d, which ->
                onPick(scaleValues[which])
                d.dismiss()
                // Rebuild so the new size applies immediately across the app.
                recreate()
            }
            .show()
        trackDialog(dialog)
    }
}

package com.privatemsg.app.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.View
import android.widget.TextView
import java.util.regex.Pattern

/** Makes URLs clickable (open browser) and number runs clickable (custom menu). */
object Linkifier {

    // A run of digits (with optional + and spaces); filtered further below.
    // UNICODE_CHARACTER_CLASS so Persian and Arabic-Indic digits count too.
    private val NUMBER: Pattern =
        Pattern.compile("\\+?\\d[\\d ]{2,}\\d", Pattern.UNICODE_CHARACTER_CLASS)

    fun apply(tv: TextView, body: String, onNumber: (String) -> Unit) {
        val sp = SpannableString(body)
        Linkify.addLinks(sp, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)

        val urlSpans = sp.getSpans(0, sp.length, URLSpan::class.java)
        fun overlapsUrl(s: Int, e: Int): Boolean = urlSpans.any {
            val us = sp.getSpanStart(it)
            val ue = sp.getSpanEnd(it)
            s < ue && us < e
        }

        val m = NUMBER.matcher(body)
        while (m.find()) {
            val s = m.start()
            val e = m.end()
            if (overlapsUrl(s, e)) continue
            val raw = body.substring(s, e)
            val digits = raw.filter { it.isDigit() }
            // Need at least 4 digits to be a meaningful number.
            if (digits.length < 4) continue
            // E.164 caps a real number at 15 digits; longer means the greedy match
            // swallowed several separate runs of prose.
            if (digits.length > 15) continue
            // Only the shapes phone numbers actually come in may be space-split:
            // "+98 21 ..." or "0912 345 ...". "12 34 56" is prose, not a number.
            val firstDigit = Character.digit(digits[0], 10)
            if (firstDigit < 0) continue
            if (raw.contains(' ') && !raw.startsWith("+") && firstDigit != 0) continue
            // Skip times/dates/ranges: matches sitting next to ':' '/' '-'.
            val before = if (s > 0) body[s - 1] else ' '
            val after = if (e < body.length) body[e] else ' '
            if (before == ':' || before == '/' || before == '-' ||
                after == ':' || after == '/' || after == '-'
            ) continue
            // Hand the dialer ASCII digits: a tel: URI built from Persian digits is
            // not parsed by every dialer.
            val number = (if (raw.startsWith("+")) "+" else "") +
                digits.map { Character.forDigit(Character.digit(it, 10), 10).toChar() }
                    .joinToString("")
            sp.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = onNumber(number)
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = 0xFF4DA3FF.toInt()
                    ds.isUnderlineText = false
                }
            }, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        tv.text = sp
        tv.movementMethod = LinkMovementMethod.getInstance()
        tv.linksClickable = true
    }
}

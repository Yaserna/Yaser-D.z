package com.privatemsg.app.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.View
import android.widget.TextView

/** Makes URLs clickable (open browser) and number runs clickable (custom menu). */
object Linkifier {

    fun apply(tv: TextView, body: String, onNumber: (String) -> Unit) {
        val sp = SpannableString(body)
        Linkify.addLinks(sp, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)

        val urlSpans = sp.getSpans(0, sp.length, URLSpan::class.java)
        fun overlapsUrl(s: Int, e: Int): Boolean = urlSpans.any {
            val us = sp.getSpanStart(it)
            val ue = sp.getSpanEnd(it)
            s < ue && us < e
        }

        for (hit in NumberRules.find(body)) {
            val s = hit.start
            val e = hit.end
            if (overlapsUrl(s, e)) continue
            sp.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = onNumber(hit.dialable)
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

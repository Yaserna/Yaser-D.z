package com.privatemsg.app.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.privatemsg.app.R
import com.privatemsg.app.data.Message
import com.privatemsg.app.databinding.ItemMessageBinding

class MessageAdapter(
    private val showSim: Boolean = false,
    private val slotForSub: (Int) -> Int? = { null },
    private val onLongClick: (Message) -> Unit = {},
    private val onNumberClick: (String) -> Unit = {},
    private val onSelectionChanged: () -> Unit = {},
    private val sentColor: Int = com.privatemsg.app.data.SecureStore.DEFAULT_SENT_COLOR,
    private val receivedColor: Int = com.privatemsg.app.data.SecureStore.DEFAULT_RECEIVED_COLOR,
    private val isStarred: (Message) -> Boolean = { false }
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val items = mutableListOf<Message>()
    private val selected = mutableSetOf<Long>()
    var selectionMode = false
        private set

    /**
     * Replaces the list with animated minimal updates.
     *
     * A full notifyDataSetChanged rebuilt every bubble on every delivery report: the
     * whole visible list was re-linkedified and re-measured, which is exactly the work
     * that stutters on a long chat. DiffUtil updates only the rows that changed, and
     * rebinds the row after each removal because that row's day separator depends on
     * its (now different) predecessor.
     */
    fun submit(list: List<Message>) {
        val old = ArrayList(items)
        val newIds = list.map { it.id }.toSet()
        val removedPositions = ArrayList<Int>()
        for (i in old.indices) if (old[i].id !in newIds) removedPositions.add(i)
        val result = androidx.recyclerview.widget.DiffUtil.calculateDiff(
            object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(o: Int, n: Int) = old[o].id == list[n].id
                override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n]
            }
        )
        items.clear()
        items.addAll(list)
        result.dispatchUpdatesTo(this)
        for (p in removedPositions) {
            // The row now sitting where the removed one was (and the one after it) may
            // need a different day separator; index 0 has no predecessor to worry about.
            if (p in 1..items.lastIndex) notifyItemChanged(p)
        }
    }

    fun startSelection(m: Message) {
        selectionMode = true
        selected.clear()
        selected.add(m.id)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun exitSelection() {
        selectionMode = false
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectAll() {
        selected.clear()
        items.forEach { selected.add(it.id) }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun toggle(m: Message) {
        if (!selected.add(m.id)) selected.remove(m.id)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectedMessages(): List<Message> = items.filter { selected.contains(it.id) }
    fun selectedCount(): Int = selected.size

    inner class VH(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        // Anything that isn't an incoming message belongs on the sender's side
        // (sent / outbox / failed / queued) — so a failed send never looks "received".
        val sent = m.type != INBOX
        val failed = m.type == FAILED
        val ctx = holder.itemView.context

        // Day separator (Persian/Jalali date) above the first message of each day.
        val showHeader = position == 0 || !JalaliDate.sameDay(items[position - 1].date, m.date)
        holder.binding.dateHeader.visibility = if (showHeader) View.VISIBLE else View.GONE
        if (showHeader) holder.binding.dateHeader.text = JalaliDate.label(m.date).toLatinDigits()

        val bubbleColor = if (sent) sentColor else receivedColor
        Linkifier.apply(holder.binding.text, m.body.toLatinDigits(), onNumberClick)
        holder.binding.bubble.background = bubbleBackground(ctx, bubbleColor)
        // Keep the message text readable whatever color the bubble is.
        val onBubble = if (isLight(bubbleColor)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        holder.binding.text.setTextColor(onBubble)
        // Sent messages on the right (START in RTL), received on the left (END).
        holder.binding.root.gravity =
            if (sent) Gravity.START else Gravity.END

        val delivered = sent && m.status == 0

        // Time: a muted shade of the readable color, so it stays legible on any bubble.
        // Force Latin (English) digits even though the app locale is Persian.
        val baseTime = android.text.format.DateUtils.formatDateTime(
            ctx, m.date, android.text.format.DateUtils.FORMAT_SHOW_TIME
        ).toLatinDigits()
        val badges = listOfNotNull(
            if (isStarred(m)) "⭐" else null,
            if (m.isEncrypted) "🔒" else null,
            if (m.isFromHidden) "a+" else null
        ).joinToString(" ")
        holder.binding.time.text = if (badges.isEmpty()) baseTime
        else ctx.getString(R.string.time_with_badges, baseTime, badges)
        holder.binding.time.setTextColor(
            if (isLight(bubbleColor)) 0xFF555555.toInt() else 0xFFCFCFCF.toInt()
        )

        // Status ticks for sent messages: red ✕ = failed, blue ✓✓ = delivered, plain ✓ = sent.
        if (sent) {
            holder.binding.ticks.text = when {
                failed -> "✕"
                delivered -> "✓✓"
                else -> "✓"
            }
            holder.binding.ticks.setTextColor(
                when {
                    failed -> 0xFFE53935.toInt()
                    delivered -> 0xFF1A73E8.toInt()
                    else -> onBubble
                }
            )
            holder.binding.ticks.visibility = View.VISIBLE
        } else {
            holder.binding.ticks.visibility = View.GONE
        }

        // Tiny SIM tag inside the bubble (blue SIM-card shape, slot number), shown when 2+ SIMs.
        val slot = if (showSim) slotForSub(m.subId) else null
        if (slot != null) {
            holder.binding.simTag.text = slot.toString()
            holder.binding.simTag.visibility = View.VISIBLE
        } else {
            holder.binding.simTag.visibility = View.GONE
        }

        if (selectionMode) {
            // In selection mode: highlight selected rows; a tap anywhere toggles.
            holder.binding.root.setBackgroundColor(
                if (selected.contains(m.id)) 0x33F7A623 else 0x00000000
            )
            holder.binding.text.movementMethod = null // tap should toggle, not open links
            val toggle = View.OnClickListener { toggle(m) }
            holder.binding.root.setOnClickListener(toggle)
            holder.binding.bubble.setOnClickListener(toggle)
            holder.binding.text.setOnClickListener(toggle)
            val longSelect = View.OnLongClickListener { toggle(m); true }
            holder.binding.root.setOnLongClickListener(longSelect)
            holder.binding.bubble.setOnLongClickListener(longSelect)
            holder.binding.text.setOnLongClickListener(longSelect)
        } else {
            holder.binding.root.setBackgroundColor(0x00000000)
            holder.binding.root.setOnClickListener(null)
            holder.binding.bubble.setOnClickListener(null)
            holder.binding.text.setOnClickListener(null)
            // Long-press triggers the menu anywhere across the row: the bubble, the
            // message text, and the empty space facing the bubble.
            val longPress = View.OnLongClickListener { onLongClick(m); true }
            holder.binding.root.setOnLongClickListener(longPress)
            holder.binding.bubble.setOnLongClickListener(longPress)
            holder.binding.text.setOnLongClickListener(longPress)
        }
    }

    override fun getItemCount() = items.size

    /**
     * A rounded (18dp) solid-color bubble background, built from the chosen color.
     * Cached per color: every visible bubble used to allocate a fresh drawable on each
     * bind, which is garbage the scroll has to pay for over and over.
     */
    private val bubbleCache = HashMap<Int, android.graphics.drawable.GradientDrawable>()

    private fun bubbleBackground(ctx: android.content.Context, color: Int): android.graphics.drawable.GradientDrawable {
        bubbleCache[color]?.let { return it }
        val d = android.graphics.drawable.GradientDrawable()
        d.cornerRadius = 18f * ctx.resources.displayMetrics.density
        d.setColor(color)
        bubbleCache[color] = d
        return d
    }

    /** True if a color is light enough that black text reads better than white. */
    private fun isLight(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        // Perceived luminance (0..255).
        return (0.299 * r + 0.587 * g + 0.114 * b) > 150
    }

    companion object {
        private const val INBOX = 1   // Telephony.Sms.MESSAGE_TYPE_INBOX
        private const val FAILED = 5  // Telephony.Sms.MESSAGE_TYPE_FAILED
    }
}


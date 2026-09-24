package com.privatemsg.app.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.privatemsg.app.R
import com.privatemsg.app.data.Conversation
import com.privatemsg.app.data.ContactsHelper
import com.privatemsg.app.databinding.ItemConversationBinding

class ConversationAdapter(
    private val contacts: ContactsHelper,
    private val onClick: (Conversation) -> Unit,
    private val onLongClick: (Conversation) -> Unit = {},
    private val onSelectionChanged: () -> Unit = {},
    // Optional: override the displayed name (e.g. a hidden-number app-only alias).
    private val nameOverride: ((Conversation) -> String?)? = null
) : RecyclerView.Adapter<ConversationAdapter.VH>() {

        private val items = mutableListOf<Conversation>()
    init {
        stateRestorationPolicy = androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }
    private val selected = mutableSetOf<Long>()
    var selectionMode = false
        private set

    fun submit(list: List<Conversation>) {
        val oldList = java.util.ArrayList(items)
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = list.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldList[oldPos].threadId == list[newPos].threadId
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldList[oldPos] == list[newPos]
        })
        items.clear()
        items.addAll(list)
        diff.dispatchUpdatesTo(this)
    }

    fun startSelection(conv: Conversation) {
        selectionMode = true
        selected.clear()
        selected.add(conv.threadId)
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
        items.forEach { selected.add(it.threadId) }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectedThreadIds(): Set<Long> = selected.toSet()
    fun selectedCount(): Int = selected.size

    private fun toggle(conv: Conversation) {
        if (!selected.add(conv.threadId)) selected.remove(conv.threadId)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    inner class VH(val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val ctx = holder.itemView.context
        val display = nameOverride?.invoke(c) ?: contacts.displayFor(c.address)
        holder.binding.name.text = display
        val timeText =
            if (c.date > 0) DateUtils.getRelativeTimeSpanString(c.date).toString().toLatinDigits() else ""
        // A muted conversation is marked here (there is no separate mute icon in the row).
        holder.binding.time.text = if (c.isMuted) "$timeText 🔕" else timeText

        // Unread conversations: dot, bold name, and a brighter snippet.
        holder.binding.unreadDot.visibility = if (c.unread) View.VISIBLE else View.GONE
        holder.binding.name.setTypeface(
            null, if (c.unread) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )

        if (c.failed) {
            // A message in this conversation failed to send → mark it red.
            val red = 0xFFE53935.toInt()
            holder.binding.name.setTextColor(red)
            holder.binding.snippet.text = ctx.getString(R.string.send_failed)
            holder.binding.snippet.setTextColor(red)
        } else {
            holder.binding.name.setTextColor(ctx.getColor(R.color.textPrimary))
            holder.binding.snippet.text = c.snippet.toLatinDigits()
            holder.binding.snippet.setTextColor(
                ctx.getColor(if (c.unread) R.color.textPrimary else R.color.textSecondary)
            )
        }

        // Prefer the saved contact's photo; otherwise fall back to a letter/icon.
        val photo = contacts.photoUriFor(c.address)
        if (photo != null) {
            try {
                holder.binding.avatarPhoto.setImageURI(android.net.Uri.parse(photo))
            } catch (e: Exception) {
                holder.binding.avatarPhoto.setImageURI(null)
            }
            holder.binding.avatarPhoto.visibility = View.VISIBLE
            holder.binding.avatarLetter.visibility = View.GONE
            holder.binding.avatarIcon.visibility = View.GONE
        } else {
            holder.binding.avatarPhoto.visibility = View.GONE
            val first = display.trim().firstOrNull()
            if (first != null && first.isLetter()) {
                holder.binding.avatarLetter.text = first.uppercaseChar().toString()
                holder.binding.avatarLetter.visibility = View.VISIBLE
                holder.binding.avatarIcon.visibility = View.GONE
            } else {
                holder.binding.avatarLetter.visibility = View.GONE
                holder.binding.avatarIcon.visibility = View.VISIBLE
            }
        }

        if (selectionMode) {
            holder.binding.checkBox.visibility = View.VISIBLE
            holder.binding.checkBox.setImageResource(
                if (selected.contains(c.threadId)) R.drawable.ic_check_on else R.drawable.ic_check_off
            )
        } else {
            holder.binding.checkBox.visibility = View.GONE
        }

        holder.binding.root.setOnClickListener {
            if (selectionMode) toggle(c) else onClick(c)
        }
        holder.binding.root.setOnLongClickListener {
            if (!selectionMode) onLongClick(c)
            true
        }
    }

    override fun getItemCount() = items.size
}

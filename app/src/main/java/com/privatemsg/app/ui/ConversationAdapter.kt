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
        uriByAddress.clear()
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = list.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                // threadId alone is not an identity: the hidden list has no provider
                // thread and every row would otherwise look like the same item, which
                // makes DiffUtil move data between the wrong numbers.
                oldList[oldPos].threadId == list[newPos].threadId &&
                    oldList[oldPos].address == list[newPos].address
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldList[oldPos] == list[newPos]
        })
        items.clear()
        items.addAll(list)
        diff.dispatchUpdatesTo(this)
        // Warm the contact lookups for the rows just added, so the first bind of a row
        // reads a cache instead of asking the contacts provider on the UI thread - both
        // for the name and for the photo.
        avatarIo.execute {
            list.take(40).forEach {
                contacts.displayFor(it.address)
                uriByAddress[it.address] = contacts.photoUriFor(it.address).orEmpty()
            }
        }
    }

    /** Letter/icon avatar for a row that has no contact photo (or has not resolved one yet). */
    private fun showFallback(holder: VH, display: String) {
        holder.wantedAvatar = null
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

    companion object {
        /**
         * Contact photos are decoded off the UI thread and kept for the session:
         * setImageURI on every bind reads through the contacts provider while the list
         * is scrolling, which is the classic source of jank in a conversation list.
         *
         * The worker and the cache are process-wide on purpose. Every list in the app
         * (normal, hidden, archive, decoy) builds its own adapter, and one thread per
         * adapter would keep living after the screen that made it was recreated - a
         * leaked thread plus a cache that starts empty again on every rotation.
         */
        private val avatarIo = java.util.concurrent.Executors.newSingleThreadExecutor()
        private val avatarCache = android.util.LruCache<String, android.graphics.Bitmap>(6 * 1024 * 1024)
    }

    /**
     * Contact-photo uri per address, resolved by the worker ("" = resolved, no photo).
     * It lives for one submitted list, so a photo the user adds later is picked up by the
     * next refresh rather than being cached for the whole session.
     */
    private val uriByAddress = java.util.concurrent.ConcurrentHashMap<String, String>()

    inner class VH(val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root) {
        /** Which contact photo this row asked for, so a late decode cannot paint an old row. */
        var wantedAvatar: String? = null
    }

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
        // The uri itself comes from the contacts provider, so an unknown address is never
        // asked for on this thread: the row paints its letter avatar at once and the photo
        // replaces it when the worker has found it. A cold list used to pay one provider
        // query per visible row, on the UI thread, right when the screen was opening.
        val known = uriByAddress[c.address]
        if (known == null) {
            showFallback(holder, display)
            val forAddress = c.address
            avatarIo.execute {
                uriByAddress[forAddress] = contacts.photoUriFor(forAddress).orEmpty()
                holder.itemView.post {
                    val p = holder.bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION && items.getOrNull(p)?.address == forAddress) {
                        notifyItemChanged(p)
                    }
                }
            }
        } else if (known.isEmpty()) {
            showFallback(holder, display)
        } else {
            holder.binding.avatarPhoto.visibility = View.VISIBLE
            holder.binding.avatarLetter.visibility = View.GONE
            holder.binding.avatarIcon.visibility = View.GONE
            holder.wantedAvatar = known
            val cached = avatarCache.get(known)
            if (cached != null) {
                holder.binding.avatarPhoto.setImageBitmap(cached)
            } else {
                // Nothing yet: show the empty circle rather than the previous row's face.
                holder.binding.avatarPhoto.setImageDrawable(null)
                loadAvatar(holder, known)
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

    /**
     * Starts a background decode for [uri] and paints it only if this row still asks
     * for that same photo (a recycled holder may have moved to another contact by then).
     */
    private fun loadAvatar(holder: VH, uri: String) {
        val ctx = holder.itemView.context.applicationContext
        val targetPx = (48 * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        avatarIo.execute {
            val bmp = runCatching { decodeAvatar(ctx, uri, targetPx) }.getOrNull()
            if (bmp != null) avatarCache.put(uri, bmp)
            holder.itemView.post {
                if (holder.wantedAvatar == uri && bmp != null) {
                    holder.binding.avatarPhoto.setImageBitmap(bmp)
                }
            }
        }
    }

    /** Reads the small thumbnail bytes once, then decodes them at the size actually shown. */
    private fun decodeAvatar(ctx: android.content.Context, uri: String, targetPx: Int): android.graphics.Bitmap? {
        val bytes = ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))
            ?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty()) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
            sample *= 2
        }
        return android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }
}

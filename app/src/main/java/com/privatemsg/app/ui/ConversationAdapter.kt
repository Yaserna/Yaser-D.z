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
    private val nameOverride: ((Conversation) -> String?)? = null,
    // Optional: called on the UI thread once the contact names are known.
    private val onNamesResolved: (() -> Unit)? = null
) : RecyclerView.Adapter<ConversationAdapter.VH>() {

        private val items = mutableListOf<Conversation>()
    init {
        stateRestorationPolicy = androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }
    private val selected = mutableSetOf<Long>()
    var selectionMode = false
        private set

    /**
     * Display name per address, so a bind never has to ask the contacts provider.
     * It is filled by [submit] from the values the caller already resolved.
     */
    private val nameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The addresses that were asked for and have no contact name behind them, with the
     * cache generation of that attempt. They are not asked again until the contacts
     * change, so a list of two hundred unknown numbers does not pay two hundred
     * provider queries on every refresh.
     */
    private val nameMisses = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * The cache generation the names in [nameCache] belong to. A new generation means
     * the contacts changed, so the names we hold may be stale and are dropped.
     */
    @Volatile
    private var nameGeneration: Int = ContactsHelper.cacheGeneration()

    /**
     * The addresses whose photo was already fetched for the current list. The rest
     * are looked up when their row is bound, so a long list does not pay one
     * provider query per row before the user scrolls to it.
     */
    private val photoPrefetched = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun submit(list: List<Conversation>) {
        val oldList = java.util.ArrayList(items)
        uriByAddress.clear()
        // The photo addresses belong to the list that was just replaced, so they are
        // looked up again for the new one.
        photoPrefetched.clear()
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
        // The names are resolved OFF the UI thread and arrive later; the list is
        // never held back waiting for them. A cold start used to query the contacts
        // provider once per row before the first row could be shown, which left the
        // screen empty for seconds.
        warmNames(list)
    }

    /**
     * Resolves the contact names of the rows just submitted and patches them in.
     *
     * The work happens OFF the UI thread and the list is never held back waiting for
     * it: a cold start used to query the contacts provider once per row before the
     * first row could be shown, which left the screen empty for seconds.
     *
     * The names come in two waves: the first rows of the list first, because those
     * are the ones on screen at startup, and the rest once the list has settled and
     * the window that is really visible is known.
     */
    private fun warmNames(list: List<Conversation>) {
        if (list.isEmpty()) return
        val addresses = list.map { it.address }.distinct()
        // Wave 1: the head of the list, which is what the user sees first.
        avatarIo.execute { resolveNames(list, addresses.take(FIRST_WAVE)) }
        // Wave 2: the rest, once the first wave is on screen and the visible window
        // can be asked for.
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.postDelayed({
            val visible = visibleAddresses()
            avatarIo.execute {
                val rest = addresses.drop(FIRST_WAVE)
                resolveNames(list, rest, visible)
            }
        }, FIRST_WAVE_DELAY_MS)
    }

    /**
     * Looks the given names up and hands the ones that changed back to the list.
     *
     * @param visible the addresses that are on screen; they are asked for first.
     */
    private fun resolveNames(
        list: List<Conversation>,
        addresses: List<String>,
        visible: List<String> = emptyList()
    ) {
        if (addresses.isEmpty()) return
        val byAddress = HashMap<String, Conversation>(list.size)
        list.forEach { byAddress[it.address] = it }
        val generation = ContactsHelper.cacheGeneration()
        // The contacts changed: the names we hold may be the old ones, so they are
        // dropped and every row is asked again.
        if (generation != nameGeneration) {
            nameGeneration = generation
            nameCache.clear()
            nameMisses.clear()
        }
        // A number that was already asked for without success is skipped until the
        // contacts change, so a refresh does not repeat the same queries.
        // A list with its own labels (the hidden one) is asked every time: a label
        // can be added or removed at any moment, and that label is the name.
        val pending = addresses
            .filter { nameOverride != null || !nameCache.containsKey(it) }
            .filter { nameOverride != null || nameMisses[it] != generation }
            .sortedBy { if (it in visible) 0 else 1 }
        val resolved = HashSet<String>()
        pending.forEach { address ->
            val conv = byAddress[address] ?: return@forEach
            val name = resolveNameFor(conv)
            if (name == null || name == address) {
                if (nameOverride != null) {
                    // A list with its own labels: there is no contact name behind the
                    // number, so the row shows the number itself. The label it had
                    // may have just been deleted, so the row is redrawn when it
                    // still shows the old one - a label that was removed must not
                    // stay on the screen.
                    if (nameCache.remove(address) != null) resolved.add(address)
                } else {
                    // No contact name behind this number: the row already shows it,
                    // so there is nothing to redraw, and it is not asked again for
                    // now.
                    nameMisses[address] = generation
                }
                return@forEach
            }
            nameCache[address] = name
            resolved.add(address)
        }
        // The photos of the rows that are on screen are fetched here as well, so a
        // bind does not have to ask the provider for one either. The rest is left
        // to the bind of its own row, which is when the user actually reaches it.
        // A list with its own labels (the hidden one) never gets a photo: the face
        // of the contact behind a hidden number would reveal who it is, which is
        // exactly what the hidden list is there to keep.
        if (DisplayNames.showsPhoto(ownLabels = nameOverride != null)) {
            val forPhotos = (visible + addresses.take(FIRST_WAVE))
                .distinct()
                .filter { photoPrefetched.add(it) }
            forPhotos.forEach { uriByAddress[it] = contacts.photoUriFor(it).orEmpty() }
        }
        if (resolved.isEmpty()) return
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            // Only the rows whose name really changed are redrawn, so a list of two
            // hundred rows does not rebind two hundred views for one new name.
            // The position is checked again here: the list may have been replaced
            // between the lookup and this moment, and a stale position would point
            // at a different conversation.
            items.mapIndexedNotNull { index, c ->
                if (c.address in resolved) index else null
            }.forEach { index ->
                if (index < items.size && items[index].address in resolved) {
                    notifyItemChanged(index)
                }
            }
            // A screen that searches by name needs the names it now has.
            onNamesResolved?.invoke()
        }
    }

    /**
     * The name a row shows: the caller's own label first, then the contact name.
     *
     * A list that is built around a private number (a hidden chat) keeps the number
     * itself when it has no label, because the contact behind it must not be
     * revealed by accident. Such a list never asks the contacts provider at all.
     */
    private fun resolveNameFor(c: Conversation): String? {
        nameOverride?.invoke(c)?.let { return it }
        if (nameOverride != null) return null
        return contacts.displayFor(c.address)
    }

    /** The addresses of the rows the user can see right now. */
    private fun visibleAddresses(): List<String> {
        val rv = recycler ?: return emptyList()
        val manager = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return emptyList()
        val first = manager.findFirstVisibleItemPosition()
        val last = manager.findLastVisibleItemPosition()
        if (first < 0 || last < first) return emptyList()
        return (first..last).mapNotNull { items.getOrNull(it)?.address }
    }

    /**
     * The list this adapter is attached to. It is only used to ask which rows are on
     * screen, so the contact names are resolved for the visible ones first.
     */
    var recycler: androidx.recyclerview.widget.RecyclerView? = null
        private set

    /** The names that are known so far, keyed by address. */
    fun resolvedNames(): Map<String, String> = nameCache.toMap()

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
         * How many rows of a fresh list are named straight away. These are the rows
         * the user sees first, and naming them is what the screen was waiting for.
         */
        private const val FIRST_WAVE = 24

        /** How long the rest of the list waits before its names are asked for. */
        private const val FIRST_WAVE_DELAY_MS = 400L

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

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        recycler = null
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val ctx = holder.itemView.context
        // The name comes from the cache only: a provider query here would run on the
        // UI thread once per visible row, which is what made a cold list look empty
        // for a beat even though the data had already been read.
        // A list with its own labels (the hidden one) never uses the contact-name
        // cache, so the name of a hidden contact can not leak into it.
        val override = nameOverride
        val display = if (override != null) {
            // A list with its own labels (the hidden one) never shows a contact
            // name, so the name of a hidden contact can not leak into it.
            DisplayNames.forRow(c.address, override(c), null, hidden = true)
        } else {
            DisplayNames.forRow(c.address, null, nameCache[c.address])
        }
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
        // A list with its own labels (the hidden one) never shows a photo at all: the
        // face behind a hidden number would give away who it is.
        val known = if (DisplayNames.showsPhoto(ownLabels = override != null)) uriByAddress[c.address] else ""
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

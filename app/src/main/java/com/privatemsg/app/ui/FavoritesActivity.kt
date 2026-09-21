package com.privatemsg.app.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.privatemsg.app.R
import com.privatemsg.app.data.ContactsHelper
import com.privatemsg.app.data.Favorite
import com.privatemsg.app.data.StarredDbHelper
import com.privatemsg.app.databinding.ActivityFavoritesBinding
import com.privatemsg.app.databinding.ItemFavoriteBinding

class FavoritesActivity : BaseActivity() {

    private lateinit var binding: ActivityFavoritesBinding
    private lateinit var starredDb: StarredDbHelper
    private lateinit var adapter: FavAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavoritesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        starredDb = StarredDbHelper.getInstance(this)
        adapter = FavAdapter(ContactsHelper(this)) { fav -> confirmDelete(fav) }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val items = starredDb.getAllFavorites()
        adapter.submit(items)
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(fav: Favorite) {
        MaterialAlertDialogBuilder(this)
            .setItems(arrayOf(getString(R.string.delete))) { _, _ ->
                starredDb.unstar(fav.id, isHidden = false)
                refresh()
            }
            .show()
    }

    private class FavAdapter(
        private val contacts: ContactsHelper,
        private val onLongClick: (Favorite) -> Unit
    ) : RecyclerView.Adapter<FavAdapter.VH>() {

        private val items = mutableListOf<Favorite>()

        fun submit(list: List<Favorite>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        inner class VH(val binding: ItemFavoriteBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(ItemFavoriteBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = items[position]
            holder.binding.body.text = f.body
            val date = DateUtils.getRelativeTimeSpanString(f.date).toString()
            holder.binding.meta.text = "${contacts.displayFor(f.address)} · $date"
            holder.binding.root.setOnLongClickListener { onLongClick(f); true }
        }

        override fun getItemCount() = items.size
    }
}

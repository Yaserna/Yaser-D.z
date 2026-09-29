package com.privatemsg.app.ui

import android.content.Context
import android.provider.ContactsContract
import android.widget.ArrayAdapter
import android.widget.Filter

data class ContactItem(val name: String, val number: String) {
    override fun toString(): String = "$name — $number"
}

/** Suggests contacts by name OR number as the user types in the recipient field. */
class ContactSuggestAdapter(context: Context) :
    ArrayAdapter<ContactItem>(context, android.R.layout.simple_dropdown_item_1line) {

    // Reading every contact is an unbounded query: it must never run on the UI thread.
    @Volatile
    private var all: List<ContactItem> = emptyList()

    init {
        val appContext = context.applicationContext
        ioExecutor.execute { all = loadContacts(appContext) }
    }

    companion object {
        private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    private fun loadContacts(context: Context): List<ContactItem> {
        val list = mutableListOf<ContactItem>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    list.add(ContactItem(name, number))
                }
            }
        } catch (e: SecurityException) {
            // READ_CONTACTS not granted yet.
        }
        return list
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val q = constraint?.toString()?.trim()?.lowercase().orEmpty()
            val res = if (q.isEmpty()) emptyList()
            else all.filter { it.name.lowercase().contains(q) || it.number.contains(q) }.take(8)
            return FilterResults().apply { values = res; count = res.size }
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults) {
            clear()
            @Suppress("UNCHECKED_CAST")
            addAll((results.values as? List<ContactItem>) ?: emptyList())
            notifyDataSetChanged()
        }
    }
}

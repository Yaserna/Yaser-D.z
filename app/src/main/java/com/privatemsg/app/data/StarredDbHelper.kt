package com.privatemsg.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentHashMap

class StarredDbHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val systemStarredCache = ConcurrentHashMap.newKeySet<Long>()
    private val hiddenStarredCache = ConcurrentHashMap.newKeySet<Long>()
    private val starredThreadsAddressCache = ConcurrentHashMap.newKeySet<String>()

    init {
        loadCache()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "message_id INTEGER NOT NULL, " +
                "is_hidden INTEGER DEFAULT 0, " +
                "thread_id INTEGER DEFAULT -1, " +
                "address TEXT, " +
                "body TEXT, " +
                "date INTEGER, " +
                "UNIQUE(is_hidden, message_id))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS starred_threads (" +
                "address TEXT PRIMARY KEY, " +
                "thread_id INTEGER DEFAULT -1)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS starred_threads (" +
                    "address TEXT PRIMARY KEY, " +
                    "thread_id INTEGER DEFAULT -1)"
            )
        }
    }

    private fun loadCache() {
        try {
            val db = writableDatabase
            onCreate(db)
            db.query(TABLE, arrayOf("message_id", "is_hidden"), null, null, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val msgId = c.getLong(0)
                    val isHidden = c.getInt(1) == 1
                    if (isHidden) hiddenStarredCache.add(msgId)
                    else systemStarredCache.add(msgId)
                }
            }
            db.query("starred_threads", arrayOf("address"), null, null, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val addr = c.getString(0) ?: ""
                    if (addr.isNotBlank()) starredThreadsAddressCache.add(SecureStore.normalize(addr))
                }
            }
        } catch (_: Exception) {}
    }

    fun isStarred(messageId: Long, isHidden: Boolean): Boolean {
        return if (isHidden) hiddenStarredCache.contains(messageId)
        else systemStarredCache.contains(messageId)
    }

    fun isThreadStarred(address: String): Boolean {
        if (address.isBlank()) return false
        return starredThreadsAddressCache.contains(SecureStore.normalize(address))
    }

    fun isThreadStarred(threadId: Long): Boolean = false

    @Synchronized
    fun toggleThreadStar(address: String): Boolean {
        if (address.isBlank()) return false
        val norm = SecureStore.normalize(address)
        return if (isThreadStarred(norm)) {
            writableDatabase.delete("starred_threads", "address = ?", arrayOf(norm))
            starredThreadsAddressCache.remove(norm)
            false
        } else {
            val values = ContentValues().apply {
                put("address", norm)
                put("thread_id", -1)
            }
            writableDatabase.insertWithOnConflict("starred_threads", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            starredThreadsAddressCache.add(norm)
            true
        }
    }

    fun toggleThreadStar(threadId: Long, address: String): Boolean = toggleThreadStar(address)

    @Synchronized
    fun toggleStar(
        messageId: Long,
        isHidden: Boolean,
        threadId: Long,
        address: String,
        body: String,
        date: Long
    ): Boolean {
        return if (isStarred(messageId, isHidden)) {
            unstar(messageId, isHidden)
            false
        } else {
            star(messageId, isHidden, threadId, address, body, date)
            true
        }
    }

    @Synchronized
    fun star(
        messageId: Long,
        isHidden: Boolean,
        threadId: Long,
        address: String,
        body: String,
        date: Long
    ) {
        val values = ContentValues().apply {
            put("message_id", messageId)
            put("is_hidden", if (isHidden) 1 else 0)
            put("thread_id", threadId)
            put("address", address)
            put("body", body)
            put("date", date)
        }
        writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        if (isHidden) hiddenStarredCache.add(messageId) else systemStarredCache.add(messageId)
    }

    @Synchronized
    fun unstar(messageId: Long, isHidden: Boolean) {
        writableDatabase.delete(
            TABLE,
            "message_id = ? AND is_hidden = ?",
            arrayOf(messageId.toString(), if (isHidden) "1" else "0")
        )
        if (isHidden) hiddenStarredCache.remove(messageId) else systemStarredCache.remove(messageId)
    }

    @Synchronized
    fun getStarredSystemMessageIds(threadId: Long): Set<Long> {
        val set = mutableSetOf<Long>()
        readableDatabase.query(
            TABLE,
            arrayOf("message_id"),
            "thread_id = ? AND is_hidden = 0",
            arrayOf(threadId.toString()),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) set.add(c.getLong(0))
        }
        return set
    }

    @Synchronized
    fun getStarredHiddenMessageIds(address: String): Set<Long> {
        val target = SecureStore.normalize(address)
        val set = mutableSetOf<Long>()
        readableDatabase.query(
            TABLE,
            arrayOf("message_id", "address"),
            "is_hidden = 1",
            null, null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val addr = c.getString(1) ?: ""
                if (SecureStore.normalize(addr) == target) {
                    set.add(c.getLong(0))
                }
            }
        }
        return set
    }

    @Synchronized
    fun getAllFavorites(): List<Favorite> {
        val list = mutableListOf<Favorite>()
        readableDatabase.query(
            TABLE,
            arrayOf("message_id", "address", "body", "date"),
            "is_hidden = 0",
            null, null, null, "date DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                list.add(
                    Favorite(
                        c.getLong(0),
                        c.getString(1) ?: "",
                        c.getString(2) ?: "",
                        c.getLong(3)
                    )
                )
            }
        }
        return list
    }

    companion object {
        private const val DB_NAME = "starred.db"
        private const val DB_VERSION = 2
        private const val TABLE = "starred_messages"

        @Volatile
        private var instance: StarredDbHelper? = null

        fun getInstance(context: Context): StarredDbHelper =
            instance ?: synchronized(this) {
                instance ?: StarredDbHelper(context).also { instance = it }
            }
    }
}

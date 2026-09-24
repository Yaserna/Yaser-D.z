package com.privatemsg.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Private local database for hidden messages.
 * Thread-safe singleton with WAL mode enabled to prevent database locking.
 */
class HiddenDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        try {
            db.enableWriteAheadLogging()
        } catch (_: Exception) {}
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "address TEXT, " +
                "body TEXT, " +
                "date INTEGER, " +
                "type INTEGER, " +
                "sub_id INTEGER DEFAULT -1, " +
                "status INTEGER DEFAULT -1, " +
                "read INTEGER DEFAULT 1)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hidden_address ON $TABLE(address)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN sub_id INTEGER DEFAULT -1")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN status INTEGER DEFAULT -1")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN read INTEGER DEFAULT 1")
        }
        if (oldVersion < 5) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_hidden_address ON $TABLE(address)")
        }
    }

    @Synchronized
    fun insert(address: String, body: String, date: Long, type: Int, subId: Int = -1): Long {
        val values = ContentValues().apply {
            put("address", address)
            put("body", body)
            put("date", date)
            put("type", type)
            put("sub_id", subId)
            put("status", -1)
            put("read", if (type == INBOX) 0 else 1)
        }
        return writableDatabase.insert(TABLE, null, values)
    }

    @Synchronized
    fun markRead(address: String) {
        val target = SecureStore.normalize(address)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.query(TABLE, arrayOf("id", "address"), "read = 0", null, null, null, null).use { c ->
                while (c.moveToNext()) {
                    if (SecureStore.normalize(c.getString(1) ?: "") == target) {
                        val v = ContentValues().apply { put("read", 1) }
                        db.update(TABLE, v, "id = ?", arrayOf(c.getLong(0).toString()))
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun hasUnread(address: String): Boolean {
        val target = SecureStore.normalize(address)
        readableDatabase.query(
            TABLE, arrayOf("address"), "read = 0 AND type = $INBOX", null, null, null, null
        ).use { c ->
            while (c.moveToNext()) {
                if (SecureStore.normalize(c.getString(0) ?: "") == target) return true
            }
        }
        return false
    }

    @Synchronized
    fun updateStatus(id: Long, status: Int) {
        val values = ContentValues().apply { put("status", status) }
        writableDatabase.update(TABLE, values, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun updateType(id: Long, type: Int) {
        val values = ContentValues().apply { put("type", type) }
        writableDatabase.update(TABLE, values, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun getConversations(): List<Conversation> {
        val list = mutableListOf<Conversation>()
        val seen = HashSet<String>()
        readableDatabase.query(
            TABLE, arrayOf("address", "body", "date"),
            null, null, null, null, "date DESC"
        ).use { c ->
            while (c.moveToNext()) {
                val address = c.getString(0) ?: ""
                val key = SecureStore.normalize(address)
                if (!seen.add(key)) continue
                list.add(
                    Conversation(
                        threadId = key.hashCode().toLong(),
                        address = address,
                        snippet = c.getString(1) ?: "",
                        date = c.getLong(2)
                    )
                )
            }
        }
        return list
    }

    @Synchronized
    fun getMessages(address: String): List<Message> {
        val list = mutableListOf<Message>()
        val target = SecureStore.normalize(address)
        readableDatabase.query(
            TABLE, arrayOf("id", "address", "body", "date", "type", "sub_id", "status"),
            null, null, null, null, "date ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val addr = c.getString(1) ?: ""
                if (SecureStore.normalize(addr) != target) continue
                list.add(
                    Message(
                        id = c.getLong(0),
                        threadId = 0,
                        address = addr,
                        body = c.getString(2) ?: "",
                        date = c.getLong(3),
                        type = c.getInt(4),
                        subId = c.getInt(5),
                        status = c.getInt(6)
                    )
                )
            }
        }
        return list
    }

    @Synchronized
    fun deleteById(id: Long) {
        writableDatabase.delete(TABLE, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun deleteByAddress(address: String) {
        val target = SecureStore.normalize(address)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.query(TABLE, arrayOf("id", "address"), null, null, null, null, null).use { c ->
                while (c.moveToNext()) {
                    val addr = c.getString(1) ?: ""
                    if (SecureStore.normalize(addr) == target) {
                        db.delete(TABLE, "id = ?", arrayOf(c.getLong(0).toString()))
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private const val DB_NAME = "hidden.db"
        private const val DB_VERSION = 5
        private const val TABLE = "hidden_sms"
        private const val INBOX = 1

        @Volatile
        private var instance: HiddenDbHelper? = null

        fun getInstance(context: Context): HiddenDbHelper =
            instance ?: synchronized(this) {
                instance ?: HiddenDbHelper(context).also { instance = it }
            }
    }
}

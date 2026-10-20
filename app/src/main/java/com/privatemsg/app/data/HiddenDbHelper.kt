package com.privatemsg.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Private local database for hidden messages.
 * Thread-safe singleton with WAL mode enabled to prevent database locking.
 *
 * The address and body columns are stored encrypted (see [DbCipher]), so opening the
 * file gives unreadable text. Because encrypted values cannot be compared, every row
 * also carries [COL_ADDR_HASH] - a keyed hash of the normalized address - and all
 * lookups go through that column instead of scanning the whole table.
 */
class HiddenDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val appContext = context.applicationContext

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
                "read INTEGER DEFAULT 1, " +
                "$COL_ADDR_HASH TEXT)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hidden_addr_hash ON $TABLE($COL_ADDR_HASH)")
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
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_ADDR_HASH TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_hidden_addr_hash ON $TABLE($COL_ADDR_HASH)")
            // The address column now holds ciphertext, so the old index can never match
            // a lookup again - drop it instead of maintaining a dead one.
            db.execSQL("DROP INDEX IF EXISTS idx_hidden_address")
            encryptExistingRows(db)
        }
    }

    /**
     * Upgrades rows written before encryption existed: computes their lookup hash and
     * rewrites the plaintext address/body as ciphertext.
     *
     * SQLiteOpenHelper already runs [onUpgrade] inside its own transaction, so no
     * transaction is started here (a nested one throws on older Android versions).
     * Failures are swallowed on purpose: a half-migrated table stays readable, and
     * [ensureBackfilled] repairs whatever the migration could not finish.
     */
    private fun encryptExistingRows(db: SQLiteDatabase) {
        try {
            val ids = mutableListOf<Long>()
            val addresses = mutableListOf<String>()
            val bodies = mutableListOf<String>()
            db.query(TABLE, arrayOf("id", "address", "body"), null, null, null, null, null).use { c ->
                while (c.moveToNext()) {
                    ids.add(c.getLong(0))
                    addresses.add(c.getString(1) ?: "")
                    bodies.add(c.getString(2) ?: "")
                }
            }
            for (i in ids.indices) {
                val values = ContentValues().apply {
                    put(COL_ADDR_HASH, DbCipher.lookupHash(appContext, SecureStore.normalize(addresses[i])))
                    put("address", DbCipher.encrypt(appContext, addresses[i]))
                    put("body", DbCipher.encrypt(appContext, bodies[i]))
                }
                db.update(TABLE, values, "id = ?", arrayOf(ids[i].toString()))
            }
            backfillChecked = true
        } catch (_: Exception) {
            // Leave it for ensureBackfilled() rather than breaking the whole section.
        }
    }

    @Volatile
    private var backfillChecked = false

    // Deliberately not `this`: every other method of this class is @Synchronized, and
    // some of them call ensureBackfilled() while holding that monitor (and an open
    // SQLite transaction, in insertAll's case). Locking a private object here keeps
    // the lock order one-way and cannot deadlock against a concurrent write.
    private val backfillLock = Any()

    /**
     * A row without a lookup hash is invisible to every address-based query, so any
     * row the migration could not finish is fixed here - once per process, before the
     * first read of the table.
     */
    private fun ensureBackfilled() {
        if (backfillChecked) return
        synchronized(backfillLock) {
            if (backfillChecked) return
            try {
                val db = writableDatabase
                val pending = mutableListOf<Triple<Long, String, String>>()
                db.query(
                    TABLE, arrayOf("id", "address", "body"),
                    "$COL_ADDR_HASH IS NULL OR $COL_ADDR_HASH = ''", null, null, null, null
                ).use { c ->
                    while (c.moveToNext()) {
                        pending.add(Triple(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: ""))
                    }
                }
                for ((id, address, body) in pending) {
                    val values = ContentValues().apply {
                        put(COL_ADDR_HASH, DbCipher.lookupHash(appContext, SecureStore.normalize(address)))
                        put("address", DbCipher.encrypt(appContext, address))
                        put("body", DbCipher.encrypt(appContext, body))
                    }
                    db.update(TABLE, values, "id = ?", arrayOf(id.toString()))
                }
                backfillChecked = true
            } catch (_: Exception) {
                // Retried on the next read; nothing here may break the section.
            }
        }
    }

    @Synchronized
    fun insert(address: String, body: String, date: Long, type: Int, subId: Int = -1): Long {
        val values = ContentValues().apply {
            put(COL_ADDR_HASH, DbCipher.lookupHash(appContext, SecureStore.normalize(address)))
            put("address", DbCipher.encrypt(appContext, address))
            put("body", DbCipher.encrypt(appContext, body))
            put("date", date)
            put("type", type)
            put("sub_id", subId)
            put("status", -1)
            put("read", if (type == INBOX) 0 else 1)
        }
        return writableDatabase.insert(TABLE, null, values)
    }

    /**
     * Inserts many rows as one transaction while holding this helper's monitor, so no
     * concurrent writer (e.g. the incoming-SMS worker's [insert]) can slip its own
     * writes into the transaction and be silently rolled back if it fails.
     */
    @Synchronized
    fun insertAll(rows: List<Message>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) insert(r.address, r.body, r.date, r.type, r.subId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * [insertAll] but skipping anything the table already holds for these addresses.
     * A move from the public store is two steps (copy, then delete) and the delete can
     * fail halfway; without this guard the next sweep would import the same message a
     * second time and the chat would show it twice.
     *
     * Duplicates are matched on the decrypted date, type and body, which only ever live
     * in memory here - nothing comparable is written to disk.
     *
     * @return the number of rows actually inserted.
     */
    @Synchronized
    fun insertAllDistinct(rows: List<Message>): Int {
        if (rows.isEmpty()) return 0
        val known = rows.map { SecureStore.normalize(it.address) }.distinct()
            .flatMap { getMessages(it) }
            .map { keyOf(it.date, it.type, it.body) }
            .toSet()
        val fresh = rows.filterNot { known.contains(keyOf(it.date, it.type, it.body)) }
        insertAll(fresh)
        return fresh.size
    }

    private fun keyOf(date: Long, type: Int, body: String): String = "$date|$type|$body"

    @Synchronized
    fun markRead(address: String) {
        ensureBackfilled()
        val values = ContentValues().apply { put("read", 1) }
        writableDatabase.update(
            TABLE, values,
            "read = 0 AND $COL_ADDR_HASH = ?",
            arrayOf(hashOf(address))
        )
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
        ensureBackfilled()
        val latestByKey = LinkedHashMap<String, Conversation>()
        val unreadKeys = HashSet<String>()
        readableDatabase.query(
            TABLE, arrayOf(COL_ADDR_HASH, "address", "body", "date", "type", "read"),
            null, null, null, null, "date DESC"
        ).use { c ->
            while (c.moveToNext()) {
                val address = decrypted(c.getString(1))
                // Rows written before the hash column exist still group correctly.
                val key = c.getString(0)?.takeIf { it.isNotEmpty() }
                    ?: DbCipher.lookupHash(appContext, SecureStore.normalize(address))
                if (c.getInt(4) == INBOX && c.getInt(5) == 0) unreadKeys.add(key)
                if (latestByKey.containsKey(key)) continue
                // The query is newest-first, so this is the latest message of the address.
                latestByKey[key] = Conversation(
                    threadId = SecureStore.normalize(address).hashCode().toLong(),
                    address = address,
                    snippet = decryptedBody(c.getString(2)),
                    date = c.getLong(3)
                )
            }
        }
        return latestByKey.map { (key, conv) -> conv.copy(unread = unreadKeys.contains(key)) }
    }

    @Synchronized
    fun getMessages(address: String): List<Message> {
        ensureBackfilled()
        val list = mutableListOf<Message>()
        readableDatabase.query(
            TABLE, arrayOf("id", "address", "body", "date", "type", "sub_id", "status"),
            "$COL_ADDR_HASH = ?", arrayOf(hashOf(address)), null, null, "date ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Message(
                        id = c.getLong(0),
                        threadId = 0,
                        address = decrypted(c.getString(1)),
                        body = decrypted(c.getString(2)),
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
        ensureBackfilled()
        writableDatabase.delete(TABLE, "$COL_ADDR_HASH = ?", arrayOf(hashOf(address)))
    }

    private fun hashOf(address: String): String =
        DbCipher.lookupHash(appContext, SecureStore.normalize(address))

    private fun decrypted(stored: String?): String =
        stored?.let { DbCipher.decrypt(appContext, it) } ?: ""

    /** A body whose cipher this key can no longer open shows a placeholder, never the ciphertext. */
    private fun decryptedBody(stored: String?): String {
        if (stored == null) return ""
        // One decrypt only: asking isUndecryptable() first would run the same
        // AES-GCM decryption twice per row on every list refresh.
        val plain = DbCipher.decrypt(appContext, stored)
        return if (plain.isEmpty() && DbCipher.isEncrypted(stored))
            appContext.getString(com.privatemsg.app.R.string.content_unavailable)
        else plain
    }

    companion object {
        private const val DB_NAME = "hidden.db"
        private const val DB_VERSION = 6
        private const val TABLE = "hidden_sms"
        private const val COL_ADDR_HASH = "addr_hash"
        private const val INBOX = 1

        @Volatile
        private var instance: HiddenDbHelper? = null

        fun getInstance(context: Context): HiddenDbHelper =
            instance ?: synchronized(this) {
                instance ?: HiddenDbHelper(context).also { instance = it }
            }
    }
}

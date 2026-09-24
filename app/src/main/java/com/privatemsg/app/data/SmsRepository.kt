package com.privatemsg.app.data

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony

class SmsRepository(private val context: Context) {

    private val secure = SecureStore(context)

    /** Normal conversations, excluding any hidden numbers. */
    fun getConversations(): List<Conversation> {
        val latest = LinkedHashMap<Long, Conversation>()
        val unreadThreads = HashSet<Long>()
        val hidden = secure.getHiddenNumbers()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
            Telephony.Sms.TYPE
        )
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            val iThread = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iRead = c.getColumnIndexOrThrow(Telephony.Sms.READ)
            val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (c.moveToNext()) {
                val thread = c.getLong(iThread)
                val type = c.getInt(iType)
                // A conversation is unread if ANY incoming message in it is unread
                // (not only when the latest message happens to be the unread one).
                if (type == Telephony.Sms.MESSAGE_TYPE_INBOX && c.getInt(iRead) == 0) {
                    unreadThreads.add(thread)
                }
                if (latest.containsKey(thread)) continue
                val address = c.getString(iAddr) ?: ""
                if (hidden.contains(SecureStore.normalize(address))) continue
                latest[thread] = Conversation(
                    threadId = thread,
                    address = address,
                    snippet = c.getString(iBody) ?: "",
                    date = c.getLong(iDate),
                    unread = false, // set below, after the full scan
                    isMuted = secure.isMuted(address),
                    // The latest message failed to send → flag the conversation.
                    failed = type == Telephony.Sms.MESSAGE_TYPE_FAILED
                )
            }
        }
        return latest.values.map { it.copy(unread = it.threadId in unreadThreads) }
    }

    /** Bodies of the still-unread incoming messages in a thread (oldest → newest). */
    fun unreadBodies(threadId: Long): List<String> {
        val list = mutableListOf<String>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.BODY),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ? AND ${Telephony.Sms.READ} = 0",
            arrayOf(threadId.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
            "${Telephony.Sms.DATE} ASC"
        )?.use { c -> while (c.moveToNext()) list.add(c.getString(0) ?: "") }
        return list
    }

    fun getMessages(threadId: Long): List<Message> {
        val list = mutableListOf<Message>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.SUBSCRIPTION_ID,
            Telephony.Sms.STATUS
        )
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} ASC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val iSub = c.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
            val iStatus = c.getColumnIndex(Telephony.Sms.STATUS)
            while (c.moveToNext()) {
                list.add(
                    Message(
                        id = c.getLong(iId),
                        threadId = threadId,
                        address = c.getString(iAddr) ?: "",
                        body = c.getString(iBody) ?: "",
                        date = c.getLong(iDate),
                        type = c.getInt(iType),
                        subId = if (iSub >= 0) c.getInt(iSub) else -1,
                        status = if (iStatus >= 0) c.getInt(iStatus) else -1
                    )
                )
            }
        }
        return list
    }

    /** Stores the sent message and returns its row Uri (for status updates). */
    fun storeSentMessage(address: String, body: String, subId: Int): android.net.Uri? {
        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
        val values = ContentValues().apply {
            put(Telephony.Sms.THREAD_ID, threadId)
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE)
            if (subId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        }
        val uri = context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
        context.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
        return uri
    }

    /**
     * Inserts a fake received message into a (cover) conversation — used when a
     * decoy notification is tapped, so the fake text shows there with its time.
     * Skips insertion if the same message is already present (e.g. tapped twice).
     */
    fun insertDecoyInbox(address: String, body: String, date: Long) {
        val exists = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.DATE} = ? AND ${Telephony.Sms.BODY} = ?",
            arrayOf(address, date.toString(), body), null
        )?.use { it.count > 0 } ?: false
        if (exists) return
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
    }

    fun deleteMessage(id: Long) {
        context.contentResolver.delete(
            Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID} = ?", arrayOf(id.toString())
        )
    }

    fun deleteMessages(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        ids.chunked(100).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val args = chunk.map { it.toString() }.toTypedArray()
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms._ID} IN ($placeholders)",
                args
            )
        }
    }

    fun deleteThread(threadId: Long) {
        val starredDb = StarredDbHelper.getInstance(context)
        val address = try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS),
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                null
            )?.use { if (it.moveToFirst()) it.getString(0) else "" } ?: ""
        } catch (_: Exception) { "" }

        if (address.isNotBlank() && starredDb.isThreadStarred(address)) {
            return
        }

        val starredIds = starredDb.getStarredSystemMessageIds(threadId)
        if (starredIds.isEmpty()) {
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString())
            )
        } else {
            val inClause = starredIds.joinToString(",")
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms._ID} NOT IN ($inClause)",
                arrayOf(threadId.toString())
            )
        }
    }

    fun insertInboxMessage(
        address: String,
        body: String,
        date: Long,
        subId: Int = -1,
        serviceCenter: String = ""
    ): Long {
        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
        val values = ContentValues().apply {
            put(Telephony.Sms.THREAD_ID, threadId)
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            if (subId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
            if (serviceCenter.isNotEmpty()) put(Telephony.Sms.SERVICE_CENTER, serviceCenter)
        }
        val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        val id = uri?.lastPathSegment?.toLongOrNull() ?: -1L
        context.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
        return id
    }

    fun markThreadRead(threadId: Long) {
        val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
        context.contentResolver.update(
            Telephony.Sms.CONTENT_URI, values,
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
            arrayOf(threadId.toString())
        )
    }

    /**
     * Move every message of [address] from the system store into the private
     * hidden database, then delete them from the system store so the
     * conversation disappears from everywhere outside this app.
     */
        fun migrateToHidden(address: String, hiddenDb: HiddenDbHelper) {
        val target = SecureStore.normalize(address)
        val idsToDelete = mutableListOf<Long>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )
        val db = hiddenDb.writableDatabase
        db.beginTransaction()
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, projection, null, null,
                "${Telephony.Sms.DATE} ASC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                while (c.moveToNext()) {
                    val addr = c.getString(iAddr) ?: ""
                    if (SecureStore.normalize(addr) != target) continue
                    hiddenDb.insert(
                        addr,
                        c.getString(iBody) ?: "",
                        c.getLong(iDate),
                        c.getInt(iType)
                    )
                    idsToDelete.add(c.getLong(iId))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        idsToDelete.chunked(100).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val args = chunk.map { it.toString() }.toTypedArray()
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms._ID} IN ($placeholders)",
                args
            )
        }
    }

    /**
     * Safety sweep: make sure NO message from any hidden number is left in the
     * system store. Any that are found are moved into the private hidden database
     * and removed from the provider, so switching the default SMS app reveals
     * no trace of hidden conversations.
     */
    fun purgeHiddenFromProvider(hiddenDb: HiddenDbHelper) {
        val hidden = secure.getHiddenNumbers().map { SecureStore.normalize(it) }.toSet()
        if (hidden.isEmpty()) return
        val idsToDelete = mutableListOf<Long>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.SUBSCRIPTION_ID
        )
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} ASC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val iSub = c.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
            while (c.moveToNext()) {
                val addr = c.getString(iAddr) ?: ""
                if (SecureStore.normalize(addr) !in hidden) continue
                hiddenDb.insert(
                    addr,
                    c.getString(iBody) ?: "",
                    c.getLong(iDate),
                    c.getInt(iType),
                    if (iSub >= 0) c.getInt(iSub) else -1
                )
                idsToDelete.add(c.getLong(iId))
            }
        }
        for (id in idsToDelete) {
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID} = ?", arrayOf(id.toString())
            )
        }
    }

    /** Move a hidden conversation back into the visible system store. */
                fun restoreFromHidden(address: String, hiddenDb: HiddenDbHelper) {
        val messages = hiddenDb.getMessages(address)
        val sentValues = mutableListOf<ContentValues>()
        val inboxValues = mutableListOf<ContentValues>()
        for (m in messages) {
            // بازیابی قطعی متن اصلی: در صورت وجود پیام‌های رمزشده تستی، آن‌ها را به متن باز می‌گرداند
            val cleanBody = if (NumericCipher.isNumericEncrypted(m.body)) {
                NumericCipher.decryptFromNumeric(m.body)?.text ?: m.body
            } else if (m.body.endsWith(" a+")) {
                m.body.removeSuffix(" a+")
            } else if (m.body.endsWith("a+")) {
                m.body.removeSuffix("a+")
            } else {
                m.body
            }
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, m.address)
                put(Telephony.Sms.BODY, cleanBody)
                put(Telephony.Sms.DATE, m.date)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.TYPE, m.type)
            }
            if (m.type == Telephony.Sms.MESSAGE_TYPE_SENT) {
                sentValues.add(values)
            } else {
                inboxValues.add(values)
            }
        }
        if (sentValues.isNotEmpty()) {
            context.contentResolver.bulkInsert(Telephony.Sms.Sent.CONTENT_URI, sentValues.toTypedArray())
        }
        if (inboxValues.isNotEmpty()) {
            context.contentResolver.bulkInsert(Telephony.Sms.Inbox.CONTENT_URI, inboxValues.toTypedArray())
        }
        hiddenDb.deleteByAddress(address)
    }
}


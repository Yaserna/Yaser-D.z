package com.privatemsg.app.data

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony

class SmsRepository(context: Context) {

    /**
     * Only the application context is kept: this repository is handed to worker threads
     * that can outlive a screen, and holding an Activity would leak it.
     */
    private val context = context.applicationContext

    private val secure = SecureStore(context)

    /**
     * Normal conversations, excluding any hidden numbers.
     *
     * Two passes, because BODY is the column that makes this query expensive: pass 1 walks
     * every row with numeric columns only (no text is copied over the binder, so a phone
     * with tens of thousands of messages scans in tens of milliseconds) and records the id
     * of each thread's newest row; pass 2 asks for the snippets of just those ids. Reading
     * every body up front meant the cold-start list had to move the whole message store.
     */
    fun getConversations(): List<Conversation> {
        // thread id -> newest row (its _id for the snippet, address/date/type for the rest)
        class Newest(var messageId: Long, var address: String, var date: Long, var failed: Boolean)
        val latest = LinkedHashMap<Long, Newest>()
        val unreadThreads = HashSet<Long>()
        // Compare normalized forms: the stored entries are whatever the user typed
        // ("+98 912 ..." etc.), which never equals the normalized address as-is.
        val hidden = secure.getHiddenNumbers().map { SecureStore.normalize(it) }.toSet()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.DATE,
                Telephony.Sms.READ,
                Telephony.Sms.TYPE
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val iThread = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
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
                latest[thread] = Newest(
                    messageId = c.getLong(iId),
                    address = address,
                    date = c.getLong(iDate),
                    // The latest message failed to send → flag the conversation.
                    failed = type == Telephony.Sms.MESSAGE_TYPE_FAILED
                )
            }
        }
        val snippets = bodiesFor(latest.values.map { it.messageId })
        return latest.entries.map { (thread, row) ->
            Conversation(
                threadId = thread,
                address = row.address,
                snippet = snippets[row.messageId].orEmpty(),
                date = row.date,
                unread = thread in unreadThreads,
                isMuted = secure.isMuted(row.address),
                failed = row.failed
            )
        }
    }

    /** Bodies of the given message rows, keyed by id, fetched in provider-sized chunks. */
    private fun bodiesFor(ids: List<Long>): Map<Long, String?> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, String>(ids.size)
        ids.chunked(100).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY),
                "${Telephony.Sms._ID} IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray(),
                null
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                while (c.moveToNext()) out[c.getLong(iId)] = c.getString(iBody) ?: ""
            }
        }
        return out
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
        // A number the system cannot turn into a thread (an operator name, a short code)
        // must not abort the store: the message would vanish before it ever reached the
        // radio, and the caller's UI callback would never run.
        val threadId = try {
            Telephony.Threads.getOrCreateThreadId(context, address)
        } catch (e: Exception) {
            android.util.Log.e("SmsRepository", "no thread for this address", e)
            -1L
        }
        val values = ContentValues().apply {
            if (threadId > 0) put(Telephony.Sms.THREAD_ID, threadId)
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            // This app reads provider status 0 as "delivered" (and the hidden DB uses
            // -1 for "unknown"), so a fresh row must NOT be written as 0 here.
            put(Telephony.Sms.STATUS, -1)
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
        val threadId = try {
            Telephony.Threads.getOrCreateThreadId(context, address)
        } catch (_: Exception) {
            0L
        }
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            // Set explicitly: the screen looks this row up by thread, and a row left
            // unthreaded by the provider would never appear in that conversation.
            if (threadId > 0) put(Telephony.Sms.THREAD_ID, threadId)
        }
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        context.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
    }

    fun deleteMessage(id: Long) {
        context.contentResolver.delete(
            Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID} = ?", arrayOf(id.toString())
        )
    }

    /**
     * The radio refused a message that was already stored as "sent": drop that row and
     * write the same text as a queued outbox entry instead, so it stays readable, looks
     * visibly pending, and can be retried - instead of carrying a false sent tick.
     *
     * @return true when the queued row was written.
     */
    fun queueFailedSend(
        sentUri: android.net.Uri,
        address: String,
        body: String,
        threadId: Long,
        subId: Int
    ): Boolean = try {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE)
            if (threadId > 0) put(Telephony.Sms.THREAD_ID, threadId)
            if (subId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        }
        // Remove the optimistic "sent" row first, or the same message would show twice.
        context.contentResolver.delete(sentUri, null, null)
        context.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values)
        context.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
        true
    } catch (e: Exception) {
        android.util.Log.e("SmsRepository", "could not requeue failed send", e)
        false
    }

    /** @return how many rows the provider actually removed (the caller can log a shortfall). */
    fun deleteMessages(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        var deleted = 0
        ids.chunked(100).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val args = chunk.map { it.toString() }.toTypedArray()
            deleted += context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms._ID} IN ($placeholders)",
                args
            )
        }
        return deleted
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
        // As the default SMS app this row is the only copy the user will ever get, so a
        // sender the thread lookup cannot resolve (an operator name, a short code) must
        // not lose the message: it is stored without a thread id instead.
        val threadId = try {
            Telephony.Threads.getOrCreateThreadId(context, address)
        } catch (e: Exception) {
            android.util.Log.e("SmsRepository", "no thread for this sender", e)
            -1L
        }
        val values = ContentValues().apply {
            if (threadId > 0) put(Telephony.Sms.THREAD_ID, threadId)
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
     *
     * Lure rows are left where they are: the honeypot put them in the public store on
     * purpose, and moving them into the private table would take the one trace the
     * intruder is meant to follow away from the screen they are watching.
     */
        fun migrateToHidden(address: String, hiddenDb: HiddenDbHelper) {
        val target = SecureStore.normalize(address)
        val lures = secure.lureIds()
        val idsToDelete = mutableListOf<Long>()
        val moved = mutableListOf<Message>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.SUBSCRIPTION_ID
        )
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, projection, null, null,
            "${Telephony.Sms.DATE} ASC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val iSub = c.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
            while (c.moveToNext()) {
                val rowId = c.getLong(iId)
                // A lure row belongs in the public store by design (see
                // SecureStore.rememberLure).
                if (rowId in lures) continue
                val addr = c.getString(iAddr) ?: ""
                if (SecureStore.normalize(addr) != target) continue
                moved.add(
                    Message(
                        id = 0,
                        threadId = 0,
                        address = addr,
                        body = c.getString(iBody) ?: "",
                        date = c.getLong(iDate),
                        type = c.getInt(iType),
                        subId = if (iSub >= 0) c.getInt(iSub) else -1
                    )
                )
                idsToDelete.add(c.getLong(iId))
            }
        }
        // Batched inside the helper's own lock: no concurrent writer can join this
        // transaction, and the provider query above already finished before it opened.
        hiddenDb.insertAllDistinct(moved)

        val deleted = deleteMessages(idsToDelete)
        if (deleted < idsToDelete.size) {
            // Rows left behind are a privacy trace: they surface in the public list until
            // the next hidden-section visit sweeps them again (the sweep now de-dupes).
            android.util.Log.w(
                "SmsRepository",
                "hide move incomplete: ${idsToDelete.size - deleted} of ${idsToDelete.size} rows still in the system store"
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
        // Read the lure ids once: the loop below walks every row in the message store.
        val lures = secure.lureIds()
        val idsToDelete = mutableListOf<Long>()
        val moved = mutableListOf<Message>()
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
                val rowId = c.getLong(iId)
                // A lure row belongs in the public store by design (see
                // SecureStore.rememberLure): sweeping it would delete the conversation
                // an intruder is following and turn it into a private one.
                if (rowId in lures) continue
                val addr = c.getString(iAddr) ?: ""
                if (SecureStore.normalize(addr) !in hidden) continue
                moved.add(
                    Message(
                        id = 0,
                        threadId = 0,
                        address = addr,
                        body = c.getString(iBody) ?: "",
                        date = c.getLong(iDate),
                        type = c.getInt(iType),
                        subId = if (iSub >= 0) c.getInt(iSub) else -1
                    )
                )
                idsToDelete.add(c.getLong(iId))
            }
        }
        // One transaction for the whole sweep instead of one provider call per row, and
        // rows the private DB already holds are not imported a second time.
        hiddenDb.insertAllDistinct(moved)
        deleteMessages(idsToDelete)
    }

    /**
     * Move a hidden conversation back into the visible system store.
     *
     * @return true when every row reached the provider. On false the private rows are
     *   kept: deleting them after a refused insert would lose messages that were never
     *   actually restored.
     */
    fun restoreFromHidden(address: String, hiddenDb: HiddenDbHelper): Boolean {
        val messages = hiddenDb.getMessages(address)
        val threadId = try {
            Telephony.Threads.getOrCreateThreadId(context, address)
        } catch (_: Exception) {
            0L
        }
        val sentValues = mutableListOf<ContentValues>()
        val inboxValues = mutableListOf<ContentValues>()
        val outboxValues = mutableListOf<ContentValues>()
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
                // Restored sends must not inherit the provider's default status 0,
                // which this app shows as "delivered".
                put(Telephony.Sms.STATUS, -1)
                if (threadId > 0) put(Telephony.Sms.THREAD_ID, threadId)
                if (m.subId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, m.subId)
            }
            // Route by real history: inbox, confirmed sends, and - for queued/failed
            // rows - the outbox, which keeps their type instead of rewriting them
            // into "sent" the way the sent table does.
            when (m.type) {
                Telephony.Sms.MESSAGE_TYPE_INBOX -> inboxValues.add(values)
                Telephony.Sms.MESSAGE_TYPE_SENT -> sentValues.add(values)
                else -> outboxValues.add(values)
            }
        }
        // A refused insert (row too large, provider busy, a thread that vanished) must
        // not cost the user the messages: the private rows stay until all of them made it.
        // The rows are inserted one by one, so the ids that actually landed are known:
        // a rollback can then remove exactly those, and a retry cannot duplicate them.
        val landedIds = mutableListOf<Long>()
        // A provider may answer with a uri whose last segment is not a row id. Such a row
        // did land, so it has to be removed by its own uri, otherwise it would stay in the
        // public store next to the private copy that is being kept.
        val landedUris = mutableListOf<android.net.Uri>()
        var refused = false

        fun flush(uri: android.net.Uri, rows: List<ContentValues>) {
            for (row in rows) {
                val inserted = try {
                    context.contentResolver.insert(uri, row)
                } catch (e: Exception) {
                    android.util.Log.e("SmsRepository", "restore insert failed", e)
                    null
                }
                if (inserted == null) {
                    refused = true
                    continue
                }
                val id = inserted.lastPathSegment?.toLongOrNull()
                if (id == null || id <= 0L) {
                    landedUris.add(inserted)
                    refused = true
                } else {
                    landedIds.add(id)
                }
            }
        }
        flush(Telephony.Sms.Sent.CONTENT_URI, sentValues)
        flush(Telephony.Sms.Inbox.CONTENT_URI, inboxValues)
        flush(Telephony.Sms.Outbox.CONTENT_URI, outboxValues)
        if (refused) {
            // Undo the rows that made it, so the conversation is either fully public or
            // fully private - never half of each.
            val rolledBack = deleteMessages(landedIds)
            var rolledBackByUri = 0
            for (u in landedUris) {
                try {
                    context.contentResolver.delete(u, null, null)
                    rolledBackByUri++
                } catch (e: Exception) {
                    android.util.Log.e("SmsRepository", "restore rollback failed", e)
                }
            }
            android.util.Log.w(
                "SmsRepository",
                "restore incomplete: rolled back $rolledBack row(s) by id and " +
                    "$rolledBackByUri by uri"
            )
            return false
        }
        hiddenDb.deleteByAddress(address)
        return true
    }
}


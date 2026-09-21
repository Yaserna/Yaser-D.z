package com.privatemsg.app.data

data class Conversation(
    val threadId: Long,
    val address: String,
    val snippet: String,
    val date: Long,
    val unread: Boolean = false,
    val failed: Boolean = false,
    val draft: String? = null,
    val isMuted: Boolean = false,
    val isProtected: Boolean = false
)

data class Message(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val subId: Int = -1,
    val status: Int = -1,
    val dateSent: Long = 0L,
    val serviceCenter: String = "",
    val isEncrypted: Boolean = false,
    val isFromHidden: Boolean = false
)

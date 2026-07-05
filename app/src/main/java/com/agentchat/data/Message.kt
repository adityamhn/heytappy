package com.agentchat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MessageSender {
    USER,
    AGENT,
    SYSTEM,
}

enum class MessageStatus {
    SENT,
    WORKING,
    DONE,
    ERROR,
    AWAITING_CONFIRMATION,
}

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val sender: MessageSender,
    val status: MessageStatus = MessageStatus.SENT,
    val timestamp: Long = System.currentTimeMillis(),
)

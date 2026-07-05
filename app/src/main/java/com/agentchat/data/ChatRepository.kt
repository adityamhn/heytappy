package com.agentchat.data

import kotlinx.coroutines.flow.Flow

class ChatRepository(private val dao: MessageDao) {

    val messages: Flow<List<Message>> = dao.observeMessages()

    suspend fun addUserMessage(text: String): Long =
        dao.insert(Message(text = text, sender = MessageSender.USER, status = MessageStatus.SENT))

    suspend fun addAgentMessage(
        text: String,
        status: MessageStatus = MessageStatus.DONE,
    ): Long = dao.insert(Message(text = text, sender = MessageSender.AGENT, status = status))

    suspend fun addSystemMessage(text: String): Long =
        dao.insert(Message(text = text, sender = MessageSender.SYSTEM, status = MessageStatus.DONE))

    suspend fun updateMessage(message: Message) = dao.update(message)

    suspend fun getMessage(id: Long): Message? = dao.getById(id)

    suspend fun latestMessage(): Message? = dao.latestMessage()

    suspend fun clear() = dao.clear()
}

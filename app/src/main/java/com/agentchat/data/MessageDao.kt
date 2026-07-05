package com.agentchat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC, id ASC")
    fun observeMessages(): Flow<List<Message>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun latestMessage(): Message?

    @Insert
    suspend fun insert(message: Message): Long

    @Update
    suspend fun update(message: Message)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Long): Message?

    @Query("DELETE FROM messages")
    suspend fun clear()
}

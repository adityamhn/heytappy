package com.agentchat.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun senderToString(value: MessageSender): String = value.name

    @TypeConverter
    fun stringToSender(value: String): MessageSender = MessageSender.valueOf(value)

    @TypeConverter
    fun statusToString(value: MessageStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): MessageStatus = MessageStatus.valueOf(value)
}

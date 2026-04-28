package com.easyhomework.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Room entity representing a query history record.
 */
@Entity(tableName = "query_history")
@TypeConverters(ChatMessageListConverter::class)
data class QueryHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val screenshotPath: String,         // Path to the saved screenshot file
    val recognizedText: String,         // OCR recognized text
    val conversations: List<ChatMessage>, // Full conversation history
    val previewText: String = ""        // Short preview of the question for list display
)

/**
 * Type converter for Room to serialize/deserialize List<ChatMessage> as JSON.
 */
class ChatMessageListConverter {
    private val gson = Gson()

    @TypeConverter
    fun fromChatMessageList(messages: List<ChatMessage>): String {
        return gson.toJson(messages)
    }

    @TypeConverter
    fun toChatMessageList(json: String): List<ChatMessage> {
        val type = object : TypeToken<List<ChatMessage>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }
}

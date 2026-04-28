package com.easyhomework.app.model

/**
 * Represents a single chat message in a conversation.
 */
data class ChatMessage(
    val role: String,       // "system", "user", "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false  // true while the assistant response is still streaming
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        fun system(content: String) = ChatMessage(role = ROLE_SYSTEM, content = content)
        fun user(content: String) = ChatMessage(role = ROLE_USER, content = content)
        fun assistant(content: String, isStreaming: Boolean = false) =
            ChatMessage(role = ROLE_ASSISTANT, content = content, isStreaming = isStreaming)
    }
}

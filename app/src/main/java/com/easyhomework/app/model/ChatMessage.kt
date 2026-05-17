package com.easyhomework.app.model

import android.graphics.Bitmap
import com.easyhomework.app.tools.ToolCall

/**
 * Represents a single chat message in a conversation.
 * Supports multimodal content (text + images) and tool calls.
 */
data class ChatMessage(
    val role: String, // "system", "user", "assistant", "tool"
    val content: String,
    val imageBitmap: Bitmap? = null, // Optional image for vision models
    val toolCalls: List<ToolCall>? = null, // Tool calls from assistant
    val toolCallId: String? = null, // Tool call ID for tool results
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false, // true while the assistant response is still streaming
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"

        fun system(content: String) = ChatMessage(role = ROLE_SYSTEM, content = content)
        fun user(content: String) = ChatMessage(role = ROLE_USER, content = content)
        fun userWithImage(content: String, bitmap: Bitmap) =
            ChatMessage(role = ROLE_USER, content = content, imageBitmap = bitmap)
        fun assistant(content: String, isStreaming: Boolean = false) =
            ChatMessage(role = ROLE_ASSISTANT, content = content, isStreaming = isStreaming)
        fun assistantWithToolCalls(content: String?, toolCalls: List<ToolCall>) =
            ChatMessage(role = ROLE_ASSISTANT, content = content ?: "", toolCalls = toolCalls)
        fun toolResult(toolCallId: String, content: String) =
            ChatMessage(role = ROLE_TOOL, content = content, toolCallId = toolCallId)
    }
}

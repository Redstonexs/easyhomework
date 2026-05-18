package com.easyhomework.app.model

import android.graphics.Bitmap
import com.easyhomework.app.tools.ToolCall

/**
 * Represents a single chat message in a conversation.
 * Supports multimodal content (text + images) and tool calls.
 *
 * @property role Message role: system, user, assistant, or tool.
 * @property imageBitmap Optional image for vision models.
 * @property toolCalls Tool calls requested by the assistant.
 * @property toolCallId Tool call ID for tool result messages.
 * @property isStreaming True while the assistant response is still streaming.
 * reasoningContent stores OpenAI-compatible reasoning_content so thinking-mode
 * providers can validate tool-call follow-up messages.
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val imageBitmap: Bitmap? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val reasoningContent: String? = null,
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
        fun assistant(content: String, isStreaming: Boolean = false, reasoningContent: String? = null) =
            ChatMessage(
                role = ROLE_ASSISTANT,
                content = content,
                isStreaming = isStreaming,
                reasoningContent = reasoningContent,
            )
        fun assistantWithToolCalls(content: String?, toolCalls: List<ToolCall>, reasoningContent: String? = null) =
            ChatMessage(
                role = ROLE_ASSISTANT,
                content = content ?: "",
                toolCalls = toolCalls,
                reasoningContent = reasoningContent,
            )
        fun toolResult(toolCallId: String, content: String) =
            ChatMessage(role = ROLE_TOOL, content = content, toolCallId = toolCallId)
    }
}

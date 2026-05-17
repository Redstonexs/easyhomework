package com.easyhomework.app.network

import com.easyhomework.app.model.ApiType
import com.easyhomework.app.tools.ToolCall
import com.google.gson.JsonParser

/**
 * Parses Server-Sent Events (SSE) stream from OpenAI and Anthropic APIs.
 * Robust handling of non-standard lines, comments, and edge cases.
 * Supports tool call streaming.
 */
class SSEStreamParser {

    // Buffers for accumulating streamed tool calls. OpenAI-compatible APIs key by
    // tool index; Anthropic keys by content block index. Never rely on a single
    // active tool because several domestic providers stream multiple calls at once.
    private val toolCallBuffers = mutableMapOf<Int, ToolCallBuffer>()

    private data class ToolCallBuffer(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    /**
     * Parse a single SSE line.
     *
     * A chunk can legally contain thinking, answer text, and tool-call deltas at
     * the same time. Return all events so later fields do not get dropped.
     * Silently skips any non-parseable lines instead of emitting errors.
     */
    fun parseLine(line: String, apiType: ApiType = ApiType.OPENAI): List<ParseResult> {
        val trimmed = line.trim()

        // Skip empty lines, SSE comments (":"), and non-data fields ("event:", "id:", "retry:")
        if (trimmed.isEmpty() || trimmed.startsWith(":") ||
            trimmed.startsWith("event:") || trimmed.startsWith("id:") ||
            trimmed.startsWith("retry:")
        ) {
            return emptyList()
        }

        // Must start with "data:" (with or without space after colon)
        if (!trimmed.startsWith("data:")) {
            return emptyList()
        }

        // Extract data payload - handle both "data: {...}" and "data:{...}"
        val data = trimmed.removePrefix("data:").trim()

        // Check for stream end signal
        if (data == "[DONE]") {
            return flushToolCalls() + ParseResult.Done
        }

        // Empty data field
        if (data.isEmpty()) {
            return emptyList()
        }

        // Must be valid JSON (starts with '{')
        if (!data.startsWith("{")) {
            return emptyList()
        }

        return when (apiType) {
            ApiType.OPENAI -> parseOpenAIData(data)
            ApiType.ANTHROPIC -> parseAnthropicData(data)
        }
    }

    /**
     * Parse OpenAI-format streaming data chunk.
     */
    private fun parseOpenAIData(data: String): List<ParseResult> {
        return try {
            val jsonObject = JsonParser.parseString(data).asJsonObject

            val choices = jsonObject.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) {
                return emptyList()
            }

            val choice = choices[0].asJsonObject
            val delta = choice.getAsJsonObject("delta")
            val results = mutableListOf<ParseResult>()

            if (delta != null) {
                // Check for reasoning/thinking content (for models like o1, deepseek-r1)
                if (delta.has("reasoning_content")) {
                    val reasoning = delta.get("reasoning_content")
                    if (!reasoning.isJsonNull && reasoning.asString.isNotEmpty()) {
                        results.add(ParseResult.Thinking(reasoning.asString))
                    }
                }

                // Some domestic OpenAI-compatible APIs use "reasoning" instead.
                if (delta.has("reasoning")) {
                    val reasoning = delta.get("reasoning")
                    if (!reasoning.isJsonNull && reasoning.asString.isNotEmpty()) {
                        results.add(ParseResult.Thinking(reasoning.asString))
                    }
                }

                // Check for regular content (may coexist with reasoning/tool calls)
                if (delta.has("content")) {
                    val content = delta.get("content")
                    if (!content.isJsonNull && content.asString.isNotEmpty()) {
                        results.add(ParseResult.Content(content.asString))
                    }
                }

                // Check for tool calls
                if (delta.has("tool_calls")) {
                    val toolCalls = delta.getAsJsonArray("tool_calls")
                    for (tc in toolCalls) {
                        val tcObj = tc.asJsonObject
                        val index = tcObj.get("index")?.asInt ?: toolCallBuffers.size
                        val buffer = toolCallBuffers.getOrPut(index) { ToolCallBuffer() }

                        val id = tcObj.get("id")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                        if (id.isNotBlank()) buffer.id = id

                        val function = tcObj.getAsJsonObject("function")
                        val name = function?.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                        if (name.isNotBlank()) buffer.name = name

                        val arguments = function?.get("arguments")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                        if (arguments.isNotEmpty()) {
                            buffer.arguments.append(arguments)
                        }
                    }
                }

                // Older OpenAI-compatible servers may stream a single function_call
                // instead of tool_calls. Treat it as one normal tool call.
                if (delta.has("function_call")) {
                    val function = delta.getAsJsonObject("function_call")
                    if (function != null) {
                        val buffer = toolCallBuffers.getOrPut(0) { ToolCallBuffer() }
                        if (buffer.id.isBlank()) buffer.id = "tool_call_0"

                        val name = function.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                        if (name.isNotBlank()) buffer.name = name

                        val arguments = function.get("arguments")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                        if (arguments.isNotEmpty()) buffer.arguments.append(arguments)
                    }
                }
            }

            // Check for finish reason
            if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull) {
                results.addAll(flushToolCalls())
                results.add(ParseResult.Done)
            }

            results
        } catch (e: Exception) {
            // Clear stale buffers on parse error to prevent state leaks
            toolCallBuffers.clear()
            emptyList()
        }
    }

    /**
     * Parse Anthropic-format streaming data chunk.
     */
    private fun parseAnthropicData(data: String): List<ParseResult> {
        return try {
            val jsonObject = JsonParser.parseString(data).asJsonObject
            val type = jsonObject.get("type")?.asString ?: return emptyList()

            when (type) {
                "content_block_start" -> {
                    val index = jsonObject.get("index")?.asInt ?: 0
                    val contentBlock = jsonObject.getAsJsonObject("content_block")
                    if (contentBlock != null) {
                        val blockType = contentBlock.get("type")?.asString
                        if (blockType == "tool_use") {
                            val id = contentBlock.get("id")?.asString ?: ""
                            val name = contentBlock.get("name")?.asString ?: ""
                            val input = contentBlock.getAsJsonObject("input")?.toString().orEmpty()
                            val buffer = ToolCallBuffer(id = id, name = name)
                            if (input.isNotEmpty()) buffer.arguments.append(input)
                            toolCallBuffers[index] = buffer
                        }
                    }
                    emptyList()
                }
                "content_block_delta" -> {
                    val index = jsonObject.get("index")?.asInt ?: 0
                    val delta = jsonObject.getAsJsonObject("delta") ?: return emptyList()
                    val deltaType = delta.get("type")?.asString

                    when (deltaType) {
                        "text_delta" -> {
                            val text = delta.get("text")?.asString ?: return emptyList()
                            listOf(ParseResult.Content(text))
                        }
                        "thinking_delta" -> {
                            val thinking = delta.get("thinking")?.asString ?: return emptyList()
                            listOf(ParseResult.Thinking(thinking))
                        }
                        "input_json_delta" -> {
                            val partialJson = delta.get("partial_json")?.asString ?: return emptyList()
                            toolCallBuffers.getOrPut(index) { ToolCallBuffer() }.arguments.append(partialJson)
                            emptyList()
                        }
                        else -> emptyList()
                    }
                }
                "content_block_stop" -> {
                    val index = jsonObject.get("index")?.asInt ?: 0
                    flushToolCall(index)?.let { listOf(it) } ?: emptyList()
                }
                "message_stop" -> {
                    flushToolCalls() + ParseResult.Done
                }
                "message_delta" -> {
                    // Check for stop reason
                    val delta = jsonObject.getAsJsonObject("delta")
                    if (delta?.has("stop_reason") == true && !delta.get("stop_reason").isJsonNull) {
                        flushToolCalls() + ParseResult.Done
                    } else {
                        emptyList()
                    }
                }
                "error" -> {
                    val error = jsonObject.getAsJsonObject("error")
                    val message = error?.get("message")?.asString ?: "Unknown Anthropic error"
                    listOf(ParseResult.Error(message))
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun flushToolCalls(): List<ParseResult.ToolCall> {
        val calls = toolCallBuffers.keys.sorted().mapNotNull { flushToolCall(it) }
        toolCallBuffers.clear()
        return calls
    }

    private fun flushToolCall(index: Int): ParseResult.ToolCall? {
        val buffer = toolCallBuffers.remove(index) ?: return null
        if (buffer.name.isBlank()) return null
        val id = buffer.id.ifBlank { "tool_call_$index" }
        val args = buffer.arguments.toString().ifBlank { "{}" }
        return ParseResult.ToolCall(ToolCall(id = id, name = buffer.name, arguments = args))
    }

    /**
     * Parse a non-streaming response.
     */
    fun parseFullResponse(responseBody: String, apiType: ApiType = ApiType.OPENAI): String? {
        return try {
            val jsonObject = JsonParser.parseString(responseBody).asJsonObject

            when (apiType) {
                ApiType.OPENAI -> {
                    val choices = jsonObject.getAsJsonArray("choices")
                    if (choices != null && choices.size() > 0) {
                        val message = choices[0].asJsonObject.getAsJsonObject("message")
                        message?.get("content")?.asString
                    } else null
                }
                ApiType.ANTHROPIC -> {
                    val content = jsonObject.getAsJsonArray("content")
                    if (content != null && content.size() > 0) {
                        // Find the text block (skip thinking blocks)
                        content.map { it.asJsonObject }
                            .filter { it.get("type")?.asString == "text" }
                            .joinToString("") { it.get("text")?.asString ?: "" }
                            .ifEmpty { null }
                    } else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reset parser state between requests.
     */
    fun reset() {
        toolCallBuffers.clear()
    }

    sealed class ParseResult {
        data class Content(val text: String) : ParseResult()
        data class Thinking(val text: String) : ParseResult()
        data class ToolCall(val toolCall: com.easyhomework.app.tools.ToolCall) : ParseResult()
        data class Error(val message: String) : ParseResult()
        object Done : ParseResult()
    }
}

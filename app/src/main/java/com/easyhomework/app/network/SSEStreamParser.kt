package com.easyhomework.app.network

import com.easyhomework.app.model.ApiType
import com.google.gson.JsonParser

/**
 * Parses Server-Sent Events (SSE) stream from OpenAI and Anthropic APIs.
 * Robust handling of non-standard lines, comments, and edge cases.
 */
class SSEStreamParser {

    /**
     * Parse a single SSE line for OpenAI-compatible APIs.
     * Silently skips any non-parseable lines instead of emitting errors.
     */
    fun parseLine(line: String, apiType: ApiType = ApiType.OPENAI): ParseResult {
        val trimmed = line.trim()

        // Skip empty lines, SSE comments (":"), and non-data fields ("event:", "id:", "retry:")
        if (trimmed.isEmpty() || trimmed.startsWith(":") ||
            trimmed.startsWith("event:") || trimmed.startsWith("id:") ||
            trimmed.startsWith("retry:")
        ) {
            return ParseResult.Skip
        }

        // Must start with "data:" (with or without space after colon)
        if (!trimmed.startsWith("data:")) {
            return ParseResult.Skip
        }

        // Extract data payload - handle both "data: {...}" and "data:{...}"
        val data = trimmed.removePrefix("data:").trim()

        // Check for stream end signal
        if (data == "[DONE]") {
            return ParseResult.Done
        }

        // Empty data field
        if (data.isEmpty()) {
            return ParseResult.Skip
        }

        // Must be valid JSON (starts with '{')
        if (!data.startsWith("{")) {
            return ParseResult.Skip
        }

        return when (apiType) {
            ApiType.OPENAI -> parseOpenAIData(data)
            ApiType.ANTHROPIC -> parseAnthropicData(data)
        }
    }

    /**
     * Parse OpenAI-format streaming data chunk.
     */
    private fun parseOpenAIData(data: String): ParseResult {
        return try {
            val jsonObject = JsonParser.parseString(data).asJsonObject

            val choices = jsonObject.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) {
                return ParseResult.Skip
            }

            val choice = choices[0].asJsonObject
            val delta = choice.getAsJsonObject("delta") ?: return ParseResult.Skip

            // Check for reasoning/thinking content first (for models like o1, deepseek-r1)
            if (delta.has("reasoning_content")) {
                val reasoning = delta.get("reasoning_content")
                if (!reasoning.isJsonNull) {
                    return ParseResult.Thinking(reasoning.asString)
                }
            }

            // Check for regular content
            if (delta.has("content")) {
                val content = delta.get("content")
                if (!content.isJsonNull) {
                    return ParseResult.Content(content.asString)
                }
            }

            // Check for finish reason
            if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull) {
                return ParseResult.Done
            }

            ParseResult.Skip
        } catch (e: Exception) {
            // Silently skip unparseable data instead of emitting errors
            ParseResult.Skip
        }
    }

    /**
     * Parse Anthropic-format streaming data chunk.
     */
    private fun parseAnthropicData(data: String): ParseResult {
        return try {
            val jsonObject = JsonParser.parseString(data).asJsonObject
            val type = jsonObject.get("type")?.asString ?: return ParseResult.Skip

            when (type) {
                "content_block_delta" -> {
                    val delta = jsonObject.getAsJsonObject("delta") ?: return ParseResult.Skip
                    val deltaType = delta.get("type")?.asString

                    when (deltaType) {
                        "text_delta" -> {
                            val text = delta.get("text")?.asString ?: return ParseResult.Skip
                            ParseResult.Content(text)
                        }
                        "thinking_delta" -> {
                            val thinking = delta.get("thinking")?.asString ?: return ParseResult.Skip
                            ParseResult.Thinking(thinking)
                        }
                        else -> ParseResult.Skip
                    }
                }
                "message_stop" -> ParseResult.Done
                "message_delta" -> {
                    // Check for stop reason
                    val delta = jsonObject.getAsJsonObject("delta")
                    if (delta?.has("stop_reason") == true && !delta.get("stop_reason").isJsonNull) {
                        ParseResult.Done
                    } else {
                        ParseResult.Skip
                    }
                }
                "error" -> {
                    val error = jsonObject.getAsJsonObject("error")
                    val message = error?.get("message")?.asString ?: "Unknown Anthropic error"
                    ParseResult.Error(message)
                }
                else -> ParseResult.Skip
            }
        } catch (e: Exception) {
            ParseResult.Skip
        }
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

    sealed class ParseResult {
        data class Content(val text: String) : ParseResult()
        data class Thinking(val text: String) : ParseResult()
        data class Error(val message: String) : ParseResult()
        object Done : ParseResult()
        object Skip : ParseResult()
    }
}

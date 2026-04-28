package com.easyhomework.app.network

import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * Parses Server-Sent Events (SSE) stream from OpenAI-compatible APIs.
 */
class SSEStreamParser {

    private val gson = Gson()

    /**
     * Parse a single SSE line and extract the delta content.
     * Returns null if the line is not a data event or is [DONE].
     */
    fun parseLine(line: String): ParseResult {
        val trimmed = line.trim()

        // Skip empty lines and comments
        if (trimmed.isEmpty() || trimmed.startsWith(":")) {
            return ParseResult.Skip
        }

        // Check for data prefix
        if (!trimmed.startsWith("data: ")) {
            return ParseResult.Skip
        }

        val data = trimmed.removePrefix("data: ").trim()

        // Check for stream end signal
        if (data == "[DONE]") {
            return ParseResult.Done
        }

        // Parse JSON and extract delta content
        return try {
            val jsonObject = JsonParser.parseString(data).asJsonObject
            val choices = jsonObject.getAsJsonArray("choices")
            if (choices != null && choices.size() > 0) {
                val choice = choices[0].asJsonObject
                val delta = choice.getAsJsonObject("delta")
                if (delta != null && delta.has("content")) {
                    val content = delta.get("content").asString
                    ParseResult.Content(content)
                } else {
                    // Check for finish reason
                    if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull) {
                        ParseResult.Done
                    } else {
                        ParseResult.Skip
                    }
                }
            } else {
                ParseResult.Skip
            }
        } catch (e: Exception) {
            ParseResult.Error("Failed to parse SSE data: ${e.message}")
        }
    }

    /**
     * Parse a non-streaming response and extract the full content.
     */
    fun parseFullResponse(responseBody: String): String? {
        return try {
            val jsonObject = JsonParser.parseString(responseBody).asJsonObject
            val choices = jsonObject.getAsJsonArray("choices")
            if (choices != null && choices.size() > 0) {
                val choice = choices[0].asJsonObject
                val message = choice.getAsJsonObject("message")
                message?.get("content")?.asString
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    sealed class ParseResult {
        data class Content(val text: String) : ParseResult()
        data class Error(val message: String) : ParseResult()
        object Done : ParseResult()
        object Skip : ParseResult()
    }
}

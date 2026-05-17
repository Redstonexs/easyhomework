package com.easyhomework.app.network

import com.easyhomework.app.model.ApiType
import com.easyhomework.app.tools.ToolCall
import com.google.gson.JsonElement
import com.google.gson.JsonObject
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
        val arguments: StringBuilder = StringBuilder(),
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

        // Extract data payload - handle SSE and NDJSON-style streaming.
        val data = when {
            trimmed.startsWith("data:") -> trimmed.removePrefix("data:").trim()
            trimmed.startsWith("{") -> trimmed
            else -> return emptyList()
        }

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
            val topLevelResults = parseOpenAITopLevelData(jsonObject)
            if (topLevelResults.isNotEmpty()) {
                return topLevelResults
            }

            val choices = jsonObject.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) {
                return emptyList()
            }

            val choice = choices[0].asJsonObject
            val delta = objectValue(choice.get("delta"))
            val results = mutableListOf<ParseResult>()

            if (delta != null) {
                // Check for reasoning/thinking content (for models like o1, deepseek-r1)
                textValue(delta.get("reasoning_content"))
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { results.add(ParseResult.Thinking(it)) }

                // Some domestic OpenAI-compatible APIs use "reasoning" instead.
                textValue(delta.get("reasoning"))
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { results.add(ParseResult.Thinking(it)) }

                // Check for regular content (may coexist with reasoning/tool calls)
                textValue(delta.get("content"))
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { results.add(ParseResult.Content(it)) }

                // Check for tool calls
                if (delta.has("tool_calls")) {
                    val toolCalls = arrayValue(delta.get("tool_calls")).orEmpty()
                    for ((fallbackIndex, tc) in toolCalls.withIndex()) {
                        val tcObj = objectValue(tc) ?: continue
                        val index = intValue(tcObj.get("index")) ?: fallbackIndex
                        val buffer = toolCallBuffers.getOrPut(index) { ToolCallBuffer() }

                        val id = textValue(tcObj.get("id")).orEmpty()
                        if (id.isNotBlank()) buffer.id = id

                        val function = objectValue(tcObj.get("function"))
                        val name = textValue(function?.get("name")).orEmpty()
                        if (name.isNotBlank()) buffer.name = name

                        val arguments = argumentValue(function?.get("arguments"))
                        if (arguments.isNotEmpty()) {
                            buffer.arguments.append(arguments)
                        }
                    }
                }

                // Older OpenAI-compatible servers may stream a single function_call
                // instead of tool_calls. Treat it as one normal tool call.
                if (delta.has("function_call")) {
                    val function = objectValue(delta.get("function_call"))
                    if (function != null) {
                        val buffer = toolCallBuffers.getOrPut(0) { ToolCallBuffer() }
                        if (buffer.id.isBlank()) buffer.id = "tool_call_0"

                        val name = textValue(function.get("name")).orEmpty()
                        if (name.isNotBlank()) buffer.name = name

                        val arguments = argumentValue(function.get("arguments"))
                        if (arguments.isNotEmpty()) buffer.arguments.append(arguments)
                    }
                }
            }

            // Some OpenAI-compatible gateways ignore stream=true and send a full
            // chat choice inside an SSE data frame instead of delta chunks.
            val message = choice.getAsJsonObject("message")
            textValue(message?.get("reasoning_content"))
                ?.takeIf { it.isNotEmpty() }
                ?.let { results.add(ParseResult.Thinking(it)) }
            textValue(message?.get("reasoning"))
                ?.takeIf { it.isNotEmpty() }
                ?.let { results.add(ParseResult.Thinking(it)) }
            textValue(message?.get("content"))
                ?.takeIf { it.isNotEmpty() }
                ?.let { results.add(ParseResult.Content(it)) }

            // Legacy completions-style streaming uses choices[].text.
            textValue(choice.get("text"))
                ?.takeIf { it.isNotEmpty() }
                ?.let { results.add(ParseResult.Content(it)) }

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

    private fun parseOpenAITopLevelData(jsonObject: JsonObject): List<ParseResult> {
        val results = mutableListOf<ParseResult>()
        val type = textValue(jsonObject.get("type")).orEmpty()

        appendTopLevelToolEvents(jsonObject, results)

        when {
            type == "response.output_text.delta" || type == "response.text.delta" -> {
                textValue(jsonObject.get("delta"))
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { results.add(ParseResult.Content(it)) }
            }
            type.contains("reasoning") && type.endsWith(".delta") -> {
                textValue(jsonObject.get("delta"))
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { results.add(ParseResult.Thinking(it)) }
            }
            type == "response.completed" -> {
                textValue(jsonObject.get("response"))
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { results.add(ParseResult.Content(it)) }
                results.addAll(flushToolCalls())
                results.add(ParseResult.Done)
            }
            type == "response.output_item.done" || type == "response.function_call_arguments.done" -> {
                results.addAll(flushToolCalls())
            }
            type == "response.failed" || type == "error" -> {
                val message = textValue(jsonObject.get("error"))
                    ?: textValue(jsonObject.get("message"))
                    ?: "Unknown OpenAI-compatible error"
                results.add(ParseResult.Error(message))
            }
        }

        if (results.isEmpty() && !jsonObject.has("choices")) {
            val fallbackText = textValue(jsonObject.get("answer"))
                ?: textValue(jsonObject.get("content"))
                ?: textValue(jsonObject.get("text"))
                ?: textValue(jsonObject.get("response"))
                ?: textValue(jsonObject.get("output_text"))
                ?: textValue(jsonObject.get("delta"))
            fallbackText?.takeIf { it.isNotEmpty() }?.let { results.add(ParseResult.Content(it)) }
        }

        return results
    }

    private fun appendTopLevelToolEvents(jsonObject: JsonObject, results: MutableList<ParseResult>) {
        arrayValue(jsonObject.get("tool_calls"))?.forEachIndexed { index, item ->
            parseCompleteToolCall(item, index)?.let { results.add(ParseResult.ToolCall(it)) }
        }

        objectValue(jsonObject.get("function_call"))?.let { function ->
            val name = textValue(function.get("name")).orEmpty()
            if (name.isNotBlank()) {
                results.add(
                    ParseResult.ToolCall(
                        ToolCall(
                            id = textValue(jsonObject.get("id")) ?: "tool_call_0",
                            name = name,
                            arguments = argumentValue(function.get("arguments")).ifBlank { "{}" },
                        ),
                    ),
                )
            }
        }

        val type = textValue(jsonObject.get("type")).orEmpty()
        val outputIndex = intValue(jsonObject.get("output_index")) ?: intValue(jsonObject.get("item_index")) ?: 0

        objectValue(jsonObject.get("item"))
            ?.takeIf { textValue(it.get("type")) == "function_call" }
            ?.let { item ->
                val buffer = toolCallBuffers.getOrPut(outputIndex) { ToolCallBuffer() }
                val id = textValue(item.get("call_id")) ?: textValue(item.get("id"))
                if (!id.isNullOrBlank()) buffer.id = id
                textValue(item.get("name"))?.let { if (it.isNotBlank()) buffer.name = it }
                argumentValue(item.get("arguments")).takeIf { it.isNotBlank() }?.let { buffer.arguments.append(it) }
            }

        if (type == "response.function_call_arguments.delta") {
            argumentValue(jsonObject.get("delta")).takeIf { it.isNotBlank() }?.let {
                toolCallBuffers.getOrPut(outputIndex) { ToolCallBuffer() }.arguments.append(it)
            }
        }
    }

    private fun parseCompleteToolCall(element: JsonElement, fallbackIndex: Int): ToolCall? {
        val toolCallObj = objectValue(element) ?: return null
        val function = objectValue(toolCallObj.get("function")) ?: toolCallObj
        val name = textValue(function.get("name")).orEmpty()
        if (name.isBlank()) return null

        return ToolCall(
            id = textValue(toolCallObj.get("id")) ?: textValue(toolCallObj.get("call_id")) ?: "tool_call_$fallbackIndex",
            name = name,
            arguments = argumentValue(function.get("arguments")).ifBlank { "{}" },
        )
    }

    private fun objectValue(element: JsonElement?): JsonObject? {
        return if (element != null && element.isJsonObject) element.asJsonObject else null
    }

    private fun arrayValue(element: JsonElement?): List<JsonElement>? {
        return if (element != null && element.isJsonArray) element.asJsonArray.toList() else null
    }

    private fun intValue(element: JsonElement?): Int? {
        return if (element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
            element.asInt
        } else {
            null
        }
    }

    private fun argumentValue(element: JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        return if (element.isJsonPrimitive) element.asString else element.toString()
    }

    private fun textValue(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null

        return when {
            element.isJsonPrimitive -> element.asString
            element.isJsonArray ->
                element.asJsonArray
                    .mapNotNull { item -> textValue(item) }
                    .joinToString("")
                    .ifEmpty { null }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                textValue(obj.get("text"))
                    ?: textValue(obj.get("content"))
                    ?: textValue(obj.get("output_text"))
                    ?: textValue(obj.get("answer"))
                    ?: textValue(obj.get("response"))
                    ?: textValue(obj.get("message"))
                    ?: textValue(obj.get("output"))
                    ?: textValue(obj.get("delta"))
            }
            else -> null
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
                    } else {
                        null
                    }
                }
                ApiType.ANTHROPIC -> {
                    val content = jsonObject.getAsJsonArray("content")
                    if (content != null && content.size() > 0) {
                        // Find the text block (skip thinking blocks)
                        content.map { it.asJsonObject }
                            .filter { it.get("type")?.asString == "text" }
                            .joinToString("") { it.get("text")?.asString ?: "" }
                            .ifEmpty { null }
                    } else {
                        null
                    }
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

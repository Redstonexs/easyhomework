package com.easyhomework.app.network

import android.graphics.Bitmap
import android.util.Base64
import com.easyhomework.app.model.ApiType
import com.easyhomework.app.model.ChatMessage
import com.easyhomework.app.model.LLMConfig
import com.easyhomework.app.model.ModelInfo
import com.easyhomework.app.model.ThinkingDepth
import com.easyhomework.app.tools.ToolCall
import com.easyhomework.app.tools.ToolDefinition
import com.easyhomework.app.tools.ToolRegistry
import com.easyhomework.app.tools.ToolResult
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Repository for LLM API calls with streaming support.
 * Supports both OpenAI-compatible and Anthropic APIs.
 * Supports vision models with image input.
 * Supports function calling / tool use.
 */
class LLMRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val sseParser = SSEStreamParser()

    /**
     * Send a streaming chat completion request.
     */
    fun streamChatCompletion(
        config: LLMConfig,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null
    ): Flow<StreamEvent> = flow {
        emit(StreamEvent.Started)

        sseParser.reset()
        val requestBody = buildRequestBody(config, messages, stream = true, tools = tools)
        val request = buildRequest(config, requestBody)

        try {
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                emit(StreamEvent.Error("API Error (${response.code}): $errorBody"))
                return@flow
            }

            val reader = BufferedReader(
                InputStreamReader(response.body?.byteStream() ?: run {
                    emit(StreamEvent.Error("Empty response body"))
                    return@flow
                })
            )

            reader.use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    when (val result = sseParser.parseLine(line!!, config.apiType)) {
                        is SSEStreamParser.ParseResult.Content -> {
                            emit(StreamEvent.Token(result.text))
                        }
                        is SSEStreamParser.ParseResult.Thinking -> {
                            emit(StreamEvent.Thinking(result.text))
                        }
                        is SSEStreamParser.ParseResult.ToolCall -> {
                            emit(StreamEvent.ToolCall(result.toolCall))
                        }
                        is SSEStreamParser.ParseResult.ToolCalls -> {
                            for (tc in result.toolCalls) {
                                emit(StreamEvent.ToolCall(tc))
                            }
                        }
                        is SSEStreamParser.ParseResult.Done -> {
                            break
                        }
                        is SSEStreamParser.ParseResult.Error -> {
                            emit(StreamEvent.Error(result.message))
                        }
                        is SSEStreamParser.ParseResult.Skip -> {
                            // Silently skip
                        }
                    }
                }
            }

            emit(StreamEvent.Completed)
        } catch (e: Exception) {
            emit(StreamEvent.Error("Network error: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Send a non-streaming chat completion request.
     */
    suspend fun chatCompletion(
        config: LLMConfig,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildRequestBody(config, messages, stream = false, tools = tools)
            val request = buildRequest(config, requestBody)
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                return@withContext Result.failure(Exception("API Error (${response.code}): $errorBody"))
            }

            val responseBody = response.body?.string() ?: return@withContext Result.failure(
                Exception("Empty response body")
            )

            val chatResponse = parseFullResponse(responseBody, config.apiType)
                ?: return@withContext Result.failure(Exception("Failed to parse response"))

            Result.success(chatResponse)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch available models from the API with capability information.
     */
    suspend fun fetchModels(config: LLMConfig): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = Request.Builder()
                .url(config.getModelsUrl())
                .get()

            when (config.apiType) {
                ApiType.OPENAI -> {
                    requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey}")
                }
                ApiType.ANTHROPIC -> {
                    requestBuilder.addHeader("x-api-key", config.apiKey)
                    requestBuilder.addHeader("anthropic-version", "2023-06-01")
                }
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to fetch models (${response.code})")
                )
            }

            val body = response.body?.string() ?: return@withContext Result.failure(
                Exception("Empty response")
            )

            val models = parseModelsResponse(body, config.apiType)
            Result.success(models)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseModelsResponse(body: String, apiType: ApiType): List<ModelInfo> {
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val dataArray = json.getAsJsonArray("data") ?: return emptyList()

            dataArray.map { it.asJsonObject }
                .map { modelJson ->
                    val id = modelJson.get("id")?.asString ?: return@map null
                    val supportsVision = detectVisionCapability(modelJson, apiType)
                    val supportsFunctionCalling = detectFunctionCallingCapability(modelJson, apiType, id)
                    val supportsThinking = detectThinkingCapability(modelJson, apiType, id)
                    ModelInfo(
                        id = id,
                        supportsVision = supportsVision,
                        supportsFunctionCalling = supportsFunctionCalling,
                        supportsThinking = supportsThinking
                    )
                }
                .filterNotNull()
                .sortedBy { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Detect vision capability from model metadata in API response.
     */
    private fun detectVisionCapability(modelJson: com.google.gson.JsonObject, apiType: ApiType): Boolean {
        // Check explicit capabilities field (OpenAI style)
        val capabilities = modelJson.getAsJsonObject("capabilities")
        if (capabilities != null) {
            if (capabilities.has("vision") && capabilities.get("vision").asBoolean) {
                return true
            }
        }

        // Check modalities field
        val modalities = modelJson.getAsJsonArray("modalities")
        if (modalities != null) {
            for (modality in modalities) {
                if (modality.asString == "image" || modality.asString == "vision") {
                    return true
                }
            }
        }

        // Check input_types field
        val inputTypes = modelJson.getAsJsonArray("input_types")
        if (inputTypes != null) {
            for (inputType in inputTypes) {
                if (inputType.asString == "image" || inputType.asString == "vision") {
                    return true
                }
            }
        }

        // For Anthropic, all Claude 3+ models support vision
        if (apiType == ApiType.ANTHROPIC) {
            val id = modelJson.get("id")?.asString?.lowercase() ?: ""
            if (id.contains("claude-3") || id.contains("claude-sonnet-4") || id.contains("claude-opus-4")) {
                return true
            }
        }

        return false
    }

    /**
     * Detect function calling capability from model metadata.
     */
    private fun detectFunctionCallingCapability(modelJson: com.google.gson.JsonObject, apiType: ApiType, modelId: String): Boolean {
        // Check explicit capabilities field
        val capabilities = modelJson.getAsJsonObject("capabilities")
        if (capabilities != null) {
            if (capabilities.has("function_calling") && capabilities.get("function_calling").asBoolean) {
                return true
            }
            if (capabilities.has("tool_use") && capabilities.get("tool_use").asBoolean) {
                return true
            }
        }

        // Check supported_features field
        val features = modelJson.getAsJsonArray("supported_features")
        if (features != null) {
            for (feature in features) {
                val f = feature.asString.lowercase()
                if (f == "function_calling" || f == "tool_use" || f == "tools") {
                    return true
                }
            }
        }

        // Model name based detection as fallback
        val lower = modelId.lowercase()

        // OpenAI models that support function calling
        if (lower.contains("gpt-4") || lower.contains("gpt-3.5-turbo") ||
            lower.contains("o1") || lower.contains("o3") || lower.contains("o4")) {
            return true
        }

        // Anthropic Claude models (all Claude 3+ support tool use)
        if (apiType == ApiType.ANTHROPIC) {
            if (lower.contains("claude-3") || lower.contains("claude-sonnet-4") || lower.contains("claude-opus-4")) {
                return true
            }
        }

        // Other models that typically support function calling
        val functionCallingPatterns = listOf(
            "gemini-1.5", "gemini-2", "gemini-pro",
            "qwen-max", "qwen-plus", "qwen-turbo",
            "deepseek-chat", "deepseek-coder",
            "glm-4", "glm-3",
            "mistral", "mixtral"
        )

        return functionCallingPatterns.any { lower.contains(it) }
    }

    /**
     * Detect thinking/reasoning capability from model metadata.
     */
    private fun detectThinkingCapability(modelJson: com.google.gson.JsonObject, apiType: ApiType, modelId: String): Boolean {
        // Check explicit capabilities field
        val capabilities = modelJson.getAsJsonObject("capabilities")
        if (capabilities != null) {
            if (capabilities.has("reasoning") && capabilities.get("reasoning").asBoolean) {
                return true
            }
            if (capabilities.has("thinking") && capabilities.get("thinking").asBoolean) {
                return true
            }
        }

        // Model name based detection
        val lower = modelId.lowercase()

        // OpenAI reasoning models
        if (lower.contains("o1") || lower.contains("o3") || lower.contains("o4")) {
            return true
        }

        // Anthropic Claude with extended thinking
        if (apiType == ApiType.ANTHROPIC) {
            if (lower.contains("claude-3") || lower.contains("claude-sonnet-4") || lower.contains("claude-opus-4")) {
                return true
            }
        }

        // DeepSeek reasoning models
        if (lower.contains("deepseek-r1") || lower.contains("deepseek-reasoner")) {
            return true
        }

        return false
    }

    // ---- Response Parsing ----

    data class ChatResponse(
        val content: String?,
        val toolCalls: List<ToolCall>?,
        val thinking: String? = null
    )

    private fun parseFullResponse(body: String, apiType: ApiType): ChatResponse? {
        return try {
            val json = JsonParser.parseString(body).asJsonObject

            when (apiType) {
                ApiType.OPENAI -> parseOpenAIResponse(json)
                ApiType.ANTHROPIC -> parseAnthropicResponse(json)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseOpenAIResponse(json: com.google.gson.JsonObject): ChatResponse? {
        val choices = json.getAsJsonArray("choices") ?: return null
        if (choices.size() == 0) return null

        val message = choices[0].asJsonObject.getAsJsonObject("message") ?: return null
        val content = message.get("content")?.asString

        // Parse tool calls
        val toolCallsArray = message.getAsJsonArray("tool_calls")
        val toolCalls = toolCallsArray?.map { tc ->
            val function = tc.asJsonObject.getAsJsonObject("function")
            ToolCall(
                id = tc.asJsonObject.get("id")?.asString ?: "",
                name = function?.get("name")?.asString ?: "",
                arguments = function?.get("arguments")?.asString ?: "{}"
            )
        }

        return ChatResponse(content = content, toolCalls = toolCalls)
    }

    private fun parseAnthropicResponse(json: com.google.gson.JsonObject): ChatResponse? {
        val content = json.getAsJsonArray("content") ?: return null

        var textContent: String? = null
        var thinkingContent: String? = null
        val toolCalls = mutableListOf<ToolCall>()

        for (block in content) {
            val blockObj = block.asJsonObject
            val type = blockObj.get("type")?.asString

            when (type) {
                "text" -> textContent = blockObj.get("text")?.asString
                "thinking" -> thinkingContent = blockObj.get("thinking")?.asString
                "tool_use" -> {
                    toolCalls.add(ToolCall(
                        id = blockObj.get("id")?.asString ?: "",
                        name = blockObj.get("name")?.asString ?: "",
                        arguments = blockObj.getAsJsonObject("input")?.toString() ?: "{}"
                    ))
                }
            }
        }

        return ChatResponse(content = textContent, toolCalls = toolCalls, thinking = thinkingContent)
    }

    // ---- Request Building ----

    private fun buildRequest(config: LLMConfig, body: String): Request {
        val builder = Request.Builder()
            .url(config.getFullUrl())
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))

        when (config.apiType) {
            ApiType.OPENAI -> {
                builder.addHeader("Authorization", "Bearer ${config.apiKey}")
                builder.addHeader("Accept", "text/event-stream")
            }
            ApiType.ANTHROPIC -> {
                builder.addHeader("x-api-key", config.apiKey)
                builder.addHeader("anthropic-version", "2023-06-01")
            }
        }

        return builder.build()
    }

    private fun buildRequestBody(
        config: LLMConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
        tools: List<ToolDefinition>? = null
    ): String {
        return when (config.apiType) {
            ApiType.OPENAI -> buildOpenAIBody(config, messages, stream, tools)
            ApiType.ANTHROPIC -> buildAnthropicBody(config, messages, stream, tools)
        }
    }

    private fun buildOpenAIBody(
        config: LLMConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
        tools: List<ToolDefinition>? = null
    ): String {
        val apiMessages = mutableListOf<Map<String, Any>>()

        if (config.systemPrompt.isNotBlank()) {
            apiMessages.add(mapOf("role" to "system", "content" to config.systemPrompt))
        }

        messages.filter { it.role != ChatMessage.ROLE_SYSTEM }.forEach { msg ->
            if (msg.imageBitmap != null && config.supportsVision) {
                // Multimodal message with image
                val content = mutableListOf<Map<String, Any>>()
                content.add(mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf(
                        "url" to "data:image/jpeg;base64,${bitmapToBase64(msg.imageBitmap)}",
                        "detail" to "high"
                    )
                ))
                if (msg.content.isNotBlank()) {
                    content.add(mapOf("type" to "text", "text" to msg.content))
                }
                apiMessages.add(mapOf("role" to msg.role, "content" to content))
            } else if (msg.toolCalls != null) {
                // Assistant message with tool calls
                val toolCallsMap = msg.toolCalls.map { tc ->
                    mapOf(
                        "id" to tc.id,
                        "type" to "function",
                        "function" to mapOf(
                            "name" to tc.name,
                            "arguments" to tc.arguments
                        )
                    )
                }
                apiMessages.add(mapOf(
                    "role" to "assistant",
                    "content" to msg.content,
                    "tool_calls" to toolCallsMap
                ))
            } else if (msg.toolCallId != null) {
                // Tool result message
                apiMessages.add(mapOf(
                    "role" to "tool",
                    "tool_call_id" to msg.toolCallId,
                    "content" to msg.content
                ))
            } else {
                apiMessages.add(mapOf("role" to msg.role, "content" to msg.content))
            }
        }

        val body = mutableMapOf<String, Any>(
            "model" to config.modelName,
            "messages" to apiMessages,
            "max_tokens" to config.maxTokens,
            "stream" to stream
        )

        // Only add temperature for non-thinking models (o1/o3 don't support it)
        if (!config.thinkingEnabled || config.thinkingDepth == ThinkingDepth.NONE) {
            body["temperature"] = config.temperature
        }

        // Add reasoning effort for OpenAI-compatible thinking models
        if (config.thinkingEnabled && config.thinkingDepth != ThinkingDepth.NONE) {
            body["reasoning_effort"] = config.thinkingDepth.openaiReasoningEffort
        }

        // Add tools if provided
        if (!tools.isNullOrEmpty()) {
            body["tools"] = tools.map { it.toJson() }
            body["tool_choice"] = "auto"
        }

        return gson.toJson(body)
    }

    private fun buildAnthropicBody(
        config: LLMConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
        tools: List<ToolDefinition>? = null
    ): String {
        val apiMessages = mutableListOf<Map<String, Any>>()

        messages.filter { it.role != ChatMessage.ROLE_SYSTEM }.forEach { msg ->
            if (msg.imageBitmap != null && config.supportsVision) {
                // Multimodal message with image for Anthropic
                val content = mutableListOf<Map<String, Any>>()
                content.add(mapOf(
                    "type" to "image",
                    "source" to mapOf(
                        "type" to "base64",
                        "media_type" to "image/jpeg",
                        "data" to bitmapToBase64(msg.imageBitmap)
                    )
                ))
                if (msg.content.isNotBlank()) {
                    content.add(mapOf("type" to "text", "text" to msg.content))
                }
                apiMessages.add(mapOf("role" to msg.role, "content" to content))
            } else if (msg.toolCalls != null) {
                // Assistant message with tool use
                val content = mutableListOf<Map<String, Any>>()
                msg.toolCalls.forEach { tc ->
                    content.add(mapOf(
                        "type" to "tool_use",
                        "id" to tc.id,
                        "name" to tc.name,
                        "input" to JsonParser.parseString(tc.arguments).asJsonObject
                    ))
                }
                apiMessages.add(mapOf("role" to "assistant", "content" to content))
            } else if (msg.toolCallId != null) {
                // Tool result message
                apiMessages.add(mapOf(
                    "role" to "user",
                    "content" to listOf(mapOf(
                        "type" to "tool_result",
                        "tool_use_id" to msg.toolCallId,
                        "content" to msg.content
                    ))
                ))
            } else {
                apiMessages.add(mapOf("role" to msg.role, "content" to msg.content))
            }
        }

        val body = mutableMapOf<String, Any>(
            "model" to config.modelName,
            "messages" to apiMessages,
            "max_tokens" to config.maxTokens,
            "stream" to stream
        )

        // System prompt for Anthropic is a top-level field
        if (config.systemPrompt.isNotBlank()) {
            body["system"] = config.systemPrompt
        }

        // Only add temperature when thinking is disabled or depth is NONE
        if (!config.thinkingEnabled || config.thinkingDepth == ThinkingDepth.NONE) {
            body["temperature"] = config.temperature
        }

        // Extended thinking for Anthropic
        if (config.thinkingEnabled && config.thinkingDepth != ThinkingDepth.NONE) {
            body["thinking"] = mapOf(
                "type" to "enabled",
                "budget_tokens" to config.thinkingDepth.budgetTokens
            )
        }

        // Add tools if provided
        if (!tools.isNullOrEmpty()) {
            body["tools"] = tools.map { tool ->
                mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "input_schema" to tool.parameters
                )
            }
        }

        return gson.toJson(body)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // Scale down large images to reduce payload size
        val maxSize = 1024
        val scale = if (bitmap.width > maxSize || bitmap.height > maxSize) {
            minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height)
        } else {
            1f
        }
        val scaledBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else {
            bitmap
        }
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    sealed class StreamEvent {
        object Started : StreamEvent()
        data class Token(val text: String) : StreamEvent()
        data class Thinking(val text: String) : StreamEvent()
        data class ToolCall(val toolCall: com.easyhomework.app.tools.ToolCall) : StreamEvent()
        object Completed : StreamEvent()
        data class Error(val message: String) : StreamEvent()
    }
}

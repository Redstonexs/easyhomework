package com.easyhomework.app.network

import android.graphics.Bitmap
import android.util.Base64
import com.easyhomework.app.model.ApiType
import com.easyhomework.app.model.ChatMessage
import com.easyhomework.app.model.LLMConfig
import com.easyhomework.app.model.ModelInfo
import com.easyhomework.app.model.ThinkingDepth
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
        messages: List<ChatMessage>
    ): Flow<StreamEvent> = flow {
        emit(StreamEvent.Started)

        val requestBody = buildRequestBody(config, messages, stream = true)
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
        messages: List<ChatMessage>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildRequestBody(config, messages, stream = false)
            val request = buildRequest(config, requestBody)
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                return@withContext Result.failure(Exception("API Error (${response.code}): $errorBody"))
            }

            val responseBody = response.body?.string() ?: return@withContext Result.failure(
                Exception("Empty response body")
            )

            val content = sseParser.parseFullResponse(responseBody, config.apiType)
                ?: return@withContext Result.failure(Exception("Failed to parse response"))

            Result.success(content)
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
                    ModelInfo(id = id, supportsVision = supportsVision)
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
        stream: Boolean
    ): String {
        return when (config.apiType) {
            ApiType.OPENAI -> buildOpenAIBody(config, messages, stream)
            ApiType.ANTHROPIC -> buildAnthropicBody(config, messages, stream)
        }
    }

    private fun buildOpenAIBody(
        config: LLMConfig,
        messages: List<ChatMessage>,
        stream: Boolean
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

        return gson.toJson(body)
    }

    private fun buildAnthropicBody(
        config: LLMConfig,
        messages: List<ChatMessage>,
        stream: Boolean
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
        object Completed : StreamEvent()
        data class Error(val message: String) : StreamEvent()
    }
}

package com.easyhomework.app.network

import com.easyhomework.app.model.ApiType
import com.easyhomework.app.model.ChatMessage
import com.easyhomework.app.model.LLMConfig
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Repository for LLM API calls with streaming support.
 * Supports both OpenAI-compatible and Anthropic APIs.
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
     * Fetch available models from the API.
     */
    suspend fun fetchModels(config: LLMConfig): Result<List<String>> = withContext(Dispatchers.IO) {
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

    private fun parseModelsResponse(body: String, apiType: ApiType): List<String> {
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val dataArray = json.getAsJsonArray("data") ?: return emptyList()

            dataArray.map { it.asJsonObject }
                .mapNotNull { it.get("id")?.asString }
                .sorted()
        } catch (e: Exception) {
            emptyList()
        }
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
        val apiMessages = mutableListOf<Map<String, String>>()

        if (config.systemPrompt.isNotBlank()) {
            apiMessages.add(mapOf("role" to "system", "content" to config.systemPrompt))
        }

        messages.filter { it.role != ChatMessage.ROLE_SYSTEM }.forEach { msg ->
            apiMessages.add(mapOf("role" to msg.role, "content" to msg.content))
        }

        val body = mutableMapOf<String, Any>(
            "model" to config.modelName,
            "messages" to apiMessages,
            "max_tokens" to config.maxTokens,
            "stream" to stream
        )

        // Only add temperature for non-thinking models (o1/o3 don't support it)
        if (!config.thinkingEnabled) {
            body["temperature"] = config.temperature
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
            apiMessages.add(mapOf("role" to msg.role, "content" to msg.content))
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

        // Only add temperature when thinking is disabled
        if (!config.thinkingEnabled) {
            body["temperature"] = config.temperature
        }

        // Extended thinking for Anthropic
        if (config.thinkingEnabled) {
            body["thinking"] = mapOf(
                "type" to "enabled",
                "budget_tokens" to config.thinkingBudget
            )
        }

        return gson.toJson(body)
    }

    sealed class StreamEvent {
        object Started : StreamEvent()
        data class Token(val text: String) : StreamEvent()
        data class Thinking(val text: String) : StreamEvent()
        object Completed : StreamEvent()
        data class Error(val message: String) : StreamEvent()
    }
}

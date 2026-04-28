package com.easyhomework.app.network

import com.easyhomework.app.model.ChatMessage
import com.easyhomework.app.model.LLMConfig
import com.google.gson.Gson
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
     * Send a chat completion request with streaming response.
     * Emits content tokens as they arrive.
     */
    fun streamChatCompletion(
        config: LLMConfig,
        messages: List<ChatMessage>
    ): Flow<StreamEvent> = flow {
        emit(StreamEvent.Started)

        val requestBody = buildRequestBody(config, messages, stream = true)

        val request = Request.Builder()
            .url(config.getFullUrl())
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

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
                    when (val result = sseParser.parseLine(line!!)) {
                        is SSEStreamParser.ParseResult.Content -> {
                            emit(StreamEvent.Token(result.text))
                        }
                        is SSEStreamParser.ParseResult.Done -> {
                            break
                        }
                        is SSEStreamParser.ParseResult.Error -> {
                            emit(StreamEvent.Error(result.message))
                        }
                        is SSEStreamParser.ParseResult.Skip -> {
                            // Continue to next line
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
     * Returns the complete response.
     */
    suspend fun chatCompletion(
        config: LLMConfig,
        messages: List<ChatMessage>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildRequestBody(config, messages, stream = false)

            val request = Request.Builder()
                .url(config.getFullUrl())
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                return@withContext Result.failure(Exception("API Error (${response.code}): $errorBody"))
            }

            val responseBody = response.body?.string() ?: return@withContext Result.failure(
                Exception("Empty response body")
            )

            val content = sseParser.parseFullResponse(responseBody)
                ?: return@withContext Result.failure(Exception("Failed to parse response"))

            Result.success(content)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildRequestBody(
        config: LLMConfig,
        messages: List<ChatMessage>,
        stream: Boolean
    ): String {
        val apiMessages = mutableListOf<Map<String, String>>()

        // Add system prompt
        if (config.systemPrompt.isNotBlank()) {
            apiMessages.add(
                mapOf("role" to "system", "content" to config.systemPrompt)
            )
        }

        // Add conversation messages (skip system messages from our internal format)
        messages.filter { it.role != ChatMessage.ROLE_SYSTEM }.forEach { msg ->
            apiMessages.add(
                mapOf("role" to msg.role, "content" to msg.content)
            )
        }

        val body = mutableMapOf<String, Any>(
            "model" to config.modelName,
            "messages" to apiMessages,
            "temperature" to config.temperature,
            "max_tokens" to config.maxTokens,
            "stream" to stream
        )

        return gson.toJson(body)
    }

    sealed class StreamEvent {
        object Started : StreamEvent()
        data class Token(val text: String) : StreamEvent()
        object Completed : StreamEvent()
        data class Error(val message: String) : StreamEvent()
    }
}

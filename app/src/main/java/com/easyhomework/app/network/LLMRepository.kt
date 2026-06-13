package com.easyhomework.app.network

import android.graphics.Bitmap
import android.util.Base64
import com.easyhomework.app.model.ApiType
import com.easyhomework.app.model.CapabilitySource
import com.easyhomework.app.model.ChatMessage
import com.easyhomework.app.model.LLMConfig
import com.easyhomework.app.model.ModelInfo
import com.easyhomework.app.model.PromptTemplates
import com.easyhomework.app.model.ThinkingDepth
import com.easyhomework.app.tools.ToolCall
import com.easyhomework.app.tools.ToolDefinition
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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

    /**
     * Send a streaming chat completion request.
     * @param scope The coroutine scope - when cancelled, the HTTP connection will be closed.
     */
    fun streamChatCompletion(
        config: LLMConfig,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null,
        scope: CoroutineScope? = null,
    ): Flow<StreamEvent> = flow {
        emit(StreamEvent.Started)

        val sseParser = SSEStreamParser()
        val requestBody = buildRequestBody(config, messages, stream = true, tools = tools)
        val request = buildRequest(config, requestBody)

        var call: Call? = null
        val cancellationHandles = mutableListOf<DisposableHandle>()

        try {
            val currentJob = currentCoroutineContext()[Job]
            currentJob?.let { job ->
                cancellationHandles.add(job.invokeOnCompletion { call?.cancel() })
            }
            scope?.coroutineContext?.get(Job)
                ?.takeIf { it !== currentJob }
                ?.let { job -> cancellationHandles.add(job.invokeOnCompletion { call?.cancel() }) }

            call = client.newCall(request)
            val response = call.execute()

            response.use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string() ?: "Unknown error"
                    emit(StreamEvent.Error("API Error (${resp.code}): $errorBody"))
                    return@flow
                }

                val responseBody = resp.body ?: run {
                    emit(StreamEvent.Error("Empty response body"))
                    return@flow
                }

                val reader = BufferedReader(InputStreamReader(responseBody.byteStream()))

                val fallbackBody = StringBuilder()
                var streamEventReceived = false
                var terminalError = false
                var lineCount = 0
                var payloadCount = 0

                reader.use { r ->
                    var line: String?
                    readLoop@ while (r.readLine().also { line = it } != null) {
                        val currentLine = line.orEmpty()
                        lineCount++
                        if (appendFallbackPayload(fallbackBody, currentLine)) {
                            payloadCount++
                        }

                        for (result in sseParser.parseLine(currentLine, config.apiType)) {
                            when (result) {
                                is SSEStreamParser.ParseResult.Content -> {
                                    streamEventReceived = true
                                    emit(StreamEvent.Token(result.text))
                                }
                                is SSEStreamParser.ParseResult.Thinking -> {
                                    streamEventReceived = true
                                    emit(StreamEvent.Thinking(result.text))
                                }
                                is SSEStreamParser.ParseResult.ToolCall -> {
                                    streamEventReceived = true
                                    emit(StreamEvent.ToolCall(result.toolCall))
                                }
                                is SSEStreamParser.ParseResult.Done -> {
                                    break@readLoop
                                }
                                is SSEStreamParser.ParseResult.Error -> {
                                    terminalError = true
                                    emit(StreamEvent.Error(result.message))
                                    break@readLoop
                                }
                            }
                        }
                    }
                }

                if (terminalError) {
                    return@flow
                }

                if (!streamEventReceived) {
                    when (emitFallbackResponse(fallbackBody.toString(), config.apiType)) {
                        FallbackEmission.RESPONSE -> Unit
                        FallbackEmission.ERROR -> return@flow
                        FallbackEmission.NONE -> {
                            emit(
                                StreamEvent.Error(
                                    buildNoValidResponseError(
                                        config = config,
                                        statusCode = resp.code,
                                        contentType = resp.header("Content-Type"),
                                        lineCount = lineCount,
                                        payloadCount = payloadCount,
                                        payload = fallbackBody.toString(),
                                    ),
                                ),
                            )
                            return@flow
                        }
                    }
                }
            }

            emit(StreamEvent.Completed)
        } catch (e: CancellationException) {
            call?.cancel()
            throw e
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) {
                call?.cancel()
                throw CancellationException("Request cancelled")
            }
            emit(StreamEvent.Error("Network error: ${e.message}"))
        } finally {
            cancellationHandles.forEach { it.dispose() }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Send a non-streaming chat completion request.
     */
    suspend fun chatCompletion(
        config: LLMConfig,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null,
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
                Exception("Empty response body"),
            )

            val chatResponse = parseFullResponse(responseBody, config.apiType)
                ?.takeIf { it.hasPayload() }
                ?: return@withContext Result.failure(
                    Exception(
                        buildNoValidResponseError(
                            config = config,
                            statusCode = response.code,
                            contentType = response.header("Content-Type"),
                            lineCount = responseBody.lineSequence().count(),
                            payloadCount = 1,
                            payload = responseBody,
                        ),
                    ),
                )

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
                    Exception("Failed to fetch models (${response.code})"),
                )
            }

            val body = response.body?.string() ?: return@withContext Result.failure(
                Exception("Empty response"),
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
                    val visionCapability = detectVisionCapability(modelJson, id)
                    val supportsFunctionCalling = detectFunctionCallingCapability(modelJson, apiType, id)
                    val supportsThinking = detectThinkingCapability(modelJson, apiType, id)
                    ModelInfo(
                        id = id,
                        supportsVision = visionCapability.supported,
                        visionCapabilitySource = visionCapability.source,
                        supportsFunctionCalling = supportsFunctionCalling,
                        supportsThinking = supportsThinking,
                    )
                }
                .filterNotNull()
                .sortedBy { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Detect vision capability from API metadata first, then fall back to model-name patterns.
     */
    private fun detectVisionCapability(modelJson: JsonObject, modelId: String): CapabilityDetection {
        return when (detectVisionCapabilityFromMetadata(modelJson)) {
            CapabilityStatus.SUPPORTED ->
                CapabilityDetection(supported = true, source = CapabilitySource.API)
            CapabilityStatus.UNSUPPORTED ->
                CapabilityDetection(supported = false, source = CapabilitySource.API_UNSUPPORTED)
            CapabilityStatus.UNKNOWN -> CapabilityDetection(
                supported = LLMConfig.modelSupportsVision(modelId),
                source = CapabilitySource.AUTO,
            )
        }
    }

    private fun detectVisionCapabilityFromMetadata(modelJson: JsonObject): CapabilityStatus {
        val objects = listOfNotNull(
            modelJson,
            objectValue(modelJson.get("architecture")),
            objectValue(modelJson.get("capabilities")),
            objectValue(modelJson.get("metadata")),
        )

        return firstKnownCapability(objects) { detectVisionBooleanCapability(it) }
            ?: firstKnownCapability(objects) { detectVisionInputCapability(it) }
            ?: firstKnownCapability(objects) { detectVisionFeatureCapability(it) }
            ?: CapabilityStatus.UNKNOWN
    }

    private fun firstKnownCapability(
        objects: List<JsonObject>,
        detector: (JsonObject) -> CapabilityStatus,
    ): CapabilityStatus? {
        return objects.asSequence()
            .map(detector)
            .firstOrNull { it != CapabilityStatus.UNKNOWN }
    }

    private fun detectVisionBooleanCapability(modelJson: JsonObject): CapabilityStatus {
        for (field in VISION_BOOLEAN_FIELDS) {
            val value = booleanValue(modelJson.get(field)) ?: continue
            return if (value) CapabilityStatus.SUPPORTED else CapabilityStatus.UNSUPPORTED
        }
        return CapabilityStatus.UNKNOWN
    }

    private fun detectVisionInputCapability(modelJson: JsonObject): CapabilityStatus {
        for (field in VISION_INPUT_FIELDS) {
            val element = modelJson.get(field) ?: continue
            val status = inputModalityVisionStatus(element)
            // A present-but-empty/uninformative field stays UNKNOWN — keep looking.
            if (status != CapabilityStatus.UNKNOWN) return status
        }
        return CapabilityStatus.UNKNOWN
    }

    /**
     * Interpret a modality descriptor as vision *input* support. Handles arrays
     * (`["text", "image"]`), arrow strings (`"text+image->text"`) and plain
     * strings (`"multimodal"`). For arrow strings only the left/input side
     * counts, so image-generation models (`"text->image"`) are not mistaken for
     * vision input.
     */
    private fun inputModalityVisionStatus(element: JsonElement?): CapabilityStatus {
        if (element == null || element.isJsonNull) return CapabilityStatus.UNKNOWN
        return when {
            element.isJsonArray -> when {
                element.asJsonArray.size() == 0 -> CapabilityStatus.UNKNOWN
                element.asJsonArray.any { elementContainsVisionToken(it) } -> CapabilityStatus.SUPPORTED
                else -> CapabilityStatus.UNSUPPORTED
            }
            element.isJsonPrimitive -> {
                val raw = element.asString
                when {
                    raw.isBlank() -> CapabilityStatus.UNKNOWN
                    containsVisionToken(raw.substringBefore("->")) -> CapabilityStatus.SUPPORTED
                    else -> CapabilityStatus.UNSUPPORTED
                }
            }
            element.isJsonObject ->
                if (elementContainsVisionToken(element)) CapabilityStatus.SUPPORTED else CapabilityStatus.UNSUPPORTED
            else -> CapabilityStatus.UNKNOWN
        }
    }

    private fun detectVisionFeatureCapability(modelJson: JsonObject): CapabilityStatus {
        for (field in VISION_FEATURE_FIELDS) {
            val element = modelJson.get(field) ?: continue
            if (elementContainsVisionToken(element)) return CapabilityStatus.SUPPORTED
        }
        return CapabilityStatus.UNKNOWN
    }

    private fun booleanValue(element: JsonElement?): Boolean? {
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        return if (primitive.isBoolean) primitive.asBoolean else null
    }

    private fun elementContainsVisionToken(element: JsonElement?): Boolean {
        if (element == null || element.isJsonNull) return false
        return when {
            element.isJsonPrimitive -> containsVisionToken(element.asString)
            element.isJsonArray -> element.asJsonArray.any { elementContainsVisionToken(it) }
            element.isJsonObject -> element.asJsonObject.entrySet().any { entry ->
                (containsVisionToken(entry.key) && !isExplicitFalse(entry.value)) ||
                    elementContainsVisionToken(entry.value)
            }
            else -> false
        }
    }

    private fun isExplicitFalse(element: JsonElement?): Boolean {
        booleanValue(element)?.let { return !it }
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) return false
        return element.asString.lowercase() in setOf("false", "no", "none", "unsupported")
    }

    private fun containsVisionToken(value: String): Boolean {
        val lower = value.lowercase()
        // Image *generation/output* features must not count as image *input* support.
        if (IMAGE_OUTPUT_TOKENS.any { lower.contains(it) }) return false
        return VISION_TOKENS.any { token -> lower.contains(token) }
    }

    /**
     * Detect function calling capability from model metadata.
     */
    private fun detectFunctionCallingCapability(modelJson: JsonObject, apiType: ApiType, modelId: String): Boolean {
        val capabilities = objectValue(modelJson.get("capabilities"))
        val features = arrayValue(modelJson.get("supported_features"))
        return hasTrueBooleanCapability(capabilities, FUNCTION_CALLING_CAPABILITY_FIELDS) ||
            containsAnyPrimitiveToken(features, FUNCTION_CALLING_FEATURE_TOKENS) ||
            fallbackFunctionCallingCapability(apiType, modelId)
    }

    private fun fallbackFunctionCallingCapability(apiType: ApiType, modelId: String): Boolean {
        return LLMConfig.modelSupportsFunctionCalling(apiType, modelId)
    }

    /**
     * Detect thinking/reasoning capability from model metadata.
     */
    private fun detectThinkingCapability(modelJson: JsonObject, apiType: ApiType, modelId: String): Boolean {
        val capabilities = objectValue(modelJson.get("capabilities"))
        return hasTrueBooleanCapability(capabilities, THINKING_CAPABILITY_FIELDS) ||
            fallbackThinkingCapability(apiType, modelId)
    }

    private fun fallbackThinkingCapability(apiType: ApiType, modelId: String): Boolean {
        val lower = modelId.lowercase()
        return modelMatchesAny(lower, THINKING_MODEL_PATTERNS) ||
            apiType == ApiType.ANTHROPIC && modelMatchesAny(lower, CLAUDE_CAPABILITY_MODEL_PATTERNS)
    }

    private fun hasTrueBooleanCapability(modelJson: JsonObject?, fields: List<String>): Boolean {
        return fields.any { field -> booleanValue(modelJson?.get(field)) == true }
    }

    private fun containsAnyPrimitiveToken(element: JsonElement?, tokens: Set<String>): Boolean {
        if (element == null || element.isJsonNull || !element.isJsonArray) return false
        return element.asJsonArray.any { item ->
            item.isJsonPrimitive && item.asString.lowercase() in tokens
        }
    }

    private fun modelMatchesAny(modelId: String, patterns: List<String>): Boolean {
        return patterns.any { pattern -> modelId.contains(pattern) }
    }

    // ---- Response Parsing ----

    data class ChatResponse(
        val content: String?,
        val toolCalls: List<ToolCall>?,
        val thinking: String? = null,
    ) {
        fun hasPayload(): Boolean {
            return !content.isNullOrBlank() || !thinking.isNullOrBlank() || !toolCalls.isNullOrEmpty()
        }
    }

    private enum class FallbackEmission {
        NONE,
        RESPONSE,
        ERROR,
    }

    private fun parseFullResponse(body: String, apiType: ApiType): ChatResponse? {
        return try {
            parseResponseElement(JsonParser.parseString(body), apiType)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseResponseElement(element: JsonElement, apiType: ApiType): ChatResponse? {
        return when {
            element.isJsonArray -> {
                val responses = element.asJsonArray.mapNotNull { parseResponseElement(it, apiType) }
                mergeResponses(responses)
            }
            element.isJsonObject -> {
                val json = element.asJsonObject
                val typed = when (apiType) {
                    ApiType.OPENAI -> parseOpenAIResponse(json)
                    ApiType.ANTHROPIC -> parseAnthropicResponse(json)
                }
                typed?.takeIf { it.hasPayload() } ?: parseGenericResponse(json)
            }
            else -> textValue(element)
                ?.takeIf { it.isNotBlank() }
                ?.let { ChatResponse(content = it, toolCalls = null) }
        }
    }

    private fun mergeResponses(responses: List<ChatResponse>): ChatResponse? {
        if (responses.isEmpty()) return null

        val content = responses.mapNotNull { it.content }.joinToString("").ifBlank { null }
        val thinking = responses.mapNotNull { it.thinking }.joinToString("").ifBlank { null }
        val toolCalls = responses.flatMap { it.toolCalls.orEmpty() }.ifEmpty { null }
        return ChatResponse(content = content, thinking = thinking, toolCalls = toolCalls)
            .takeIf { it.hasPayload() }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamEvent>.emitFallbackResponse(
        body: String,
        apiType: ApiType,
    ): FallbackEmission {
        val trimmedBody = body.trim()
        if (trimmedBody.isEmpty()) return FallbackEmission.NONE

        if (!trimmedBody.startsWith("{") && !trimmedBody.startsWith("[")) {
            emit(StreamEvent.Token(trimmedBody))
            return FallbackEmission.RESPONSE
        }

        val parsed = parseFullResponse(trimmedBody, apiType)
        if (parsed != null) {
            return emitChatResponse(parsed)
        }

        parseErrorMessage(trimmedBody)?.let {
            emit(StreamEvent.Error(it))
            return FallbackEmission.ERROR
        }

        val lineResponses = trimmedBody.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") || it.startsWith("[") }
            .mapNotNull { line -> parseFullResponse(line, apiType) }
            .toList()

        mergeResponses(lineResponses)?.let { response ->
            return emitChatResponse(response)
        }

        trimmedBody.lineSequence()
            .mapNotNull { line -> parseErrorMessage(line.trim()) }
            .firstOrNull()
            ?.let {
                emit(StreamEvent.Error(it))
                return FallbackEmission.ERROR
            }

        return FallbackEmission.NONE
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamEvent>.emitChatResponse(
        response: ChatResponse,
    ): FallbackEmission {
        var emitted = false

        response.thinking?.takeIf { it.isNotBlank() }?.let {
            emit(StreamEvent.Thinking(it))
            emitted = true
        }
        response.content?.takeIf { it.isNotBlank() }?.let {
            emit(StreamEvent.Token(it))
            emitted = true
        }
        response.toolCalls
            ?.filter { it.name.isNotBlank() }
            ?.forEach {
                emit(StreamEvent.ToolCall(it))
                emitted = true
            }

        return if (emitted) FallbackEmission.RESPONSE else FallbackEmission.NONE
    }

    private fun appendFallbackPayload(target: StringBuilder, line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return false

        val payload = if (trimmed.startsWith("data:")) {
            trimmed.removePrefix("data:").trim()
        } else if (trimmed.startsWith("event:") || trimmed.startsWith("id:") || trimmed.startsWith("retry:")) {
            return false
        } else {
            trimmed
        }

        if (payload.isEmpty() || payload == "[DONE]") return false
        if (target.isNotEmpty()) target.append('\n')
        target.append(payload)
        return true
    }

    private fun buildNoValidResponseError(
        config: LLMConfig,
        statusCode: Int,
        contentType: String?,
        lineCount: Int,
        payloadCount: Int,
        payload: String,
    ): String {
        val endpoint = "${config.apiEndpoint.trimEnd('/')}/${config.apiPath.trimStart('/')}"
        val sample = payload.trim()
            .ifBlank { "<空响应体>" }
            .let { if (it.length > RESPONSE_SAMPLE_LIMIT) it.take(RESPONSE_SAMPLE_LIMIT) + "..." else it }

        return """
            未收到有效响应，请检查 API 返回格式。
            API 类型: ${config.apiType.displayName}
            模型: ${config.modelName}
            地址: $endpoint
            HTTP 状态: $statusCode
            Content-Type: ${contentType?.ifBlank { "<未返回>" } ?: "<未返回>"}
            已读取行数: $lineCount，候选响应片段: $payloadCount
            响应片段: $sample
            解析说明: 未找到可用的 answer/content/text/output_text、OpenAI choices[].message.content、choices[].delta.content、Anthropic content[].text 或 tool_calls。
        """.trimIndent()
    }

    private fun parseErrorMessage(body: String): String? {
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val error = json.get("error")
            when {
                error?.isJsonObject == true -> {
                    val errorObj = error.asJsonObject
                    textValue(errorObj.get("message"))
                        ?: textValue(errorObj.get("code"))
                        ?: textValue(errorObj.get("type"))
                }
                error != null && !error.isJsonNull -> textValue(error)
                else -> textValue(json.get("message"))
                    ?: textValue(json.get("msg"))
                    ?: textValue(json.get("detail"))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseOpenAIResponse(json: JsonObject): ChatResponse? {
        parseOpenAIResponsesApi(json)?.takeIf { it.hasPayload() }?.let { return it }

        val choices = json.getAsJsonArray("choices") ?: return null
        if (choices.size() == 0) return null

        val choice = choices[0].asJsonObject
        val message = objectValue(choice.get("message"))
        val delta = objectValue(choice.get("delta"))
        val content = textValue(message?.get("content"))
            ?: textValue(delta?.get("content"))
            ?: textValue(choice.get("text"))
            ?: textValue(json.get("output_text"))
        val thinking = textValue(message?.get("reasoning_content"))
            ?: textValue(message?.get("reasoning"))
            ?: textValue(delta?.get("reasoning_content"))
            ?: textValue(delta?.get("reasoning"))

        // Parse tool calls
        val toolCallsArray = arrayValue(message?.get("tool_calls")) ?: arrayValue(delta?.get("tool_calls"))
        val toolCalls = toolCallsArray?.mapIndexedNotNull { index, tc ->
            val toolCallObj = objectValue(tc) ?: return@mapIndexedNotNull null
            val function = objectValue(toolCallObj.get("function"))
            val name = textValue(function?.get("name")).orEmpty()
            if (name.isBlank()) return@mapIndexedNotNull null
            val id = textValue(toolCallObj.get("id")).orEmpty()
            val arguments = argumentValue(function?.get("arguments"))
            ToolCall(id = id.ifBlank { "tool_call_$index" }, name = name, arguments = arguments.ifBlank { "{}" })
        } ?: parseLegacyOpenAIFunctionCall(message ?: delta)

        return ChatResponse(content = content, toolCalls = toolCalls, thinking = thinking)
    }

    private fun parseOpenAIResponsesApi(json: JsonObject): ChatResponse? {
        val outputText = textValue(json.get("output_text"))
        val output = arrayValue(json.get("output"))
        if (output == null) {
            return outputText?.takeIf { it.isNotBlank() }?.let {
                ChatResponse(content = it, toolCalls = null)
            }
        }

        val contentParts = mutableListOf<String>()
        val thinkingParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCall>()

        output.forEachIndexed { index, item ->
            val itemObj = objectValue(item) ?: return@forEachIndexed
            when (textValue(itemObj.get("type"))) {
                "message" -> arrayValue(itemObj.get("content"))?.forEach { block ->
                    val blockObj = objectValue(block) ?: return@forEach
                    textValue(blockObj.get("text"))?.let { contentParts.add(it) }
                }
                "reasoning" -> {
                    textValue(itemObj.get("summary"))?.let { thinkingParts.add(it) }
                    textValue(itemObj.get("content"))?.let { thinkingParts.add(it) }
                }
                "function_call" -> {
                    val name = textValue(itemObj.get("name")).orEmpty()
                    if (name.isNotBlank()) {
                        toolCalls.add(
                            ToolCall(
                                id = textValue(itemObj.get("call_id"))
                                    ?: textValue(itemObj.get("id"))
                                    ?: "tool_call_$index",
                                name = name,
                                arguments = argumentValue(itemObj.get("arguments")).ifBlank { "{}" },
                            ),
                        )
                    }
                }
            }
        }

        return ChatResponse(
            content = contentParts.joinToString("").ifBlank { outputText },
            thinking = thinkingParts.joinToString("").ifBlank { null },
            toolCalls = toolCalls.ifEmpty { null },
        ).takeIf { it.hasPayload() }
    }

    private fun parseLegacyOpenAIFunctionCall(message: JsonObject?): List<ToolCall>? {
        val function = objectValue(message?.get("function_call")) ?: return null
        val name = textValue(function.get("name")).orEmpty()
        if (name.isBlank()) return null

        return listOf(
            ToolCall(
                id = textValue(message?.get("id")) ?: "tool_call_0",
                name = name,
                arguments = argumentValue(function.get("arguments")).ifBlank { "{}" },
            ),
        )
    }

    private fun parseGenericResponse(json: JsonObject): ChatResponse? {
        val data = objectValue(json.get("data"))
        val message = objectValue(json.get("message"))
        val result = objectValue(json.get("result"))

        val content = textValue(json.get("answer"))
            ?: textValue(json.get("content"))
            ?: textValue(json.get("text"))
            ?: textValue(json.get("response"))
            ?: textValue(json.get("result"))
            ?: textValue(json.get("output"))
            ?: textValue(json.get("output_text"))
            ?: textValue(message?.get("content"))
            ?: textValue(message?.get("text"))
            ?: textValue(data?.get("answer"))
            ?: textValue(data?.get("content"))
            ?: textValue(data?.get("text"))
            ?: textValue(data?.get("response"))
            ?: textValue(result?.get("answer"))
            ?: textValue(result?.get("content"))
            ?: textValue(result?.get("text"))

        val thinking = textValue(json.get("reasoning"))
            ?: textValue(json.get("reasoning_content"))
            ?: textValue(json.get("thinking"))

        return ChatResponse(content = content, thinking = thinking, toolCalls = null)
            .takeIf { it.hasPayload() }
    }

    private fun objectValue(element: JsonElement?): JsonObject? {
        return if (element != null && element.isJsonObject) element.asJsonObject else null
    }

    private fun arrayValue(element: JsonElement?): JsonArray? {
        return if (element != null && element.isJsonArray) element.asJsonArray else null
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
            }
            else -> null
        }
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
                    toolCalls.add(
                        ToolCall(
                            id = blockObj.get("id")?.asString ?: "",
                            name = blockObj.get("name")?.asString ?: "",
                            arguments = blockObj.getAsJsonObject("input")?.toString() ?: "{}",
                        ),
                    )
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
        tools: List<ToolDefinition>? = null,
    ): String {
        return when (config.apiType) {
            ApiType.OPENAI -> buildOpenAIBody(config, messages, stream, tools)
            ApiType.ANTHROPIC -> buildAnthropicBody(config, messages, stream, tools)
        }
    }

    private fun buildEffectiveSystemPrompt(config: LLMConfig, tools: List<ToolDefinition>?): String {
        return PromptTemplates.buildEffectiveSystemPrompt(
            configuredPrompt = config.systemPrompt,
            includeToolPolicy = !tools.isNullOrEmpty(),
        )
    }

    private fun buildOpenAIBody(
        config: LLMConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
        tools: List<ToolDefinition>? = null,
    ): String {
        val apiMessages = mutableListOf<Map<String, Any?>>()
        val supportsImageInput = supportsImageInput(config)
        val systemPrompt = buildEffectiveSystemPrompt(config, tools)

        if (systemPrompt.isNotBlank()) {
            apiMessages.add(mapOf("role" to "system", "content" to systemPrompt))
        }

        messages.filter { it.role != ChatMessage.ROLE_SYSTEM }.forEach { msg ->
            apiMessages.add(buildOpenAIMessage(msg, supportsImageInput))
        }

        val body = mutableMapOf<String, Any>(
            "model" to config.modelName,
            "messages" to apiMessages,
            "max_tokens" to config.maxTokens,
            "stream" to stream,
        )

        // Only add temperature for non-thinking models (o1/o3 don't support it)
        if (!config.thinkingEnabled || config.thinkingDepth == ThinkingDepth.NONE) {
            body["temperature"] = config.temperature
        }

        // Add reasoning effort for OpenAI-compatible thinking models
        if (config.thinkingEnabled && config.thinkingDepth != ThinkingDepth.NONE) {
            body["reasoning_effort"] = config.thinkingDepth.openaiReasoningEffort
        }

        if (!tools.isNullOrEmpty()) {
            body["tools"] = tools.map { it.toJson() }
        }

        return gson.toJson(body)
    }

    private fun buildOpenAIMessage(msg: ChatMessage, supportsImageInput: Boolean): Map<String, Any?> {
        return when {
            msg.imageBitmap != null && supportsImageInput -> buildOpenAIImageMessage(msg, msg.imageBitmap)
            msg.toolCalls != null -> buildOpenAIToolCallMessage(msg)
            msg.toolCallId != null -> buildOpenAIToolResultMessage(msg)
            msg.role == ChatMessage.ROLE_ASSISTANT && !msg.reasoningContent.isNullOrBlank() ->
                buildOpenAIReasoningMessage(msg)
            else -> mapOf("role" to msg.role, "content" to msg.content)
        }
    }

    private fun buildOpenAIImageMessage(msg: ChatMessage, bitmap: Bitmap): Map<String, Any> {
        val content = mutableListOf<Map<String, Any>>()
        content.add(
            mapOf(
                "type" to "image_url",
                "image_url" to mapOf(
                    "url" to "data:image/jpeg;base64,${bitmapToBase64(bitmap)}",
                    "detail" to "high",
                ),
            ),
        )
        if (msg.content.isNotBlank()) {
            content.add(mapOf("type" to "text", "text" to msg.content))
        }
        return mapOf("role" to msg.role, "content" to content)
    }

    private fun buildOpenAIToolCallMessage(msg: ChatMessage): Map<String, Any?> {
        val toolCallsMap = msg.toolCalls.orEmpty().map { tc ->
            mapOf(
                "id" to tc.id,
                "type" to "function",
                "function" to mapOf(
                    "name" to tc.name,
                    "arguments" to tc.arguments,
                ),
            )
        }
        val assistantMsg = mutableMapOf<String, Any?>(
            "role" to "assistant",
            "tool_calls" to toolCallsMap,
        )
        assistantMsg["content"] = if (msg.content.isBlank()) null else msg.content
        msg.reasoningContent?.takeIf { it.isNotBlank() }?.let {
            assistantMsg["reasoning_content"] = it
        }
        return assistantMsg
    }

    private fun buildOpenAIToolResultMessage(msg: ChatMessage): Map<String, Any?> {
        return mapOf(
            "role" to "tool",
            "tool_call_id" to msg.toolCallId,
            "content" to msg.content,
        )
    }

    private fun buildOpenAIReasoningMessage(msg: ChatMessage): Map<String, Any?> {
        return mapOf(
            "role" to msg.role,
            "content" to msg.content,
            "reasoning_content" to msg.reasoningContent,
        )
    }

    private fun buildAnthropicBody(
        config: LLMConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
        tools: List<ToolDefinition>? = null,
    ): String {
        val apiMessages = mutableListOf<Map<String, Any>>()
        val supportsImageInput = supportsImageInput(config)

        // Anthropic requires strict role alternation (user/assistant/user/...).
        // We must merge consecutive tool_result messages (role=tool) into a single
        // "user" message with an array of tool_result content blocks.
        val filteredMessages = messages.filter { it.role != ChatMessage.ROLE_SYSTEM }
        var i = 0
        while (i < filteredMessages.size) {
            val msg = filteredMessages[i]
            if (msg.imageBitmap != null && supportsImageInput) {
                // Multimodal message with image for Anthropic
                val content = mutableListOf<Map<String, Any>>()
                content.add(
                    mapOf(
                        "type" to "image",
                        "source" to mapOf(
                            "type" to "base64",
                            "media_type" to "image/jpeg",
                            "data" to bitmapToBase64(msg.imageBitmap),
                        ),
                    ),
                )
                if (msg.content.isNotBlank()) {
                    content.add(mapOf("type" to "text", "text" to msg.content))
                }
                apiMessages.add(mapOf("role" to msg.role, "content" to content))
                i++
            } else if (msg.toolCalls != null) {
                // Assistant message with tool use — include text block if present
                val content = mutableListOf<Map<String, Any>>()
                if (msg.content.isNotBlank()) {
                    content.add(mapOf("type" to "text", "text" to msg.content))
                }
                msg.toolCalls.forEach { tc ->
                    val inputObj = try {
                        JsonParser.parseString(tc.arguments).asJsonObject
                    } catch (e: Exception) {
                        com.google.gson.JsonObject()
                    }
                    content.add(
                        mapOf(
                            "type" to "tool_use",
                            "id" to tc.id,
                            "name" to tc.name,
                            "input" to inputObj,
                        ),
                    )
                }
                apiMessages.add(mapOf("role" to "assistant", "content" to content))
                i++
            } else if (msg.toolCallId != null) {
                // Merge all consecutive tool_result messages into one "user" message
                val toolResultBlocks = mutableListOf<Map<String, Any>>()
                var j = i
                while (j < filteredMessages.size && filteredMessages[j].toolCallId != null) {
                    val toolMsg = filteredMessages[j]
                    toolResultBlocks.add(
                        mapOf(
                            "type" to "tool_result",
                            "tool_use_id" to (toolMsg.toolCallId ?: ""),
                            "content" to toolMsg.content,
                        ),
                    )
                    j++
                }
                apiMessages.add(
                    mapOf(
                        "role" to "user",
                        "content" to toolResultBlocks,
                    ),
                )
                i = j
            } else {
                apiMessages.add(mapOf("role" to msg.role, "content" to msg.content))
                i++
            }
        }

        val body = mutableMapOf<String, Any>(
            "model" to config.modelName,
            "messages" to apiMessages,
            "max_tokens" to config.maxTokens,
            "stream" to stream,
        )

        // System prompt for Anthropic is a top-level field
        val systemPrompt = buildEffectiveSystemPrompt(config, tools)
        if (systemPrompt.isNotBlank()) {
            body["system"] = systemPrompt
        }

        // Only add temperature when thinking is disabled or depth is NONE
        if (!config.thinkingEnabled || config.thinkingDepth == ThinkingDepth.NONE) {
            body["temperature"] = config.temperature
        }

        // Extended thinking for Anthropic
        if (config.thinkingEnabled && config.thinkingDepth != ThinkingDepth.NONE) {
            body["thinking"] = mapOf(
                "type" to "enabled",
                "budget_tokens" to config.thinkingDepth.budgetTokens,
            )
        }

        if (!tools.isNullOrEmpty()) {
            body["tools"] = tools.map { tool ->
                mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "input_schema" to tool.parameters,
                )
            }
        }

        return gson.toJson(body)
    }

    private fun supportsImageInput(config: LLMConfig): Boolean {
        return config.supportsVisionInput()
    }

    private data class CapabilityDetection(
        val supported: Boolean,
        val source: CapabilitySource,
    )

    private enum class CapabilityStatus {
        SUPPORTED,
        UNSUPPORTED,
        UNKNOWN,
    }

    private companion object {
        const val RESPONSE_SAMPLE_LIMIT = 1200
        val VISION_BOOLEAN_FIELDS = listOf(
            "vision",
            "image",
            "images",
            "image_input",
            "supports_vision",
            "supports_images",
            "multimodal",
        )
        val VISION_INPUT_FIELDS = listOf(
            "modalities",
            "input_modalities",
            "input_types",
            "input_type",
            "supported_input_types",
            "supported_modalities",
            "modality",
        )
        val VISION_FEATURE_FIELDS = listOf(
            "capabilities",
            "features",
            "supported_features",
        )
        val VISION_TOKENS = listOf(
            "image",
            "vision",
            "visual",
            "multimodal",
        )

        /** Tokens that denote image *output/generation*, which is not vision *input*. */
        val IMAGE_OUTPUT_TOKENS = listOf(
            "image_generation",
            "image-generation",
            "image generation",
            "imagegen",
            "image_gen",
            "img_generation",
            "text-to-image",
            "text_to_image",
            "texttoimage",
            "image_output",
            "output_image",
            "generate_image",
        )
        val FUNCTION_CALLING_CAPABILITY_FIELDS = listOf("function_calling", "tool_use")
        val FUNCTION_CALLING_FEATURE_TOKENS = setOf("function_calling", "tool_use", "tools")
        val FUNCTION_CALLING_MODEL_PATTERNS = listOf(
            "gpt-4",
            "gpt-3.5-turbo",
            "o1",
            "o3",
            "o4",
            "gemini-1.5",
            "gemini-2",
            "gemini-pro",
            "qwen-max",
            "qwen-plus",
            "qwen-turbo",
            "deepseek-chat",
            "deepseek-coder",
            "glm-4",
            "glm-3",
            "mistral",
            "mixtral",
        )
        val THINKING_CAPABILITY_FIELDS = listOf("reasoning", "thinking")
        val THINKING_MODEL_PATTERNS = listOf(
            "o1",
            "o3",
            "o4",
            "deepseek-r1",
            "deepseek-reasoner",
        )
        val CLAUDE_CAPABILITY_MODEL_PATTERNS = listOf(
            "claude-3",
            "claude-sonnet-4",
            "claude-opus-4",
        )
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
                true,
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

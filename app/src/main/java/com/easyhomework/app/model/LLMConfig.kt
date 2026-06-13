package com.easyhomework.app.model

/**
 * API provider type.
 */
enum class ApiType(val displayName: String) {
    OPENAI("OpenAI 兼容"),
    ANTHROPIC("Anthropic Claude"),
    ;

    companion object {
        fun fromString(value: String): ApiType {
            return entries.find { it.name == value } ?: OPENAI
        }
    }
}

/**
 * Thinking depth levels for models with extended thinking.
 */
enum class ThinkingDepth(val displayName: String, val budgetTokens: Int, val openaiReasoningEffort: String) {
    NONE("关闭", 0, ""),
    LOW("轻度", 2048, "low"),
    MEDIUM("中度", 8192, "medium"),
    HIGH("深度", 24576, "high"),
    XHIGH("极深", 50000, "high"),
    ;

    companion object {
        fun fromString(value: String): ThinkingDepth {
            return entries.find { it.name == value } ?: NONE
        }
    }
}

/**
 * Default way a captured question is sent to the model.
 * Resolved against the active model's vision capability at search time.
 */
enum class SearchSendMode(val displayName: String, val summary: String) {
    AUTO("自动（推荐）", "看模型能力：识图模型发图+文字，纯文字模型发文字"),
    TEXT_ONLY("纯文字", "只发送 OCR 识别出的文字"),
    IMAGE_ONLY("仅图片", "识图模型只发送截图，不附带 OCR 文字"),
    ;

    companion object {
        fun fromString(value: String?): SearchSendMode {
            return entries.find { it.name == value } ?: AUTO
        }
    }
}

/**
 * Source of model capability information.
 */
enum class CapabilitySource(val displayName: String) {
    AUTO("自动判断"),
    API("接口识别支持"),
    API_UNSUPPORTED("接口识别不支持"),
    MANUAL("手动设置"),
    ;

    companion object {
        fun fromString(value: String): CapabilitySource {
            return entries.find { it.name == value } ?: AUTO
        }
    }
}

/**
 * LLM API configuration data class.
 * Supports both OpenAI-compatible and Anthropic API formats.
 */
data class LLMConfig(
    val id: String = "",
    val name: String = "",
    val apiType: ApiType = ApiType.OPENAI,
    val apiEndpoint: String = "https://api.openai.com",
    val apiPath: String = "/v1/chat/completions",
    val apiKey: String = "",
    val modelName: String = "gpt-4o",
    val systemPrompt: String = PromptTemplates.DEFAULT_SYSTEM_PROMPT,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val stream: Boolean = true,
    val thinkingEnabled: Boolean = false,
    val thinkingDepth: ThinkingDepth = ThinkingDepth.MEDIUM,
    val miniBall: Boolean = false,
    val supportsVision: Boolean = true,
    val visionCapabilitySource: CapabilitySource = CapabilitySource.AUTO,
    val supportsFunctionCalling: Boolean = true,
    val supportsThinking: Boolean = false,
) {
    fun getFullUrl(): String {
        val base = apiEndpoint.trimEnd('/')
        val path = apiPath.trimStart('/')
        return "$base/$path"
    }

    fun getModelsUrl(): String {
        val base = apiEndpoint.trimEnd('/')
        return "$base/v1/models"
    }

    fun supportsVisionInput(): Boolean {
        return when (visionCapabilitySource) {
            CapabilitySource.MANUAL -> supportsVision
            CapabilitySource.API -> supportsVision || modelSupportsVision(modelName)
            CapabilitySource.API_UNSUPPORTED -> false
            CapabilitySource.AUTO -> modelSupportsVision(modelName)
        }
    }

    fun supportsToolCalling(): Boolean {
        return supportsFunctionCalling || modelSupportsFunctionCalling(apiType, modelName)
    }

    companion object {
        /**
         * Well-known vision-capable model patterns for auto-detection.
         */
        private val VISION_MODEL_PATTERNS = listOf(
            "gpt-4o", "gpt-4.1", "gpt-4-vision", "gpt-4-turbo", "gpt-5",
            "o1", "o3", "o4",
            "claude-3", "claude-3.5", "claude-3.7", "claude-4", "claude-sonnet-4", "claude-opus-4",
            "gemini-pro-vision", "gemini-1.5", "gemini-2", "gemini-2.5",
            "qwen-vl", "qwen2-vl", "qwen2.5-vl", "qwen2.5-omni", "qwen-omni", "qvq",
            "deepseek-vl",
            "glm-4v", "glm-4.1v",
            "internvl",
            "minicpm-v", "minicpm-o",
            "llava",
            "pixtral",
            "llama-3.2-vision",
            "llama-4",
        )

        private val TEXT_ONLY_MODEL_PATTERNS = listOf(
            "deepseek-chat",
            "deepseek-coder",
            "deepseek-reasoner",
            "qwen-plus",
            "qwen-turbo",
            "qwen-max",
            "llama-3",
            "mistral",
            "mixtral",
            "text-embedding",
            "embedding",
        )

        private val FUNCTION_CALLING_MODEL_PATTERNS = listOf(
            "gpt-4",
            "gpt-3.5-turbo",
            "gpt-5",
            "o1",
            "o3",
            "o4",
            "claude-3",
            "claude-sonnet-4",
            "claude-opus-4",
            "gemini-1.5",
            "gemini-2",
            "gemini-pro",
            "qwen",
            "deepseek-chat",
            "deepseek-coder",
            "deepseek-reasoner",
            "glm-4",
            "glm-3",
            "mistral",
            "mixtral",
            "llama-3",
            "llama-4",
        )

        /**
         * Detect if a model name likely supports vision input.
         */
        fun modelSupportsVision(modelName: String): Boolean {
            val lower = modelName.lowercase().trim()
            if (lower.isBlank()) return false
            if (VISION_MODEL_PATTERNS.any { lower.contains(it) }) return true
            if (TEXT_ONLY_MODEL_PATTERNS.any { lower.contains(it) }) return false

            return Regex("(^|[/_.:-])o[134](-|$)").containsMatchIn(lower)
        }

        fun modelSupportsFunctionCalling(apiType: ApiType, modelName: String): Boolean {
            val lower = modelName.lowercase()
            return apiType == ApiType.ANTHROPIC || FUNCTION_CALLING_MODEL_PATTERNS.any { lower.contains(it) }
        }
    }
}

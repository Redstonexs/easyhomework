package com.easyhomework.app.model

/**
 * API provider type.
 */
enum class ApiType(val displayName: String) {
    OPENAI("OpenAI 兼容"),
    ANTHROPIC("Anthropic Claude");

    companion object {
        fun fromString(value: String): ApiType {
            return entries.find { it.name == value } ?: OPENAI
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
    val systemPrompt: String = "你是一个专业的解题助手。请仔细阅读用户提供的题目，给出详细的解题步骤和最终答案。如果是数学题，请展示完整的计算过程。如果是选择题，请分析每个选项并给出正确答案。",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val stream: Boolean = true,
    val thinkingEnabled: Boolean = false,
    val thinkingBudget: Int = 10000,
    val miniBall: Boolean = false,
    val supportsVision: Boolean = true
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

    companion object {
        /**
         * Well-known vision-capable model patterns for auto-detection.
         */
        private val VISION_MODEL_PATTERNS = listOf(
            "gpt-4o", "gpt-4-vision", "gpt-4-turbo",
            "claude-3", "claude-sonnet-4", "claude-opus-4",
            "gemini-pro-vision", "gemini-1.5", "gemini-2",
            "qwen-vl", "qwen2-vl", "qwen-max",
            "deepseek-vl",
            "glm-4v", "glm-4",
            "internvl",
            "minicpm-v",
            "llava",
            "vision"
        )

        /**
         * Detect if a model name likely supports vision input.
         */
        fun modelSupportsVision(modelName: String): Boolean {
            val lower = modelName.lowercase()
            return VISION_MODEL_PATTERNS.any { lower.contains(it) }
        }
    }
}

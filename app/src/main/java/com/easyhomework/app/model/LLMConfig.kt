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
 * Source of model capability information.
 */
enum class CapabilitySource(val displayName: String) {
    AUTO("自动判断"),
    API("接口识别"),
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
    val systemPrompt: String = "你是一个专业的解题助手。请仔细阅读用户提供的题目，给出详细的解题步骤和最终答案。如果是数学题，请展示完整的计算过程。如果是选择题，请分析每个选项并给出正确答案。",
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
            CapabilitySource.API, CapabilitySource.MANUAL -> supportsVision
            CapabilitySource.AUTO -> supportsVision || modelSupportsVision(modelName)
        }
    }

    companion object {
        /**
         * Well-known vision-capable model patterns for auto-detection.
         */
        private val VISION_MODEL_PATTERNS = listOf(
            "gpt-4o", "gpt-4.1", "gpt-4-vision", "gpt-4-turbo", "gpt-5",
            "o3", "o4-mini",
            "claude-3", "claude-sonnet-4", "claude-opus-4",
            "gemini-pro-vision", "gemini-1.5", "gemini-2",
            "qwen-vl", "qwen2-vl", "qwen2.5-vl", "qvq",
            "deepseek-vl",
            "glm-4v",
            "internvl",
            "minicpm-v",
            "llava",
            "pixtral",
            "llama-4",
            "vision",
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

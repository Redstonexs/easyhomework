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
            // OpenAI (o-series reasoning models handled by O_SERIES_VISION_REGEX)
            "gpt-4o", "gpt-4.1", "gpt-4-vision", "gpt-4-turbo", "gpt-5", "chatgpt-4o",
            // Anthropic Claude handled separately (see modelSupportsVision)
            // Google
            "gemini-1.5", "gemini-2", "gemini-pro-vision", "gemma-3",
            // Alibaba Qwen
            "qwen-vl", "qwen2-vl", "qwen2.5-vl", "qwen3-vl", "qwen2.5-omni", "qwen-omni", "qvq",
            // Zhipu GLM (numbered "*v" variants also caught by GLM_VISION_REGEX)
            "glm-4v", "glm-4.1v", "glm-4.5v",
            // DeepSeek / open multimodal
            "deepseek-vl", "internvl", "minicpm-v", "minicpm-o", "llava",
            // Mistral
            "pixtral", "mistral-small-3", "mistral-medium-3",
            // Meta Llama
            "llama-3.2-vision", "llama-4",
            // xAI Grok
            "grok-2-vision", "grok-4", "grok-vision",
            // Others
            "step-1v", "step-1o", "step-3", "yi-vision", "yi-vl",
            "doubao-vision", "doubao-1.5-vision",
            "phi-3-vision", "phi-3.5-vision", "phi-4-multimodal",
            "nova-lite", "nova-pro",
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
            // Reasoning "mini"/"preview" models that are text-only (full o1/o3 do see images)
            "o1-mini",
            "o1-preview",
            "o3-mini",
        )

        /** `vl` as a standalone token, e.g. `qwen3-vl`, `deepseek-vl`, `yi-vl` (not `internvl`). */
        private val VL_TOKEN_REGEX = Regex("(^|[/_.:-])vl([/_.:-]|$)")

        /** Zhipu numbered vision variants: `glm-4v`, `glm-4.1v`, `glm-4.5v`. */
        private val GLM_VISION_REGEX = Regex("glm-[0-9.]+v")

        /** OpenAI o-series reasoning models (`o1`, `o3`, `o4-mini`, ...) — multimodal. */
        private val O_SERIES_VISION_REGEX = Regex("(^|[/_.:-])o[134](-|$)")

        /** Legacy Claude lines that are text-only (everything newer is multimodal). */
        private val CLAUDE_LEGACY_TEXT_MARKERS = listOf("claude-1", "claude-2", "claude-instant")

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
            // Precedence (top wins): explicit vision markers, then vl/glm tokens, then
            // Claude, then known vision families; known text-only families are excluded;
            // finally the OpenAI o-series reasoning models (minis already excluded).
            return when {
                lower.isBlank() -> false
                lower.contains("vision") || lower.contains("multimodal") -> true
                VL_TOKEN_REGEX.containsMatchIn(lower) || GLM_VISION_REGEX.containsMatchIn(lower) -> true
                isModernClaude(lower) -> true
                VISION_MODEL_PATTERNS.any { lower.contains(it) } -> true
                TEXT_ONLY_MODEL_PATTERNS.any { lower.contains(it) } -> false
                else -> O_SERIES_VISION_REGEX.containsMatchIn(lower)
            }
        }

        /**
         * Anthropic Claude accepts images on every model except the legacy
         * Claude 1 / 2 / instant text line.
         */
        private fun isModernClaude(lower: String): Boolean =
            lower.contains("claude") && CLAUDE_LEGACY_TEXT_MARKERS.none { lower.contains(it) }

        fun modelSupportsFunctionCalling(apiType: ApiType, modelName: String): Boolean {
            val lower = modelName.lowercase()
            return apiType == ApiType.ANTHROPIC || FUNCTION_CALLING_MODEL_PATTERNS.any { lower.contains(it) }
        }
    }
}

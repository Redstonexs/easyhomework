package com.easyhomework.app.model

/**
 * LLM API configuration data class.
 * Supports OpenAI-compatible API format (works with DeepSeek, Moonshot, Qwen, etc.)
 */
data class LLMConfig(
    val apiEndpoint: String = "https://api.openai.com",
    val apiPath: String = "/v1/chat/completions",
    val apiKey: String = "",
    val modelName: String = "gpt-4o",
    val systemPrompt: String = "你是一个专业的解题助手。请仔细阅读用户提供的题目，给出详细的解题步骤和最终答案。如果是数学题，请展示完整的计算过程。如果是选择题，请分析每个选项并给出正确答案。",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val stream: Boolean = true
) {
    /**
     * Get the full API URL by combining endpoint and path.
     */
    fun getFullUrl(): String {
        val base = apiEndpoint.trimEnd('/')
        val path = apiPath.trimStart('/')
        return "$base/$path"
    }
}

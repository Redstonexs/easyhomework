package com.easyhomework.app.model

/**
 * One-tap configuration template for a well-known AI provider. Applying a preset fills in the
 * endpoint, path, API type and a sensible default model — the user only needs to paste a key.
 */
data class ProviderPreset(
    val name: String,
    val apiType: ApiType,
    val endpoint: String,
    val apiPath: String,
    val suggestedModel: String,
    val keysUrl: String = "",
)

/**
 * Curated, China-friendly defaults. Vision-capable models are preferred where free/cheap so the
 * one-tap 搜题 flow can send the screenshot directly.
 */
object ProviderPresets {
    val ALL: List<ProviderPreset> = listOf(
        ProviderPreset(
            name = "DeepSeek",
            apiType = ApiType.OPENAI,
            endpoint = "https://api.deepseek.com",
            apiPath = "/v1/chat/completions",
            suggestedModel = "deepseek-chat",
            keysUrl = "platform.deepseek.com",
        ),
        ProviderPreset(
            name = "Kimi 月之暗面",
            apiType = ApiType.OPENAI,
            endpoint = "https://api.moonshot.cn",
            apiPath = "/v1/chat/completions",
            suggestedModel = "moonshot-v1-8k-vision-preview",
            keysUrl = "platform.moonshot.cn",
        ),
        ProviderPreset(
            name = "智谱 GLM",
            apiType = ApiType.OPENAI,
            endpoint = "https://open.bigmodel.cn/api/paas",
            apiPath = "/v4/chat/completions",
            suggestedModel = "glm-4v-flash",
            keysUrl = "bigmodel.cn",
        ),
        ProviderPreset(
            name = "通义千问",
            apiType = ApiType.OPENAI,
            endpoint = "https://dashscope.aliyuncs.com/compatible-mode",
            apiPath = "/v1/chat/completions",
            suggestedModel = "qwen-vl-plus",
            keysUrl = "bailian.console.aliyun.com",
        ),
        ProviderPreset(
            name = "硅基流动",
            apiType = ApiType.OPENAI,
            endpoint = "https://api.siliconflow.cn",
            apiPath = "/v1/chat/completions",
            suggestedModel = "Qwen/Qwen2.5-VL-72B-Instruct",
            keysUrl = "siliconflow.cn",
        ),
        ProviderPreset(
            name = "OpenRouter",
            apiType = ApiType.OPENAI,
            endpoint = "https://openrouter.ai/api",
            apiPath = "/v1/chat/completions",
            suggestedModel = "openai/gpt-4o-mini",
            keysUrl = "openrouter.ai",
        ),
        ProviderPreset(
            name = "OpenAI",
            apiType = ApiType.OPENAI,
            endpoint = "https://api.openai.com",
            apiPath = "/v1/chat/completions",
            suggestedModel = "gpt-4o-mini",
            keysUrl = "platform.openai.com",
        ),
        ProviderPreset(
            name = "Claude",
            apiType = ApiType.ANTHROPIC,
            endpoint = "https://api.anthropic.com",
            apiPath = "/v1/messages",
            suggestedModel = "claude-3-5-sonnet-latest",
            keysUrl = "console.anthropic.com",
        ),
    )
}

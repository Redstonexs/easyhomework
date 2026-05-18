package com.easyhomework.app.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.easyhomework.app.model.ApiType
import com.easyhomework.app.model.CapabilitySource
import com.easyhomework.app.model.LLMConfig
import com.easyhomework.app.model.ThinkingDepth
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages app preferences with encrypted storage for sensitive data like API keys.
 * Supports multiple AI provider configurations.
 */
class PreferencesManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "easyhomework_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("easyhomework_prefs", Context.MODE_PRIVATE)

    private val gson = Gson()

    // ---- Multi-Provider Config ----

    fun saveProviderConfigs(configs: List<LLMConfig>) {
        // Save non-sensitive data to regular prefs
        val configDataList = configs.map { config ->
            mapOf(
                "id" to config.id,
                "name" to config.name,
                "apiType" to config.apiType.name,
                "apiEndpoint" to config.apiEndpoint,
                "apiPath" to config.apiPath,
                "modelName" to config.modelName,
                "systemPrompt" to config.systemPrompt,
                "temperature" to config.temperature.toString(),
                "maxTokens" to config.maxTokens.toString(),
                "stream" to config.stream.toString(),
                "thinkingEnabled" to config.thinkingEnabled.toString(),
                "thinkingDepth" to config.thinkingDepth.name,
                "supportsVision" to config.supportsVision.toString(),
                "visionCapabilitySource" to config.visionCapabilitySource.name,
                "supportsFunctionCalling" to config.supportsFunctionCalling.toString(),
                "supportsThinking" to config.supportsThinking.toString(),
                "miniBall" to config.miniBall.toString(),
            )
        }
        prefs.edit().putString(KEY_PROVIDER_CONFIGS, gson.toJson(configDataList)).apply()

        // Save API keys to encrypted prefs
        val keyMap = configs.associate { it.id to it.apiKey }
        encryptedPrefs.edit().putString(KEY_API_KEYS_MAP, gson.toJson(keyMap)).apply()

        // Save active provider ID
        if (configs.isNotEmpty() && activeProviderId.isBlank()) {
            activeProviderId = configs.first().id
        }
    }

    fun loadProviderConfigs(): List<LLMConfig> {
        val json = prefs.getString(KEY_PROVIDER_CONFIGS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Map<String, String>>>() {}.type
            val configDataList: List<Map<String, String>> = gson.fromJson(json, type)

            val keysJson = encryptedPrefs.getString(KEY_API_KEYS_MAP, "{}") ?: "{}"
            val keysType = object : TypeToken<Map<String, String>>() {}.type
            val keyMap: Map<String, String> = gson.fromJson(keysJson, keysType) ?: emptyMap()

            configDataList.map { data ->
                LLMConfig(
                    id = data["id"] ?: "",
                    name = data["name"] ?: "",
                    apiType = ApiType.fromString(data["apiType"] ?: "OPENAI"),
                    apiEndpoint = data["apiEndpoint"] ?: "",
                    apiPath = data["apiPath"] ?: "/v1/chat/completions",
                    apiKey = keyMap[data["id"]] ?: "",
                    modelName = data["modelName"] ?: "",
                    systemPrompt = data["systemPrompt"] ?: LLMConfig().systemPrompt,
                    temperature = (data["temperature"] ?: "0.7").toFloatOrNull() ?: 0.7f,
                    maxTokens = (data["maxTokens"] ?: "2048").toIntOrNull() ?: 2048,
                    stream = data["stream"]?.toBooleanStrictOrNull() ?: true,
                    thinkingEnabled = data["thinkingEnabled"]?.toBooleanStrictOrNull() ?: false,
                    thinkingDepth = ThinkingDepth.fromString(data["thinkingDepth"] ?: "NONE"),
                    supportsVision = data["supportsVision"]?.toBooleanStrictOrNull() ?: true,
                    visionCapabilitySource = CapabilitySource.fromString(data["visionCapabilitySource"] ?: "AUTO"),
                    supportsFunctionCalling = data["supportsFunctionCalling"]?.toBooleanStrictOrNull() ?: true,
                    supportsThinking = data["supportsThinking"]?.toBooleanStrictOrNull() ?: false,
                    miniBall = data["miniBall"]?.toBooleanStrictOrNull() ?: false,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    var activeProviderId: String
        get() = prefs.getString(KEY_ACTIVE_PROVIDER_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROVIDER_ID, value).apply()

    fun getActiveConfig(): LLMConfig {
        val configs = loadProviderConfigs()
        val activeId = activeProviderId
        return configs.find { it.id == activeId } ?: configs.firstOrNull() ?: LLMConfig()
    }

    // ---- Legacy Single Config (for migration) ----

    fun saveLLMConfig(config: LLMConfig) {
        val configs = loadProviderConfigs().toMutableList()
        val index = configs.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            configs[index] = config
        } else if (config.id.isNotBlank()) {
            configs.add(config)
        }
        if (configs.isNotEmpty()) {
            saveProviderConfigs(configs)
            activeProviderId = config.id
        }

        // Also save legacy format for backward compatibility
        prefs.edit().apply {
            putString(KEY_API_TYPE, config.apiType.name)
            putString(KEY_API_ENDPOINT, config.apiEndpoint)
            putString(KEY_API_PATH, config.apiPath)
            putString(KEY_MODEL_NAME, config.modelName)
            putString(KEY_SYSTEM_PROMPT, config.systemPrompt)
            putFloat(KEY_TEMPERATURE, config.temperature)
            putInt(KEY_MAX_TOKENS, config.maxTokens)
            putBoolean(KEY_STREAM, config.stream)
            putBoolean(KEY_THINKING_ENABLED, config.thinkingEnabled)
            putString(KEY_THINKING_DEPTH, config.thinkingDepth.name)
            putBoolean(KEY_MINI_BALL, config.miniBall)
            apply()
        }
        encryptedPrefs.edit().putString(KEY_API_KEY, config.apiKey).apply()
    }

    fun getLLMConfig(): LLMConfig {
        // Try to get from multi-provider first
        val activeConfig = getActiveConfig()
        if (activeConfig.apiKey.isNotBlank() || activeConfig.apiEndpoint.isNotBlank()) {
            return activeConfig
        }

        // Fallback to legacy single config
        val defaults = LLMConfig()
        return LLMConfig(
            apiType = ApiType.fromString(prefs.getString(KEY_API_TYPE, defaults.apiType.name) ?: defaults.apiType.name),
            apiEndpoint = prefs.getString(KEY_API_ENDPOINT, defaults.apiEndpoint) ?: defaults.apiEndpoint,
            apiPath = prefs.getString(KEY_API_PATH, defaults.apiPath) ?: defaults.apiPath,
            apiKey = encryptedPrefs.getString(KEY_API_KEY, "") ?: "",
            modelName = prefs.getString(KEY_MODEL_NAME, defaults.modelName) ?: defaults.modelName,
            systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, defaults.systemPrompt) ?: defaults.systemPrompt,
            temperature = prefs.getFloat(KEY_TEMPERATURE, defaults.temperature),
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, defaults.maxTokens),
            stream = prefs.getBoolean(KEY_STREAM, defaults.stream),
            thinkingEnabled = prefs.getBoolean(KEY_THINKING_ENABLED, defaults.thinkingEnabled),
            thinkingDepth = ThinkingDepth.fromString(prefs.getString(KEY_THINKING_DEPTH, defaults.thinkingDepth.name) ?: defaults.thinkingDepth.name),
            miniBall = prefs.getBoolean(KEY_MINI_BALL, defaults.miniBall),
        )
    }

    // ---- Mini Ball (global setting) ----

    var miniBall: Boolean
        get() = prefs.getBoolean(KEY_MINI_BALL, false)
        set(value) = prefs.edit().putBoolean(KEY_MINI_BALL, value).apply()

    // ---- Floating Ball State ----

    var isFloatingBallEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_BALL_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_BALL_ENABLED, value).apply()

    /**
     * Registers a listener for floating ball enabled state changes.
     * The returned listener must be unregistered when the caller is disposed.
     */
    fun registerFloatingBallEnabledListener(
        onChanged: (Boolean) -> Unit,
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_FLOATING_BALL_ENABLED) {
                onChanged(isFloatingBallEnabled)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    /**
     * Unregisters a listener returned by [registerFloatingBallEnabledListener].
     */
    fun unregisterFloatingBallEnabledListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    var floatingBallX: Int
        get() = prefs.getInt(KEY_BALL_X, -1)
        set(value) = prefs.edit().putInt(KEY_BALL_X, value).apply()

    var floatingBallY: Int
        get() = prefs.getInt(KEY_BALL_Y, 300)
        set(value) = prefs.edit().putInt(KEY_BALL_Y, value).apply()

    // ---- Answer Panel State ----

    var answerPanelHeightRatio: Float
        get() = prefs.getFloat(KEY_PANEL_HEIGHT_RATIO, 0.65f)
        set(value) = prefs.edit().putFloat(KEY_PANEL_HEIGHT_RATIO, value).apply()

    companion object {
        // Legacy keys
        private const val KEY_API_TYPE = "api_type"
        private const val KEY_API_ENDPOINT = "api_endpoint"
        private const val KEY_API_PATH = "api_path"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_STREAM = "stream"
        private const val KEY_THINKING_ENABLED = "thinking_enabled"
        private const val KEY_THINKING_DEPTH = "thinking_depth"
        private const val KEY_MINI_BALL = "mini_ball"
        private const val KEY_FLOATING_BALL_ENABLED = "floating_ball_enabled"
        private const val KEY_BALL_X = "ball_x"
        private const val KEY_BALL_Y = "ball_y"
        private const val KEY_PANEL_HEIGHT_RATIO = "panel_height_ratio"

        // Multi-provider keys
        private const val KEY_PROVIDER_CONFIGS = "provider_configs"
        private const val KEY_API_KEYS_MAP = "api_keys_map"
        private const val KEY_ACTIVE_PROVIDER_ID = "active_provider_id"
    }
}

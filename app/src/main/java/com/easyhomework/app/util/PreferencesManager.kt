package com.easyhomework.app.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.easyhomework.app.model.LLMConfig

/**
 * Manages app preferences with encrypted storage for sensitive data like API keys.
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
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("easyhomework_prefs", Context.MODE_PRIVATE)

    // ---- LLM Config ----

    fun saveLLMConfig(config: LLMConfig) {
        prefs.edit().apply {
            putString(KEY_API_ENDPOINT, config.apiEndpoint)
            putString(KEY_API_PATH, config.apiPath)
            putString(KEY_MODEL_NAME, config.modelName)
            putString(KEY_SYSTEM_PROMPT, config.systemPrompt)
            putFloat(KEY_TEMPERATURE, config.temperature)
            putInt(KEY_MAX_TOKENS, config.maxTokens)
            putBoolean(KEY_STREAM, config.stream)
            apply()
        }
        // API key stored in encrypted preferences
        encryptedPrefs.edit().putString(KEY_API_KEY, config.apiKey).apply()
    }

    fun getLLMConfig(): LLMConfig {
        return LLMConfig(
            apiEndpoint = prefs.getString(KEY_API_ENDPOINT, LLMConfig().apiEndpoint) ?: LLMConfig().apiEndpoint,
            apiPath = prefs.getString(KEY_API_PATH, LLMConfig().apiPath) ?: LLMConfig().apiPath,
            apiKey = encryptedPrefs.getString(KEY_API_KEY, "") ?: "",
            modelName = prefs.getString(KEY_MODEL_NAME, LLMConfig().modelName) ?: LLMConfig().modelName,
            systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, LLMConfig().systemPrompt) ?: LLMConfig().systemPrompt,
            temperature = prefs.getFloat(KEY_TEMPERATURE, LLMConfig().temperature),
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, LLMConfig().maxTokens),
            stream = prefs.getBoolean(KEY_STREAM, LLMConfig().stream)
        )
    }

    // ---- Floating Ball State ----

    var isFloatingBallEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_BALL_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_BALL_ENABLED, value).apply()

    var floatingBallX: Int
        get() = prefs.getInt(KEY_BALL_X, -1)
        set(value) = prefs.edit().putInt(KEY_BALL_X, value).apply()

    var floatingBallY: Int
        get() = prefs.getInt(KEY_BALL_Y, 300)
        set(value) = prefs.edit().putInt(KEY_BALL_Y, value).apply()

    companion object {
        private const val KEY_API_ENDPOINT = "api_endpoint"
        private const val KEY_API_PATH = "api_path"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_STREAM = "stream"
        private const val KEY_FLOATING_BALL_ENABLED = "floating_ball_enabled"
        private const val KEY_BALL_X = "ball_x"
        private const val KEY_BALL_Y = "ball_y"
    }
}

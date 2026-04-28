package com.easyhomework.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.easyhomework.app.model.LLMConfig
import com.easyhomework.app.util.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)

    private val _config = MutableStateFlow(preferencesManager.getLLMConfig())
    val config: StateFlow<LLMConfig> = _config.asStateFlow()

    private val _saveMessage = MutableStateFlow<String?>(null)
    val saveMessage: StateFlow<String?> = _saveMessage.asStateFlow()

    fun updateConfig(config: LLMConfig) {
        _config.value = config
    }

    fun saveConfig() {
        preferencesManager.saveLLMConfig(_config.value)
        _saveMessage.value = "设置已保存"
    }

    fun clearSaveMessage() {
        _saveMessage.value = null
    }

    fun validateConfig(): String? {
        val config = _config.value
        if (config.apiEndpoint.isBlank()) return "请输入 API 端点"
        if (config.apiKey.isBlank()) return "请输入 API 密钥"
        if (config.modelName.isBlank()) return "请输入模型名称"
        return null
    }
}

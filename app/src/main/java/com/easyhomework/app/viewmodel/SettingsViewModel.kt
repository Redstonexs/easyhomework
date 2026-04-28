package com.easyhomework.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.easyhomework.app.model.LLMConfig
import com.easyhomework.app.network.LLMRepository
import com.easyhomework.app.util.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)
    private val llmRepository = LLMRepository()

    private val _config = MutableStateFlow(preferencesManager.getLLMConfig())
    val config: StateFlow<LLMConfig> = _config.asStateFlow()

    private val _saveMessage = MutableStateFlow<String?>(null)
    val saveMessage: StateFlow<String?> = _saveMessage.asStateFlow()

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

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

    fun fetchModels() {
        val config = _config.value
        if (config.apiKey.isBlank() || config.apiEndpoint.isBlank()) {
            _saveMessage.value = "请先填写 API 端点和密钥"
            return
        }

        _isFetchingModels.value = true
        viewModelScope.launch {
            val result = llmRepository.fetchModels(config)
            result.fold(
                onSuccess = { models ->
                    _availableModels.value = models
                    if (models.isEmpty()) {
                        _saveMessage.value = "未找到可用模型"
                    } else {
                        _saveMessage.value = "找到 ${models.size} 个模型"
                    }
                },
                onFailure = { error ->
                    _saveMessage.value = "获取模型失败: ${error.message}"
                }
            )
            _isFetchingModels.value = false
        }
    }

    fun selectModel(modelName: String) {
        _config.value = _config.value.copy(modelName = modelName)
    }
}

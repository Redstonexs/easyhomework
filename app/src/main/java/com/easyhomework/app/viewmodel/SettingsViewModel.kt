package com.easyhomework.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.easyhomework.app.model.ApiType
import com.easyhomework.app.model.CapabilitySource
import com.easyhomework.app.model.LLMConfig
import com.easyhomework.app.model.ModelInfo
import com.easyhomework.app.network.LLMRepository
import com.easyhomework.app.util.PreferencesManager
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)
    private val llmRepository = LLMRepository()

    private val _config = MutableStateFlow(preferencesManager.getActiveConfig())
    val config: StateFlow<LLMConfig> = _config.asStateFlow()

    private val _providerConfigs = MutableStateFlow(preferencesManager.loadProviderConfigs())
    val providerConfigs: StateFlow<List<LLMConfig>> = _providerConfigs.asStateFlow()

    private val _activeProviderId = MutableStateFlow(preferencesManager.activeProviderId)
    val activeProviderId: StateFlow<String> = _activeProviderId.asStateFlow()

    private val _saveMessage = MutableStateFlow<String?>(null)
    val saveMessage: StateFlow<String?> = _saveMessage.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    private val _autoSubmitDetectedRegion = MutableStateFlow(preferencesManager.autoSubmitDetectedRegion)
    val autoSubmitDetectedRegion: StateFlow<Boolean> = _autoSubmitDetectedRegion.asStateFlow()

    init {
        // If no providers exist, create a default one
        if (_providerConfigs.value.isEmpty()) {
            val defaultConfig = LLMConfig(
                id = UUID.randomUUID().toString(),
                name = "默认配置",
            )
            _providerConfigs.value = listOf(defaultConfig)
            _activeProviderId.value = defaultConfig.id
            _config.value = defaultConfig
        }
    }

    fun updateConfig(config: LLMConfig) {
        _config.value = config
    }

    fun updateModelName(modelName: String) {
        val modelInfo = _availableModels.value.find { it.id == modelName }
        _config.value = if (modelInfo != null) {
            _config.value.withModelInfo(modelInfo)
        } else {
            _config.value.withAutoModelCapabilities(modelName)
        }
    }

    fun updateApiType(apiType: ApiType, apiPath: String) {
        _availableModels.value = emptyList()
        val current = _config.value.copy(apiType = apiType, apiPath = apiPath)
        _config.value = if (current.visionCapabilitySource == CapabilitySource.MANUAL) {
            current.copy(
                supportsFunctionCalling = LLMConfig.modelSupportsFunctionCalling(apiType, current.modelName),
            )
        } else {
            current.withAutoModelCapabilities(current.modelName)
        }
    }

    fun selectProvider(id: String) {
        val config = _providerConfigs.value.find { it.id == id } ?: return
        _activeProviderId.value = id
        _config.value = config
        _availableModels.value = emptyList()
        preferencesManager.activeProviderId = id
    }

    fun addNewProvider() {
        val newConfig = LLMConfig(
            id = UUID.randomUUID().toString(),
            name = "新配置 ${_providerConfigs.value.size + 1}",
        )
        val updatedList = _providerConfigs.value + newConfig
        _providerConfigs.value = updatedList
        _activeProviderId.value = newConfig.id
        _config.value = newConfig
        _availableModels.value = emptyList()
        preferencesManager.saveProviderConfigs(updatedList)
        preferencesManager.activeProviderId = newConfig.id
    }

    fun deleteProvider(id: String) {
        if (_providerConfigs.value.size <= 1) {
            _saveMessage.value = "至少保留一个配置"
            return
        }
        val updatedList = _providerConfigs.value.filter { it.id != id }
        _providerConfigs.value = updatedList
        preferencesManager.saveProviderConfigs(updatedList)

        // If deleted the active one, switch to first
        if (_activeProviderId.value == id) {
            val first = updatedList.firstOrNull()
            if (first != null) {
                _activeProviderId.value = first.id
                _config.value = first
                _availableModels.value = emptyList()
                preferencesManager.activeProviderId = first.id
            }
        }
    }

    fun updateProviderName(id: String, name: String) {
        val updatedList = _providerConfigs.value.map {
            if (it.id == id) it.copy(name = name) else it
        }
        _providerConfigs.value = updatedList
        if (_activeProviderId.value == id) {
            _config.value = _config.value.copy(name = name)
        }
    }

    fun saveConfig() {
        validateConfig()?.let { error ->
            _saveMessage.value = error
            return
        }

        val currentConfig = _config.value
        val updatedList = _providerConfigs.value.map {
            if (it.id == currentConfig.id) currentConfig else it
        }
        _providerConfigs.value = updatedList
        preferencesManager.saveProviderConfigs(updatedList)
        preferencesManager.activeProviderId = currentConfig.id

        // Also save miniBall globally
        preferencesManager.miniBall = currentConfig.miniBall

        _saveMessage.value = "设置已保存"
    }

    fun updateAutoSubmitDetectedRegion(enabled: Boolean) {
        _autoSubmitDetectedRegion.value = enabled
        preferencesManager.autoSubmitDetectedRegion = enabled
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
                    val modelInfo = models.find { it.id == _config.value.modelName }
                    val currentModelUpdated = modelInfo != null
                    if (modelInfo != null) {
                        _config.value = _config.value.withModelInfo(modelInfo)
                    }
                    if (models.isEmpty()) {
                        _saveMessage.value = "未找到可用模型"
                    } else if (currentModelUpdated) {
                        _saveMessage.value = "找到 ${models.size} 个模型，已更新当前模型能力"
                    } else {
                        _saveMessage.value = "找到 ${models.size} 个模型"
                    }
                },
                onFailure = { error ->
                    _saveMessage.value = "获取模型失败: ${error.message}"
                },
            )
            _isFetchingModels.value = false
        }
    }

    private fun LLMConfig.withModelInfo(modelInfo: ModelInfo): LLMConfig {
        val hasManualVisionOverride = visionCapabilitySource == CapabilitySource.MANUAL
        return copy(
            modelName = modelInfo.id,
            supportsVision = if (hasManualVisionOverride) supportsVision else modelInfo.supportsVision,
            visionCapabilitySource = if (hasManualVisionOverride) CapabilitySource.MANUAL else modelInfo.visionCapabilitySource,
            supportsFunctionCalling = modelInfo.supportsFunctionCalling,
            supportsThinking = modelInfo.supportsThinking,
        )
    }

    private fun LLMConfig.withAutoModelCapabilities(modelName: String): LLMConfig {
        val hasManualVisionOverride = visionCapabilitySource == CapabilitySource.MANUAL
        return copy(
            modelName = modelName,
            supportsVision = if (hasManualVisionOverride) supportsVision else LLMConfig.modelSupportsVision(modelName),
            visionCapabilitySource = if (hasManualVisionOverride) CapabilitySource.MANUAL else CapabilitySource.AUTO,
            supportsFunctionCalling = LLMConfig.modelSupportsFunctionCalling(apiType, modelName),
        )
    }
}

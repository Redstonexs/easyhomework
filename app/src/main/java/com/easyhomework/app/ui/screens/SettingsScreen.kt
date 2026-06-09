@file:Suppress("FunctionName", "FunctionNaming", "ktlint:standard:function-naming")

package com.easyhomework.app.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Minimize
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easyhomework.app.model.ApiType
import com.easyhomework.app.model.CapabilitySource
import com.easyhomework.app.model.LLMConfig
import com.easyhomework.app.model.ModelInfo
import com.easyhomework.app.model.PromptTemplates
import com.easyhomework.app.model.ThinkingDepth
import com.easyhomework.app.service.FloatingBallService
import com.easyhomework.app.ui.theme.AccentCyan
import com.easyhomework.app.ui.theme.AccentGreen
import com.easyhomework.app.ui.theme.AccentOrange
import com.easyhomework.app.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    isServiceRunning: Boolean,
    onToggleService: (Boolean) -> Unit,
    onNavigateToHistory: () -> Unit,
    onResyncState: () -> Unit = {},
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val providerConfigs by viewModel.providerConfigs.collectAsStateWithLifecycle()
    val activeProviderId by viewModel.activeProviderId.collectAsStateWithLifecycle()
    val saveMessage by viewModel.saveMessage.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val isFetchingModels by viewModel.isFetchingModels.collectAsStateWithLifecycle()
    val autoSubmitDetectedRegion by viewModel.autoSubmitDetectedRegion.collectAsStateWithLifecycle()
    val latestCrashReport by viewModel.latestCrashReport.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current

    var serviceEnabled by remember { mutableStateOf(isServiceRunning) }
    var showApiKey by remember { mutableStateOf(false) }
    var showModelDropdown by remember { mutableStateOf(false) }
    var showProviderMenu by remember { mutableStateOf(false) }
    var expandProvider by remember { mutableStateOf(true) }
    var expandPrompt by remember { mutableStateOf(false) }
    var expandAdvanced by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onResyncState()
    }

    LaunchedEffect(isServiceRunning) {
        serviceEnabled = isServiceRunning
    }

    LaunchedEffect(activeProviderId) {
        showApiKey = false
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(saveMessage) {
        saveMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSaveMessage()
        }
    }

    val setupStatus = remember(config) {
        buildSetupStatus(config = config)
    }
    val saveSettings = {
        val error = viewModel.validateConfig()
        viewModel.saveConfig()
        if (error == null) {
            FloatingBallService.getInstance()?.recreateFloatingBall()
        }
    }
    val onFloatingBallChanged = { enabled: Boolean ->
        serviceEnabled = enabled
        onToggleService(enabled)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader(setupStatus = setupStatus)

            FloatingBallControlSection(
                setupStatus = setupStatus,
                serviceEnabled = serviceEnabled,
                onServiceChanged = onFloatingBallChanged,
            )

            QuickSetupSection(
                config = config,
                setupStatus = setupStatus,
                showModelDropdown = showModelDropdown,
                availableModels = availableModels,
                isFetchingModels = isFetchingModels,
                onModelNameChanged = viewModel::updateModelName,
                onFetchModels = {
                    viewModel.fetchModels()
                    showModelDropdown = true
                },
                onModelDropdownChanged = { showModelDropdown = it },
                onSave = saveSettings,
            )

            UsageSection(
                config = config,
                autoSubmitDetectedRegion = autoSubmitDetectedRegion,
                onMiniBallChanged = { viewModel.updateConfig(config.copy(miniBall = it)) },
                onAutoSubmitChanged = viewModel::updateAutoSubmitDetectedRegion,
                onNavigateToHistory = onNavigateToHistory,
            )

            DiagnosticSection(
                latestCrashReport = latestCrashReport,
                latestCrashPath = viewModel.latestCrashPath,
                onCopyCrashReport = { report ->
                    clipboardManager.setText(AnnotatedString(report))
                    viewModel.notifyCrashReportCopied()
                },
                onClearCrashReport = viewModel::clearCrashReport,
            )

            CollapsibleSettingsSection(
                title = "AI 提供商与接口",
                subtitle = providerSummary(config),
                icon = Icons.Outlined.Cloud,
                expanded = expandProvider,
                onToggle = { expandProvider = !expandProvider },
            ) {
                ProviderAndEndpointContent(
                    config = config,
                    providerConfigs = providerConfigs,
                    activeProviderId = activeProviderId,
                    showApiKey = showApiKey,
                    showProviderMenu = showProviderMenu,
                    onProviderMenuChanged = { showProviderMenu = it },
                    onSelectProvider = viewModel::selectProvider,
                    onAddProvider = viewModel::addNewProvider,
                    onDeleteProvider = viewModel::deleteProvider,
                    onProviderNameChanged = { viewModel.updateProviderName(config.id, it) },
                    onApiKeyChanged = { viewModel.updateConfig(config.copy(apiKey = it)) },
                    onShowApiKeyChanged = { showApiKey = it },
                    onApiTypeChanged = { type ->
                        val newPath = when (type) {
                            ApiType.OPENAI -> "/v1/chat/completions"
                            ApiType.ANTHROPIC -> "/v1/messages"
                        }
                        viewModel.updateApiType(type, newPath)
                    },
                    onEndpointChanged = { viewModel.updateConfig(config.copy(apiEndpoint = it)) },
                    onPathChanged = { viewModel.updateConfig(config.copy(apiPath = it)) },
                )
            }

            CollapsibleSettingsSection(
                title = "系统提示词",
                subtitle = promptSummary(config.systemPrompt),
                icon = Icons.Outlined.Tune,
                expanded = expandPrompt,
                onToggle = { expandPrompt = !expandPrompt },
            ) {
                PromptContent(
                    config = config,
                    onPromptChanged = { viewModel.updateConfig(config.copy(systemPrompt = it)) },
                    onResetPrompt = {
                        viewModel.updateConfig(
                            config.copy(systemPrompt = PromptTemplates.DEFAULT_SYSTEM_PROMPT),
                        )
                    },
                )
            }

            CollapsibleSettingsSection(
                title = "高级参数",
                subtitle = advancedSummary(config),
                icon = Icons.Outlined.DataUsage,
                expanded = expandAdvanced,
                onToggle = { expandAdvanced = !expandAdvanced },
            ) {
                AdvancedContent(
                    config = config,
                    onTemperatureChanged = {
                        viewModel.updateConfig(config.copy(temperature = (it * 10).toInt() / 10f))
                    },
                    onMaxTokensChanged = { tokens ->
                        viewModel.updateConfig(config.copy(maxTokens = tokens))
                    },
                    onStreamChanged = { viewModel.updateConfig(config.copy(stream = it)) },
                    onVisionChanged = {
                        viewModel.updateConfig(
                            config.copy(
                                supportsVision = it,
                                visionCapabilitySource = CapabilitySource.MANUAL,
                            ),
                        )
                    },
                    onThinkingChanged = { viewModel.updateConfig(config.copy(thinkingEnabled = it)) },
                    onThinkingDepthChanged = { viewModel.updateConfig(config.copy(thinkingDepth = it)) },
                )
            }

            Button(
                onClick = saveSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("保存设置", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Footer()
        }
    }
}

@Composable
private fun SettingsHeader(setupStatus: SetupStatus) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp),
    ) {
        Text(
            "EasyHomework",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            if (setupStatus.isReady) "配置已就绪，可以从悬浮球开始搜题" else "先完成基础配置，再开始搜题",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FloatingBallControlSection(
    setupStatus: SetupStatus,
    serviceEnabled: Boolean,
    onServiceChanged: (Boolean) -> Unit,
) {
    SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle(
                title = "搜题总开关",
                subtitle = if (serviceEnabled) {
                    "悬浮球已开启，可在任意应用中截屏搜题"
                } else if (setupStatus.isReady) {
                    "开启悬浮球后，即可在其他应用中截屏搜题"
                } else {
                    "先完成基础配置，再开启悬浮球"
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            ToggleSettingRow(
                title = "悬浮球",
                subtitle = if (serviceEnabled) {
                    "全局搜题入口已启用"
                } else {
                    "作为应用级总开关独立控制"
                },
                icon = Icons.Outlined.SmartToy,
                iconTint = if (serviceEnabled) AccentGreen else MaterialTheme.colorScheme.outline,
                checked = serviceEnabled,
                onCheckedChange = onServiceChanged,
            )
        }
    }
}

@Composable
private fun QuickSetupSection(
    config: LLMConfig,
    setupStatus: SetupStatus,
    showModelDropdown: Boolean,
    availableModels: List<ModelInfo>,
    isFetchingModels: Boolean,
    onModelNameChanged: (String) -> Unit,
    onFetchModels: () -> Unit,
    onModelDropdownChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            SetupStatusBlock(setupStatus = setupStatus, config = config)

            Spacer(modifier = Modifier.height(16.dp))

            ModelSelectorRow(
                config = config,
                showModelDropdown = showModelDropdown,
                availableModels = availableModels,
                isFetchingModels = isFetchingModels,
                onModelNameChanged = onModelNameChanged,
                onFetchModels = onFetchModels,
                onDropdownChanged = onModelDropdownChanged,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("保存设置", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupStatusBlock(setupStatus: SetupStatus, config: LLMConfig) {
    val accent = if (setupStatus.isReady) AccentGreen else AccentOrange

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (setupStatus.isReady) Icons.Filled.Check else Icons.Outlined.Tune,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    setupStatus.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    setupStatus.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (setupStatus.issues.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                setupStatus.issues.forEach { issue ->
                    StatusPill(text = issue, color = accent)
                }
            }
        } else {
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(text = config.apiType.displayName, color = accent)
                StatusPill(text = config.modelName, color = accent)
                if (config.supportsVisionInput()) {
                    StatusPill(text = "支持图片", color = AccentCyan)
                }
            }
        }
    }
}

@Composable
private fun ModelSelectorRow(
    config: LLMConfig,
    showModelDropdown: Boolean,
    availableModels: List<ModelInfo>,
    isFetchingModels: Boolean,
    onModelNameChanged: (String) -> Unit,
    onFetchModels: () -> Unit,
    onDropdownChanged: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            SettingsTextField(
                label = "模型名称",
                value = config.modelName,
                onValueChange = onModelNameChanged,
                placeholder = if (config.apiType == ApiType.OPENAI) "gpt-4o" else "claude-sonnet-4-20250514",
                icon = Icons.Outlined.SmartToy,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box {
            OutlinedButton(
                onClick = onFetchModels,
                modifier = Modifier.height(56.dp),
                enabled = !isFetchingModels,
                shape = RoundedCornerShape(8.dp),
            ) {
                if (isFetchingModels) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "获取模型",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            ModelDropdownMenu(
                expanded = showModelDropdown && availableModels.isNotEmpty(),
                availableModels = availableModels,
                selectedModel = config.modelName,
                onDismiss = { onDropdownChanged(false) },
                onModelSelected = { modelId ->
                    onModelNameChanged(modelId)
                    onDropdownChanged(false)
                },
            )
        }
    }
}

@Composable
private fun ModelDropdownMenu(
    expanded: Boolean,
    availableModels: List<ModelInfo>,
    selectedModel: String,
    onDismiss: () -> Unit,
    onModelSelected: (String) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .heightIn(max = 400.dp)
            .widthIn(min = 280.dp),
    ) {
        val visionModels = availableModels.filter { it.supportsVision }
        val otherModels = availableModels.filter { !it.supportsVision }

        if (visionModels.isNotEmpty()) {
            DropdownHeader("支持图像输入")
            visionModels.forEach { model ->
                ModelDropdownItem(
                    model = model,
                    selectedModel = selectedModel,
                    onModelSelected = onModelSelected,
                )
            }
        }

        if (otherModels.isNotEmpty() && visionModels.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            DropdownHeader("其他模型")
        }

        otherModels.forEach { model ->
            ModelDropdownItem(
                model = model,
                selectedModel = selectedModel,
                onModelSelected = onModelSelected,
            )
        }
    }
}

@Composable
private fun DropdownHeader(text: String) {
    DropdownMenuItem(
        text = {
            Text(
                text,
                color = MaterialTheme.colorScheme.outline,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        onClick = {},
        enabled = false,
    )
}

@Composable
private fun ModelDropdownItem(
    model: ModelInfo,
    selectedModel: String,
    onModelSelected: (String) -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.id,
                    color = if (model.id == selectedModel) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontSize = 14.sp,
                )
                if (model.supportsVision) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("图片", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        onClick = { onModelSelected(model.id) },
        trailingIcon = {
            if (model.id == selectedModel) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        },
    )
}

@Composable
private fun UsageSection(
    config: LLMConfig,
    autoSubmitDetectedRegion: Boolean,
    onMiniBallChanged: (Boolean) -> Unit,
    onAutoSubmitChanged: (Boolean) -> Unit,
    onNavigateToHistory: () -> Unit,
) {
    SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle(title = "常用操作", subtitle = "日常使用中最常改的开关")
            Spacer(modifier = Modifier.height(8.dp))
            ToggleSettingRow(
                title = "迷你悬浮球",
                subtitle = "更小、更透明，减少遮挡",
                icon = Icons.Outlined.Minimize,
                iconTint = AccentCyan,
                checked = config.miniBall,
                onCheckedChange = onMiniBallChanged,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ToggleSettingRow(
                title = "自动提交识别区域",
                subtitle = "高置信度框选后直接搜题",
                icon = Icons.Outlined.Tune,
                iconTint = AccentOrange,
                checked = autoSubmitDetectedRegion,
                onCheckedChange = onAutoSubmitChanged,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            NavigationSettingRow(
                title = "搜题历史",
                subtitle = "查看之前的搜题记录",
                icon = Icons.Outlined.History,
                iconTint = AccentCyan,
                onClick = onNavigateToHistory,
            )
        }
    }
}

@Composable
private fun DiagnosticSection(
    latestCrashReport: String?,
    latestCrashPath: String,
    onCopyCrashReport: (String) -> Unit,
    onClearCrashReport: () -> Unit,
) {
    val report = latestCrashReport ?: return
    SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle(title = "诊断日志", subtitle = "检测到上次异常退出，可复制完整崩溃栈")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                latestCrashPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onCopyCrashReport(report) },
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("复制日志")
                }
                OutlinedButton(
                    onClick = onClearCrashReport,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("清除")
                }
            }
        }
    }
}

@Composable
private fun ProviderAndEndpointContent(
    config: LLMConfig,
    providerConfigs: List<LLMConfig>,
    activeProviderId: String,
    showApiKey: Boolean,
    showProviderMenu: Boolean,
    onProviderMenuChanged: (Boolean) -> Unit,
    onSelectProvider: (String) -> Unit,
    onAddProvider: () -> Unit,
    onDeleteProvider: (String) -> Unit,
    onProviderNameChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onShowApiKeyChanged: (Boolean) -> Unit,
    onApiTypeChanged: (ApiType) -> Unit,
    onEndpointChanged: (String) -> Unit,
    onPathChanged: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            providerConfigs.forEach { provider ->
                FilterChip(
                    selected = provider.id == activeProviderId,
                    onClick = { onSelectProvider(provider.id) },
                    label = {
                        Text(
                            provider.name.ifBlank { "未命名" },
                            maxLines = 1,
                            fontSize = 13.sp,
                        )
                    },
                )
            }
        }

        IconButton(
            onClick = onAddProvider,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "添加提供商",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }

        Box {
            IconButton(
                onClick = { onProviderMenuChanged(true) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多选项",
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = showProviderMenu,
                onDismissRequest = { onProviderMenuChanged(false) },
            ) {
                DropdownMenuItem(
                    text = { Text("删除当前配置") },
                    onClick = {
                        onDeleteProvider(activeProviderId)
                        onProviderMenuChanged(false)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    SettingsTextField(
        label = "配置名称",
        value = config.name,
        onValueChange = onProviderNameChanged,
        placeholder = "默认配置",
        icon = Icons.Outlined.Tune,
    )

    Spacer(modifier = Modifier.height(12.dp))

    SettingsTextField(
        label = "API 密钥",
        value = config.apiKey,
        onValueChange = onApiKeyChanged,
        placeholder = if (config.apiType == ApiType.OPENAI) "sk-..." else "sk-ant-...",
        icon = Icons.Outlined.Key,
        isPassword = !showApiKey,
        trailingIcon = {
            IconButton(onClick = { onShowApiKeyChanged(!showApiKey) }) {
                Icon(
                    if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = "切换密钥显示",
                )
            }
        },
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        "API 类型",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ApiType.entries.forEach { type ->
            FilterChip(
                selected = config.apiType == type,
                onClick = { onApiTypeChanged(type) },
                label = { Text(type.displayName) },
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    SettingsTextField(
        label = "API 端点",
        value = config.apiEndpoint,
        onValueChange = onEndpointChanged,
        placeholder = if (config.apiType == ApiType.OPENAI) {
            "https://api.openai.com"
        } else {
            "https://api.anthropic.com"
        },
        icon = Icons.Outlined.Cloud,
    )

    Spacer(modifier = Modifier.height(12.dp))

    SettingsTextField(
        label = "API 路径",
        value = config.apiPath,
        onValueChange = onPathChanged,
        placeholder = if (config.apiType == ApiType.OPENAI) "/v1/chat/completions" else "/v1/messages",
        icon = Icons.Outlined.Route,
    )
}

@Composable
private fun PromptContent(
    config: LLMConfig,
    onPromptChanged: (String) -> Unit,
    onResetPrompt: () -> Unit,
) {
    OutlinedTextField(
        value = config.systemPrompt,
        onValueChange = onPromptChanged,
        modifier = Modifier.fillMaxWidth(),
        minLines = 4,
        maxLines = 8,
        label = { Text("自定义提示词") },
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(
        onClick = onResetPrompt,
        shape = RoundedCornerShape(8.dp),
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("恢复默认")
    }
}

@Composable
private fun AdvancedContent(
    config: LLMConfig,
    onTemperatureChanged: (Float) -> Unit,
    onMaxTokensChanged: (Int) -> Unit,
    onStreamChanged: (Boolean) -> Unit,
    onVisionChanged: (Boolean) -> Unit,
    onThinkingChanged: (Boolean) -> Unit,
    onThinkingDepthChanged: (ThinkingDepth) -> Unit,
) {
    Text(
        "Temperature: ${String.format("%.1f", config.temperature)}",
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = config.temperature,
        onValueChange = onTemperatureChanged,
        valueRange = 0f..2f,
        steps = 19,
    )

    Spacer(modifier = Modifier.height(12.dp))

    SettingsTextField(
        label = "最大 Tokens",
        value = config.maxTokens.toString(),
        onValueChange = { value ->
            value.toIntOrNull()?.let(onMaxTokensChanged)
        },
        placeholder = "2048",
        icon = Icons.Outlined.DataUsage,
        keyboardType = KeyboardType.Number,
    )

    Spacer(modifier = Modifier.height(16.dp))

    ToggleSettingRow(
        title = "流式输出",
        subtitle = "实时显示模型回答",
        icon = Icons.Outlined.Tune,
        iconTint = AccentCyan,
        checked = config.stream,
        onCheckedChange = onStreamChanged,
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    ToggleSettingRow(
        title = "支持图像输入",
        subtitle = visionSupportDescription(config.visionCapabilitySource),
        icon = Icons.Filled.Visibility,
        iconTint = AccentCyan,
        checked = config.supportsVisionInput(),
        onCheckedChange = onVisionChanged,
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    ToggleSettingRow(
        title = "思考模式",
        subtitle = when (config.apiType) {
            ApiType.OPENAI -> "支持 o1/o3/DeepSeek-R1 等模型"
            ApiType.ANTHROPIC -> "Claude Extended Thinking"
        },
        icon = Icons.Outlined.SmartToy,
        iconTint = AccentOrange,
        checked = config.thinkingEnabled,
        onCheckedChange = onThinkingChanged,
    )

    AnimatedVisibility(visible = config.thinkingEnabled) {
        Column {
            Spacer(modifier = Modifier.height(12.dp))
            Text("思考深度", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            ThinkingDepthChips(
                selectedDepth = config.thinkingDepth,
                apiType = config.apiType,
                onDepthSelected = onThinkingDepthChanged,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                thinkingDepthDescription(config.thinkingDepth),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThinkingDepthChips(
    selectedDepth: ThinkingDepth,
    apiType: ApiType,
    onDepthSelected: (ThinkingDepth) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThinkingDepth.entries.forEach { depth ->
            val isSelected = selectedDepth == depth
            FilterChip(
                modifier = Modifier.widthIn(min = 64.dp),
                selected = isSelected,
                onClick = { onDepthSelected(depth) },
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            depth.displayName,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                        if (depth != ThinkingDepth.NONE) {
                            Text(
                                when (apiType) {
                                    ApiType.OPENAI -> depth.openaiReasoningEffort
                                    ApiType.ANTHROPIC -> "${depth.budgetTokens / 1024}K"
                                },
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun CollapsibleSettingsSection(
    title: String,
    subtitle: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsIcon(icon = icon, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(icon = icon, tint = iconTint)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun NavigationSettingRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(icon = icon, tint = iconTint)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatusPill(text: String, color: Color) {
    val textColor = if (color.luminance() > 0.5f) Color.Black else Color.White
    Text(
        text,
        modifier = Modifier
            .background(color.copy(alpha = 0.90f), RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        color = textColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun SettingsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingsIcon(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector? = null,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.outline) },
        leadingIcon = icon?.let { leadingIcon ->
            {
                Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        },
        trailingIcon = trailingIcon,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
    )
}

@Composable
private fun Footer() {
    val context = LocalContext.current
    val appVersionName = remember(context) { context.appVersionName() }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "EasyHomework v$appVersionName",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )
    Text(
        "支持 OpenAI 兼容 API 及 Anthropic Claude API",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
    Spacer(modifier = Modifier.height(28.dp))
}

private fun Context.appVersionName(): String {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
    return packageInfo.versionName ?: "unknown"
}

private data class SetupStatus(
    val isReady: Boolean,
    val title: String,
    val description: String,
    val issues: List<String>,
)

private fun buildSetupStatus(config: LLMConfig): SetupStatus {
    val issues = buildList {
        if (config.apiKey.isBlank()) add("填写 API 密钥")
        if (config.modelName.isBlank()) add("填写模型名称")
        if (config.apiEndpoint.isBlank()) add("补全 API 端点")
    }

    return if (issues.isEmpty()) {
        SetupStatus(
            isReady = true,
            title = "可以开始搜题",
            description = "${config.apiType.displayName} · ${config.modelName}",
            issues = emptyList(),
        )
    } else {
        SetupStatus(
            isReady = false,
            title = "还差 ${issues.size} 项配置",
            description = issues.joinToString("、"),
            issues = issues,
        )
    }
}

private fun providerSummary(config: LLMConfig): String {
    val endpoint = config.apiEndpoint
        .removePrefix("https://")
        .removePrefix("http://")
        .ifBlank { "未填写端点" }
    return "${config.apiType.displayName} · $endpoint"
}

private fun promptSummary(prompt: String): String {
    return if (prompt.trim() == PromptTemplates.DEFAULT_SYSTEM_PROMPT) {
        "使用默认解题提示词"
    } else {
        "已自定义提示词"
    }
}

private fun advancedSummary(config: LLMConfig): String {
    val stream = if (config.stream) "流式" else "非流式"
    val vision = if (config.supportsVisionInput()) "图片开启" else "图片关闭"
    val thinking = if (config.thinkingEnabled) "思考开启" else "思考关闭"
    return "$stream · $vision · $thinking"
}

private fun visionSupportDescription(source: CapabilitySource): String {
    return when (source) {
        CapabilitySource.API -> "模型接口明确支持图像输入，可手动覆盖"
        CapabilitySource.API_UNSUPPORTED -> "模型接口明确不支持图像输入，可手动覆盖"
        CapabilitySource.AUTO -> "优先接口识别；接口缺失时按模型名称自动判断"
        CapabilitySource.MANUAL -> "已手动设置，截屏后可直接发送图片给模型"
    }
}

private fun thinkingDepthDescription(depth: ThinkingDepth): String {
    return when (depth) {
        ThinkingDepth.NONE -> "不使用思考模式，适合不支持该功能的模型"
        ThinkingDepth.LOW -> "快速思考，适合简单题目"
        ThinkingDepth.MEDIUM -> "平衡模式，适合大多数题目"
        ThinkingDepth.HIGH -> "深度思考，适合复杂题目"
        ThinkingDepth.XHIGH -> "极深思考，适合竞赛难题"
    }
}

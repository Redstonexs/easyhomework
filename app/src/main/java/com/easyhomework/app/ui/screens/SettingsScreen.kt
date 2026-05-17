package com.easyhomework.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Circle
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
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Minimize
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easyhomework.app.model.ApiType
import com.easyhomework.app.model.LLMConfig
import com.easyhomework.app.model.ThinkingDepth
import com.easyhomework.app.service.FloatingBallService
import com.easyhomework.app.ui.theme.AccentCyan
import com.easyhomework.app.ui.theme.AccentOrange
import com.easyhomework.app.ui.theme.PrimaryBlue
import com.easyhomework.app.ui.theme.PrimaryPurple
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
    val config by viewModel.config.collectAsState()
    val providerConfigs by viewModel.providerConfigs.collectAsState()
    val activeProviderId by viewModel.activeProviderId.collectAsState()
    val saveMessage by viewModel.saveMessage.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val isFetchingModels by viewModel.isFetchingModels.collectAsState()
    var serviceEnabled by remember { mutableStateOf(isServiceRunning) }
    var showApiKey by remember { mutableStateOf(false) }
    var expandAdvanced by remember { mutableStateOf(false) }
    var showModelDropdown by remember { mutableStateOf(false) }
    var showProviderMenu by remember { mutableStateOf(false) }

    // Re-sync state when screen becomes visible (e.g., returning from history)
    LaunchedEffect(Unit) {
        onResyncState()
    }

    // Update local state when external state changes
    LaunchedEffect(isServiceRunning) {
        serviceEnabled = isServiceRunning
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(saveMessage) {
        saveMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSaveMessage()
        }
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
            // ---- Header ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    )
                    .padding(horizontal = 24.dp, vertical = 32.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.SmartToy,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            "EasyHomework",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "AI 搜题助手",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---- Floating Ball Toggle ----
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (serviceEnabled) {
                                    Brush.linearGradient(listOf(PrimaryPurple, PrimaryBlue))
                                } else {
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            MaterialTheme.colorScheme.surfaceVariant,
                                        ),
                                    )
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Circle, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("悬浮球", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (serviceEnabled) "点击截屏搜题 · 长按关闭" else "开启后可在任何应用中搜题",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = serviceEnabled,
                        onCheckedChange = { enabled ->
                            serviceEnabled = enabled
                            onToggleService(enabled)
                        },
                    )
                }
            }

            // ---- Mini Ball Toggle ----
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsIcon(Icons.Outlined.Minimize, AccentCyan)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("迷你悬浮球", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "更小更透明，减少遮挡",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = config.miniBall,
                        onCheckedChange = { mini ->
                            viewModel.updateConfig(config.copy(miniBall = mini))
                        },
                    )
                }
            }

            // ---- History Button ----
            SettingsCard(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { onNavigateToHistory() },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsIcon(Icons.Outlined.History, AccentCyan)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("搜题历史", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "查看之前的搜题记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                }
            }

            // ---- Provider Selection ----
            SectionHeader("AI 提供商")

            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            providerConfigs.forEach { provider ->
                                val isActive = provider.id == activeProviderId
                                FilterChip(
                                    selected = isActive,
                                    onClick = { viewModel.selectProvider(provider.id) },
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
                            onClick = { viewModel.addNewProvider() },
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
                                onClick = { showProviderMenu = true },
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
                                onDismissRequest = { showProviderMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("删除当前配置") },
                                    onClick = {
                                        viewModel.deleteProvider(activeProviderId)
                                        showProviderMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    },
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = config.name,
                        onValueChange = { viewModel.updateProviderName(config.id, it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("配置名称") },
                        leadingIcon = {
                            @Suppress("DEPRECATION")
                            Icon(Icons.Outlined.Label, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        singleLine = true,
                    )
                }
            }

            // ---- API Type Selection ----
            SectionHeader("API 配置")

            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
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
                                onClick = {
                                    val newPath = when (type) {
                                        ApiType.OPENAI -> "/v1/chat/completions"
                                        ApiType.ANTHROPIC -> "/v1/messages"
                                    }
                                    viewModel.updateConfig(config.copy(apiType = type, apiPath = newPath))
                                },
                                label = { Text(type.displayName) },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    SettingsTextField(
                        label = "API 端点",
                        value = config.apiEndpoint,
                        onValueChange = { viewModel.updateConfig(config.copy(apiEndpoint = it)) },
                        placeholder = if (config.apiType == ApiType.OPENAI) "https://api.openai.com" else "https://api.anthropic.com",
                        icon = Icons.Outlined.Cloud,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    SettingsTextField(
                        label = "API 路径",
                        value = config.apiPath,
                        onValueChange = { viewModel.updateConfig(config.copy(apiPath = it)) },
                        placeholder = if (config.apiType == ApiType.OPENAI) "/v1/chat/completions" else "/v1/messages",
                        icon = Icons.Outlined.Route,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    SettingsTextField(
                        label = "API 密钥",
                        value = config.apiKey,
                        onValueChange = { viewModel.updateConfig(config.copy(apiKey = it)) },
                        placeholder = if (config.apiType == ApiType.OPENAI) "sk-..." else "sk-ant-...",
                        icon = Icons.Outlined.Key,
                        isPassword = !showApiKey,
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = "Toggle visibility",
                                )
                            }
                        },
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            SettingsTextField(
                                label = "模型名称",
                                value = config.modelName,
                                onValueChange = {
                                    viewModel.updateConfig(config.copy(modelName = it))
                                },
                                placeholder = if (config.apiType == ApiType.OPENAI) "gpt-4o" else "claude-sonnet-4-20250514",
                                icon = Icons.Outlined.SmartToy,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Box {
                            OutlinedButton(
                                onClick = {
                                    viewModel.fetchModels()
                                    showModelDropdown = true
                                },
                                modifier = Modifier.height(56.dp),
                                enabled = !isFetchingModels,
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

                            DropdownMenu(
                                expanded = showModelDropdown && availableModels.isNotEmpty(),
                                onDismissRequest = { showModelDropdown = false },
                                modifier = Modifier.heightIn(max = 400.dp).widthIn(min = 280.dp),
                            ) {
                                val visionModels = availableModels.filter { it.supportsVision }
                                val otherModels = availableModels.filter { !it.supportsVision }

                                if (visionModels.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "支持图像输入",
                                                color = MaterialTheme.colorScheme.tertiary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        },
                                        onClick = {},
                                        enabled = false,
                                    )
                                    visionModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        model.id,
                                                        color = if (model.id == config.modelName) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurface
                                                        },
                                                        fontSize = 14.sp,
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("👁️", fontSize = 10.sp)
                                                }
                                            },
                                            onClick = {
                                                viewModel.selectModel(
                                                    model.id,
                                                    model.supportsVision,
                                                    model.supportsFunctionCalling,
                                                    model.supportsThinking,
                                                )
                                                showModelDropdown = false
                                            },
                                            trailingIcon = {
                                                if (model.id == config.modelName) {
                                                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                }
                                            },
                                        )
                                    }
                                }

                                if (otherModels.isNotEmpty() && visionModels.isNotEmpty()) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "其他模型",
                                                color = MaterialTheme.colorScheme.outline,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        },
                                        onClick = {},
                                        enabled = false,
                                    )
                                }

                                otherModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                model.id,
                                                color = if (model.id == config.modelName) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                fontSize = 14.sp,
                                            )
                                        },
                                        onClick = {
                                            viewModel.selectModel(
                                                model.id,
                                                model.supportsVision,
                                                model.supportsFunctionCalling,
                                                model.supportsThinking,
                                            )
                                            showModelDropdown = false
                                        },
                                        trailingIcon = {
                                            if (model.id == config.modelName) {
                                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }

                    if (config.supportsVision || LLMConfig.modelSupportsVision(config.modelName)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Visibility,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "该模型支持图像输入，截屏后可直接发送图片",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }

            // ---- System Prompt ----
            SectionHeader("系统提示词")

            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    OutlinedTextField(
                        value = config.systemPrompt,
                        onValueChange = { viewModel.updateConfig(config.copy(systemPrompt = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        label = { Text("自定义提示词") },
                    )
                }
            }

            // ---- Advanced Settings ----
            SettingsCard(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { expandAdvanced = !expandAdvanced },
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsIcon(Icons.Outlined.Tune, AccentOrange)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            "高级设置",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (expandAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }

                    AnimatedVisibility(visible = expandAdvanced) {
                        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                            Text(
                                "Temperature: ${String.format("%.1f", config.temperature)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Slider(
                                value = config.temperature,
                                onValueChange = {
                                    viewModel.updateConfig(config.copy(temperature = (it * 10).toInt() / 10f))
                                },
                                valueRange = 0f..2f,
                                steps = 19,
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            SettingsTextField(
                                label = "最大 Tokens",
                                value = config.maxTokens.toString(),
                                onValueChange = {
                                    it.toIntOrNull()?.let { tokens ->
                                        viewModel.updateConfig(config.copy(maxTokens = tokens))
                                    }
                                },
                                placeholder = "2048",
                                icon = Icons.Outlined.DataUsage,
                                keyboardType = KeyboardType.Number,
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("流式输出", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = config.stream,
                                    onCheckedChange = { viewModel.updateConfig(config.copy(stream = it)) },
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("支持图像输入", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "截屏后可直接发送图片给模型",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                Switch(
                                    checked = config.supportsVision,
                                    onCheckedChange = { viewModel.updateConfig(config.copy(supportsVision = it)) },
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = MaterialTheme.colorScheme.tertiary,
                                    ),
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("思考模式", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        when (config.apiType) {
                                            ApiType.OPENAI -> "支持 o1/o3/DeepSeek-R1 等模型"
                                            ApiType.ANTHROPIC -> "Claude Extended Thinking"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                Switch(
                                    checked = config.thinkingEnabled,
                                    onCheckedChange = { viewModel.updateConfig(config.copy(thinkingEnabled = it)) },
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = MaterialTheme.colorScheme.tertiary,
                                    ),
                                )
                            }

                            AnimatedVisibility(visible = config.thinkingEnabled) {
                                Column {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("思考深度", style = MaterialTheme.typography.bodyMedium)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ThinkingDepth.entries.forEach { depth ->
                                            val isSelected = config.thinkingDepth == depth
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = {
                                                    viewModel.updateConfig(config.copy(thinkingDepth = depth))
                                                },
                                                label = {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text(
                                                            depth.displayName,
                                                            fontSize = 14.sp,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        )
                                                        if (depth != ThinkingDepth.NONE) {
                                                            Text(
                                                                when (config.apiType) {
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

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        when (config.thinkingDepth) {
                                            ThinkingDepth.NONE -> "不使用思考模式，适合不支持该功能的模型"
                                            ThinkingDepth.LOW -> "快速思考，适合简单题目"
                                            ThinkingDepth.MEDIUM -> "平衡模式，适合大多数题目"
                                            ThinkingDepth.HIGH -> "深度思考，适合复杂题目"
                                            ThinkingDepth.XHIGH -> "极深思考，适合竞赛难题"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- Save Button ----
            Button(
                onClick = {
                    val error = viewModel.validateConfig()
                    if (error != null) {
                        // handled via snackbar
                    } else {
                        viewModel.saveConfig()
                        FloatingBallService.getInstance()?.recreateFloatingBall()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                contentPadding = PaddingValues(),
            ) {
                Text("保存设置", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "EasyHomework v1.0.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Text(
                "支持 OpenAI 兼容 API 及 Anthropic Claude API",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ---- Reusable Components ----

@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun SettingsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
    ) {
        content()
    }
}

@Composable
fun SettingsIcon(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
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
        leadingIcon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        trailingIcon = trailingIcon,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
    )
}

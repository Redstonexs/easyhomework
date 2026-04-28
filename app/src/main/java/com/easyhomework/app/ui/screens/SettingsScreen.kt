package com.easyhomework.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.easyhomework.app.model.LLMConfig
import com.easyhomework.app.ui.theme.*
import com.easyhomework.app.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    isServiceRunning: Boolean,
    onToggleService: (Boolean) -> Unit,
    onNavigateToHistory: () -> Unit
) {
    val config by viewModel.config.collectAsState()
    val saveMessage by viewModel.saveMessage.collectAsState()
    var serviceEnabled by remember { mutableStateOf(isServiceRunning) }
    var showApiKey by remember { mutableStateOf(false) }
    var expandAdvanced by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(saveMessage) {
        saveMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSaveMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = DarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // ---- Header with gradient ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                PrimaryPurple.copy(alpha = 0.3f),
                                DarkBackground
                            )
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 32.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(PrimaryPurple, PrimaryBlue)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "AI",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                "EasyHomework",
                                style = MaterialTheme.typography.headlineMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "AI 搜题助手",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // ---- Floating Ball Toggle ----
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (serviceEnabled) Brush.linearGradient(
                                    listOf(PrimaryPurple, PrimaryBlue)
                                )
                                else Brush.linearGradient(
                                    listOf(DarkSurfaceVariant, DarkSurfaceVariant)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Circle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "悬浮球",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            if (serviceEnabled) "点击悬浮球截屏搜题" else "开启后可在任何应用中搜题",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = serviceEnabled,
                        onCheckedChange = { enabled ->
                            serviceEnabled = enabled
                            onToggleService(enabled)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = PrimaryPurple,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = DarkSurfaceVariant
                        )
                    )
                }
            }

            // ---- History Button ----
            SettingsCard(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { onNavigateToHistory() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsIcon(Icons.Outlined.History, AccentCyan)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "搜题历史",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            "查看之前的搜题记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = TextTertiary
                    )
                }
            }

            // ---- API Configuration ----
            SectionHeader("API 配置")

            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // API Endpoint
                    SettingsTextField(
                        label = "API 端点",
                        value = config.apiEndpoint,
                        onValueChange = {
                            viewModel.updateConfig(config.copy(apiEndpoint = it))
                        },
                        placeholder = "https://api.openai.com",
                        icon = Icons.Outlined.Cloud
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // API Path
                    SettingsTextField(
                        label = "API 路径",
                        value = config.apiPath,
                        onValueChange = {
                            viewModel.updateConfig(config.copy(apiPath = it))
                        },
                        placeholder = "/v1/chat/completions",
                        icon = Icons.Outlined.Route
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // API Key
                    SettingsTextField(
                        label = "API 密钥",
                        value = config.apiKey,
                        onValueChange = {
                            viewModel.updateConfig(config.copy(apiKey = it))
                        },
                        placeholder = "sk-...",
                        icon = Icons.Outlined.Key,
                        isPassword = !showApiKey,
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                    contentDescription = "Toggle visibility",
                                    tint = TextSecondary
                                )
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Model Name
                    SettingsTextField(
                        label = "模型名称",
                        value = config.modelName,
                        onValueChange = {
                            viewModel.updateConfig(config.copy(modelName = it))
                        },
                        placeholder = "gpt-4o",
                        icon = Icons.Outlined.SmartToy
                    )
                }
            }

            // ---- System Prompt ----
            SectionHeader("系统提示词")

            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    OutlinedTextField(
                        value = config.systemPrompt,
                        onValueChange = {
                            viewModel.updateConfig(config.copy(systemPrompt = it))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        label = { Text("自定义提示词", color = TextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryPurple,
                            unfocusedBorderColor = DarkSurfaceVariant,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = PrimaryPurple,
                            focusedLabelColor = PrimaryPurple,
                            unfocusedLabelColor = TextSecondary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            // ---- Advanced Settings ----
            SettingsCard(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { expandAdvanced = !expandAdvanced }
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsIcon(Icons.Outlined.Tune, AccentOrange)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            "高级设置",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (expandAdvanced) Icons.Filled.ExpandLess
                            else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = TextTertiary
                        )
                    }

                    AnimatedVisibility(visible = expandAdvanced) {
                        Column(
                            modifier = Modifier.padding(
                                start = 20.dp,
                                end = 20.dp,
                                bottom = 20.dp
                            )
                        ) {
                            // Temperature
                            Text(
                                "Temperature: ${String.format("%.1f", config.temperature)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Slider(
                                value = config.temperature,
                                onValueChange = {
                                    viewModel.updateConfig(
                                        config.copy(temperature = (it * 10).toInt() / 10f)
                                    )
                                },
                                valueRange = 0f..2f,
                                steps = 19,
                                colors = SliderDefaults.colors(
                                    thumbColor = PrimaryPurple,
                                    activeTrackColor = PrimaryPurple,
                                    inactiveTrackColor = DarkSurfaceVariant
                                )
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Max Tokens
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
                                keyboardType = KeyboardType.Number
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Stream toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "流式输出",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = config.stream,
                                    onCheckedChange = {
                                        viewModel.updateConfig(config.copy(stream = it))
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = PrimaryPurple,
                                        uncheckedThumbColor = TextSecondary,
                                        uncheckedTrackColor = DarkSurfaceVariant
                                    )
                                )
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
                        // Show error via snackbar
                    } else {
                        viewModel.saveConfig()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent
                ),
                contentPadding = PaddingValues()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(PrimaryPurple, PrimaryBlue)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "保存设置",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ---- About ----
            Text(
                "EasyHomework v1.0.0",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                "支持 OpenAI 兼容 API（DeepSeek、通义千问等）",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
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
        color = PrimaryPurple,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = DarkCard
        )
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
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
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
    trailingIcon: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, color = TextSecondary) },
        placeholder = { Text(placeholder, color = TextTertiary) },
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        },
        trailingIcon = trailingIcon,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PrimaryPurple,
            unfocusedBorderColor = DarkSurfaceVariant,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = PrimaryPurple,
            focusedLabelColor = PrimaryPurple,
            unfocusedLabelColor = TextSecondary
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

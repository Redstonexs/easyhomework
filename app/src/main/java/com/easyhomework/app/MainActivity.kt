package com.easyhomework.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner as PlatformLocalLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner as LifecycleLocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.easyhomework.app.service.FloatingBallService
import com.easyhomework.app.ui.screens.HistoryScreen
import com.easyhomework.app.ui.screens.SettingsScreen
import com.easyhomework.app.ui.theme.EasyHomeworkTheme
import com.easyhomework.app.util.CrashReporter
import com.easyhomework.app.util.PreferencesManager
import com.easyhomework.app.viewmodel.HistoryViewModel
import com.easyhomework.app.viewmodel.SettingsViewModel

class MainActivity : ComponentActivity() {

    private lateinit var preferencesManager: PreferencesManager

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(this)) {
            startFloatingBallService()
        } else {
            preferencesManager.isFloatingBallEnabled = false
            Toast.makeText(this, "需要悬浮窗权限才能使用搜题功能", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Notification permission is optional, proceed anyway.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.setStage(this, "main_activity_on_create")
        super.onCreate(savedInstanceState)
        preferencesManager = PreferencesManager(this)
        syncStaleFloatingBallState()

        CrashReporter.setStage(this, "main_activity_set_content")
        setContent {
            CompositionLocalProvider(
                LifecycleLocalLifecycleOwner provides this@MainActivity,
                PlatformLocalLifecycleOwner provides this@MainActivity,
            ) {
                EasyHomeworkTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        var serviceEnabled by remember { mutableStateOf(preferencesManager.isFloatingBallEnabled) }

                        LaunchedEffect(Unit) {
                            serviceEnabled = preferencesManager.isFloatingBallEnabled
                        }

                        DisposableEffect(Unit) {
                            val listener = preferencesManager.registerFloatingBallEnabledListener { enabled ->
                                serviceEnabled = enabled
                            }
                            onDispose {
                                preferencesManager.unregisterFloatingBallEnabledListener(listener)
                            }
                        }

                        AppNavigationContent(
                            onToggleService = { enabled ->
                                serviceEnabled = enabled
                                if (enabled) {
                                    if (!requestOverlayPermissionAndStart()) {
                                        serviceEnabled = false
                                    }
                                } else {
                                    stopFloatingBallService()
                                }
                            },
                            isServiceRunning = serviceEnabled,
                            onResyncState = {
                                serviceEnabled = preferencesManager.isFloatingBallEnabled
                            },
                        )
                    }
                }
            }
        }
        requestNotificationPermissionAfterFirstDraw()
        CrashReporter.setStage(this, "main_activity_ready")
    }

    private fun requestOverlayPermissionAndStart(): Boolean {
        return if (Settings.canDrawOverlays(this)) {
            startFloatingBallService()
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            overlayPermissionLauncher.launch(intent)
            false
        }
    }

    private fun startFloatingBallService(): Boolean {
        validateConfigBeforeStart()?.let { message ->
            preferencesManager.isFloatingBallEnabled = false
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return false
        }

        preferencesManager.isFloatingBallEnabled = true
        val error = FloatingBallService.start(this)
        return if (error == null) {
            Toast.makeText(this, "悬浮球已开启", Toast.LENGTH_SHORT).show()
            true
        } else {
            preferencesManager.isFloatingBallEnabled = false
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun validateConfigBeforeStart(): String? {
        val config = preferencesManager.getLLMConfig()
        return when {
            config.apiEndpoint.isBlank() -> "请先填写 API 端点"
            config.apiKey.isBlank() -> "请先填写 API 密钥"
            config.modelName.isBlank() -> "请先填写模型名称"
            else -> null
        }
    }

    private fun stopFloatingBallService() {
        preferencesManager.isFloatingBallEnabled = false
        FloatingBallService.stop(this)
        Toast.makeText(this, "悬浮球已关闭", Toast.LENGTH_SHORT).show()
    }

    private fun syncStaleFloatingBallState() {
        if (preferencesManager.isFloatingBallEnabled && FloatingBallService.getInstance() == null) {
            preferencesManager.isFloatingBallEnabled = false
        }
    }

    private fun requestNotificationPermissionAfterFirstDraw() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }, 350L)
    }
}

@Composable
@Suppress("FunctionNaming", "FunctionName", "ktlint:standard:function-naming")
fun AppNavigationContent(
    onToggleService: (Boolean) -> Unit,
    isServiceRunning: Boolean,
    onResyncState: () -> Unit,
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "settings") {
        composable("settings") {
            val viewModel: SettingsViewModel = viewModel()
            SettingsScreen(
                viewModel = viewModel,
                isServiceRunning = isServiceRunning,
                onToggleService = onToggleService,
                onNavigateToHistory = {
                    navController.navigate("history")
                },
                onResyncState = onResyncState,
            )
        }
        composable("history") {
            val viewModel: HistoryViewModel = viewModel()
            HistoryScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
            )
        }
    }
}

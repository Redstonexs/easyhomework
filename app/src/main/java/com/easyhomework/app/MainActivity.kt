package com.easyhomework.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.easyhomework.app.service.FloatingBallService
import com.easyhomework.app.ui.screens.HistoryScreen
import com.easyhomework.app.ui.screens.SettingsScreen
import com.easyhomework.app.ui.theme.DarkBackground
import com.easyhomework.app.ui.theme.EasyHomeworkTheme
import com.easyhomework.app.util.PreferencesManager
import com.easyhomework.app.viewmodel.HistoryViewModel
import com.easyhomework.app.viewmodel.SettingsViewModel

class MainActivity : ComponentActivity() {

    private lateinit var preferencesManager: PreferencesManager

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startFloatingBallService()
        } else {
            Toast.makeText(this, "需要悬浮窗权限才能使用搜题功能", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Notification permission is optional, proceed anyway
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferencesManager = PreferencesManager(this)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            EasyHomeworkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    AppNavigation(
                        onToggleService = { enabled ->
                            if (enabled) {
                                requestOverlayPermissionAndStart()
                            } else {
                                stopFloatingBallService()
                            }
                        },
                        isServiceRunning = FloatingBallService.getInstance() != null
                    )
                }
            }
        }
    }

    private fun requestOverlayPermissionAndStart() {
        if (Settings.canDrawOverlays(this)) {
            startFloatingBallService()
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startFloatingBallService() {
        preferencesManager.isFloatingBallEnabled = true
        FloatingBallService.start(this)
        Toast.makeText(this, "悬浮球已开启", Toast.LENGTH_SHORT).show()
    }

    private fun stopFloatingBallService() {
        preferencesManager.isFloatingBallEnabled = false
        FloatingBallService.stop(this)
        Toast.makeText(this, "悬浮球已关闭", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun AppNavigation(
    onToggleService: (Boolean) -> Unit,
    isServiceRunning: Boolean
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
                }
            )
        }
        composable("history") {
            val viewModel: HistoryViewModel = viewModel()
            HistoryScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

package com.easyhomework.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class EasyHomeworkApp : Application() {

    companion object {
        const val CHANNEL_FLOATING_BALL = "floating_ball_channel"
        const val CHANNEL_SCREEN_CAPTURE = "screen_capture_channel"
        const val NOTIFICATION_ID_FLOATING_BALL = 1001
        const val NOTIFICATION_ID_SCREEN_CAPTURE = 1002
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val floatingChannel = NotificationChannel(
            CHANNEL_FLOATING_BALL,
            "悬浮球服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "悬浮球后台运行通知"
            setShowBadge(false)
        }

        val captureChannel = NotificationChannel(
            CHANNEL_SCREEN_CAPTURE,
            "截屏服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "截屏功能运行通知"
            setShowBadge(false)
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(floatingChannel)
        manager.createNotificationChannel(captureChannel)
    }
}

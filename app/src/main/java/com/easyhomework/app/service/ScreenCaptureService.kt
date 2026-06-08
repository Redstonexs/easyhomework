package com.easyhomework.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.core.app.NotificationCompat
import com.easyhomework.app.EasyHomeworkApp
import com.easyhomework.app.MainActivity
import com.easyhomework.app.R

/**
 * Foreground service that manages screen capture using MediaProjection API.
 * Captures a single screenshot when requested and delivers it to FloatingBallService.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handler: Handler? = null
    private var screenDensity: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var isCapturing = false
    private var activeCaptureRequestId = 0L
    private var activeCaptureCompleted = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            cleanupProjection(stopProjection = false)
        }
    }

    companion object {
        private var instance: ScreenCaptureService? = null
        private var lastScreenshot: Bitmap? = null
        private var pendingCaptureRequestId: Long? = null
        private var captureRequestSequence = 0L
        private const val CAPTURE_TIMEOUT_MS = 2_000L

        fun isProjectionReady(): Boolean {
            return instance?.mediaProjection != null
        }

        fun getLastScreenshot(): Bitmap? {
            val bitmap = lastScreenshot
            lastScreenshot = null
            return bitmap
        }

        fun requestCapture() {
            val requestId = ++captureRequestSequence
            pendingCaptureRequestId = requestId
            if (instance != null) {
                instance?.captureScreen(requestId)
            }
        }

        fun start(context: Context, resultCode: Int, data: Intent): String? {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("resultData", data)
            }
            return try {
                context.startForegroundService(intent)
                null
            } catch (e: Exception) {
                "截图服务启动失败: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        fun stop(context: Context) {
            pendingCaptureRequestId = null
            lastScreenshot = null
            val intent = Intent(context, ScreenCaptureService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        handler = Handler(Looper.getMainLooper())

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics: WindowMetrics = wm.currentWindowMetrics
            val bounds = windowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenDensity = metrics.densityDpi
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }

        try {
            startForegroundCompat()
        } catch (e: Exception) {
            notifyScreenshotFailure("截图服务启动失败: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val resultCode = it.getIntExtra("resultCode", 0)
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra("resultData", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra("resultData")
            }

            if (resultCode != 0 && resultData != null && mediaProjection == null) {
                val projectionManager = getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE,
                ) as MediaProjectionManager

                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                mediaProjection?.registerCallback(projectionCallback, handler)

                setupImageReader()

                if (mediaProjection == null || imageReader == null || virtualDisplay == null) {
                    pendingCaptureRequestId = null
                    notifyScreenshotFailure("截屏服务未准备好，请重新授权后再试")
                    return@let
                }

                // If there was a pending capture request, execute it now
                pendingCaptureRequestId?.let { requestId ->
                    pendingCaptureRequestId = null
                    handler?.postDelayed({ captureScreen(requestId) }, 300)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler?.removeCallbacksAndMessages(null)
        cleanupProjection()
        handler = null
        instance = null
        super.onDestroy()
    }

    private fun setupImageReader() {
        val reader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2,
        )
        imageReader = reader

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "EasyHomeworkCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )
    }

    /**
     * Capture a single frame from the virtual display.
     */
    private fun captureScreen(requestId: Long) {
        if (isCapturing) {
            pendingCaptureRequestId = null
            notifyScreenshotFailure("正在截屏，请稍后重试")
            return
        }
        if (mediaProjection == null || imageReader == null) {
            pendingCaptureRequestId = null
            notifyScreenshotFailure("截屏服务未准备好，请重新授权后再试")
            return
        }
        isCapturing = true
        activeCaptureRequestId = requestId
        activeCaptureCompleted = false

        handler?.postDelayed({
            completeCaptureFailure(requestId, "截屏超时，请重试")
        }, CAPTURE_TIMEOUT_MS)

        handler?.postDelayed({
            var image: android.media.Image? = null
            try {
                image = imageReader?.acquireLatestImage()
                if (image == null) {
                    completeCaptureFailure(requestId, "暂时没有截到屏幕内容，请重试")
                    return@postDelayed
                }

                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop to actual screen size (remove padding)
                val croppedBitmap = if (rowPadding > 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight).also {
                        if (it != bitmap) bitmap.recycle()
                    }
                } else {
                    bitmap
                }

                lastScreenshot = croppedBitmap
                completeCaptureSuccess(requestId)
            } catch (e: Exception) {
                e.printStackTrace()
                completeCaptureFailure(requestId, "截屏失败，请重试")
            } finally {
                image?.close()
            }
        }, 100) // Small delay to ensure screen has rendered
    }

    private fun completeCaptureSuccess(requestId: Long) {
        if (!markCaptureCompleted(requestId)) return
        notifyScreenshotSuccess()
    }

    private fun completeCaptureFailure(requestId: Long, message: String) {
        if (!markCaptureCompleted(requestId)) return
        notifyScreenshotFailure(message)
    }

    private fun markCaptureCompleted(requestId: Long): Boolean {
        if (activeCaptureRequestId != requestId || activeCaptureCompleted) return false
        activeCaptureCompleted = true
        pendingCaptureRequestId = null
        isCapturing = false
        return true
    }

    private fun notifyScreenshotSuccess() {
        if (FloatingBallService.getInstance() == null) return
        val notifyIntent = Intent(this, FloatingBallService::class.java).apply {
            action = FloatingBallService.ACTION_SCREENSHOT_RESULT
        }
        startService(notifyIntent)
    }

    private fun notifyScreenshotFailure(message: String) {
        if (FloatingBallService.getInstance() == null) return
        val notifyIntent = Intent(this, FloatingBallService::class.java).apply {
            action = FloatingBallService.ACTION_SCREENSHOT_RESULT
            putExtra(FloatingBallService.EXTRA_SCREENSHOT_ERROR, message)
        }
        startService(notifyIntent)
    }

    private fun cleanupProjection(stopProjection: Boolean = true) {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        if (stopProjection) {
            mediaProjection?.stop()
        }
        mediaProjection = null
    }

    private fun startForegroundCompat() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                EasyHomeworkApp.NOTIFICATION_ID_SCREEN_CAPTURE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(EasyHomeworkApp.NOTIFICATION_ID_SCREEN_CAPTURE, notification)
        }
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, EasyHomeworkApp.CHANNEL_SCREEN_CAPTURE)
            .setContentTitle("截屏服务运行中")
            .setContentText("正在为 EasyHomework 提供截屏功能")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}

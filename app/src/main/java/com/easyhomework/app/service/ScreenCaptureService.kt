package com.easyhomework.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowMetrics
import android.view.WindowManager
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

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            cleanupProjection()
        }
    }

    companion object {
        private var instance: ScreenCaptureService? = null
        private var lastScreenshot: Bitmap? = null
        private var pendingCapture = false

        fun isProjectionReady(): Boolean {
            return instance?.mediaProjection != null
        }

        fun getLastScreenshot(): Bitmap? {
            val bitmap = lastScreenshot
            lastScreenshot = null
            return bitmap
        }

        fun requestCapture() {
            if (instance != null) {
                pendingCapture = true
                instance?.captureScreen()
            }
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("resultData", data)
            }
            context.startForegroundService(intent)
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

        startForeground(EasyHomeworkApp.NOTIFICATION_ID_SCREEN_CAPTURE, createNotification())
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
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                mediaProjection?.registerCallback(projectionCallback, handler)

                setupImageReader()

                // If there was a pending capture request, execute it now
                if (pendingCapture) {
                    pendingCapture = false
                    handler?.postDelayed({ captureScreen() }, 300)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cleanupProjection()
        instance = null
        super.onDestroy()
    }

    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "EasyHomeworkCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )
    }

    /**
     * Capture a single frame from the virtual display.
     */
    private fun captureScreen() {
        if (isCapturing) return
        isCapturing = true

        handler?.postDelayed({
            try {
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    val bitmap = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    // Crop to actual screen size (remove padding)
                    val croppedBitmap = if (rowPadding > 0) {
                        Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight).also {
                            if (it != bitmap) bitmap.recycle()
                        }
                    } else {
                        bitmap
                    }

                    lastScreenshot = croppedBitmap

                    // Notify FloatingBallService
                    val notifyIntent = Intent(this, FloatingBallService::class.java).apply {
                        action = FloatingBallService.ACTION_SCREENSHOT_RESULT
                    }
                    startService(notifyIntent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isCapturing = false
            }
        }, 100) // Small delay to ensure screen has rendered
    }

    private fun cleanupProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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

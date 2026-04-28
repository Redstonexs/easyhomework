package com.easyhomework.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.app.NotificationCompat
import com.easyhomework.app.EasyHomeworkApp
import com.easyhomework.app.MainActivity
import com.easyhomework.app.R
import com.easyhomework.app.ScreenCapturePermissionActivity
import com.easyhomework.app.overlay.FloatingBallView
import com.easyhomework.app.overlay.RegionSelectorOverlay
import com.easyhomework.app.overlay.AnswerPanelOverlay
import com.easyhomework.app.util.PreferencesManager
import kotlin.math.abs

/**
 * Foreground service that manages the floating ball overlay.
 * Handles:
 * - Adding/removing the floating ball view
 * - Drag gestures and edge snapping
 * - Click detection to trigger screenshot flow
 * - Communication with ScreenCaptureService
 */
class FloatingBallService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var preferencesManager: PreferencesManager
    private var floatingBallView: FloatingBallView? = null
    private var regionSelector: RegionSelectorOverlay? = null
    private var answerPanel: AnswerPanelOverlay? = null

    private var ballParams: WindowManager.LayoutParams? = null

    // Drag tracking
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    companion object {
        const val ACTION_SCREENSHOT_RESULT = "com.easyhomework.SCREENSHOT_RESULT"
        const val EXTRA_SCREENSHOT_PATH = "screenshot_path"

        private const val CLICK_THRESHOLD = 10
        private const val BALL_SIZE = 160 // dp will be calculated

        private var instance: FloatingBallService? = null

        fun getInstance(): FloatingBallService? = instance

        fun start(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        preferencesManager = PreferencesManager(this)
        startForeground(EasyHomeworkApp.NOTIFICATION_ID_FLOATING_BALL, createNotification())
        showFloatingBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_SCREENSHOT_RESULT -> {
                    // Screenshot captured, show it
                    // The bitmap will be passed via the ScreenCaptureService singleton
                    val bitmap = ScreenCaptureService.getLastScreenshot()
                    if (bitmap != null) {
                        showRegionSelector(bitmap)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        removeFloatingBall()
        removeRegionSelector()
        removeAnswerPanel()
        preferencesManager.isFloatingBallEnabled = false
        super.onDestroy()
    }

    // ---- Floating Ball Management ----

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingBall() {
        if (floatingBallView != null) return

        val ballSizePx = (BALL_SIZE * resources.displayMetrics.density).toInt()

        ballParams = WindowManager.LayoutParams(
            ballSizePx,
            ballSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (preferencesManager.floatingBallX >= 0) preferencesManager.floatingBallX
                else resources.displayMetrics.widthPixels - ballSizePx - 20
            y = preferencesManager.floatingBallY
        }

        floatingBallView = FloatingBallView(this).apply {
            setOnTouchListener { v, event ->
                handleBallTouch(v, event)
                true
            }
        }

        windowManager.addView(floatingBallView, ballParams)

        // Entrance animation
        floatingBallView?.alpha = 0f
        floatingBallView?.scaleX = 0.5f
        floatingBallView?.scaleY = 0.5f
        floatingBallView?.animate()
            ?.alpha(1f)
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(400)
            ?.setInterpolator(OvershootInterpolator())
            ?.start()
    }

    private fun removeFloatingBall() {
        floatingBallView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        floatingBallView = null
    }

    fun hideFloatingBall() {
        floatingBallView?.visibility = View.GONE
    }

    fun showFloatingBallAgain() {
        floatingBallView?.visibility = View.VISIBLE
    }

    // ---- Touch Handling ----

    private fun handleBallTouch(view: View, event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = ballParams?.x ?: 0
                initialY = ballParams?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false

                // Press feedback
                view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start()
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (abs(dx) > CLICK_THRESHOLD || abs(dy) > CLICK_THRESHOLD) {
                    isDragging = true
                }

                if (isDragging) {
                    ballParams?.x = (initialX + dx).toInt()
                    ballParams?.y = (initialY + dy).toInt()
                    try {
                        windowManager.updateViewLayout(floatingBallView, ballParams)
                    } catch (_: Exception) {}
                }
            }

            MotionEvent.ACTION_UP -> {
                // Release feedback
                view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()

                if (!isDragging) {
                    // Click: trigger screenshot
                    onFloatingBallClicked()
                } else {
                    // Snap to edge
                    snapToEdge()
                }
            }
        }
    }

    private fun snapToEdge() {
        val params = ballParams ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val ballWidth = params.width

        val targetX = if (params.x + ballWidth / 2 < screenWidth / 2) {
            8 // Snap to left
        } else {
            screenWidth - ballWidth - 8 // Snap to right
        }

        // Animate to edge
        val startX = params.x
        floatingBallView?.animate()
            ?.setDuration(250)
            ?.setInterpolator(AccelerateDecelerateInterpolator())
            ?.setUpdateListener { animator ->
                val fraction = animator.animatedFraction
                params.x = (startX + (targetX - startX) * fraction).toInt()
                try {
                    windowManager.updateViewLayout(floatingBallView, params)
                } catch (_: Exception) {}
            }
            ?.start()

        // Save position
        preferencesManager.floatingBallX = targetX
        preferencesManager.floatingBallY = params.y
    }

    // ---- Screenshot Flow ----

    private fun onFloatingBallClicked() {
        // Click animation
        floatingBallView?.let { ball ->
            ball.animate()
                .scaleX(0.8f).scaleY(0.8f)
                .setDuration(100)
                .withEndAction {
                    ball.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(100)
                        .start()
                }
                .start()
        }

        // Check if ScreenCaptureService is running and has projection
        if (ScreenCaptureService.isProjectionReady()) {
            // Hide ball and capture
            hideFloatingBall()
            // Small delay to ensure ball is hidden before capture
            floatingBallView?.postDelayed({
                ScreenCaptureService.requestCapture()
            }, 250)
        } else {
            // Need to request MediaProjection permission
            val intent = Intent(this, ScreenCapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    // ---- Region Selector ----

    fun showRegionSelector(screenshot: Bitmap) {
        removeRegionSelector()

        regionSelector = RegionSelectorOverlay(this, screenshot).apply {
            onConfirm = { croppedBitmap, recognizedText ->
                removeRegionSelector()
                showAnswerPanel(croppedBitmap, recognizedText)
            }
            onCancel = {
                removeRegionSelector()
                showFloatingBallAgain()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(regionSelector, params)
    }

    private fun removeRegionSelector() {
        regionSelector?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            it.release()
        }
        regionSelector = null
    }

    // ---- Answer Panel ----

    fun showAnswerPanel(screenshot: Bitmap, recognizedText: String) {
        removeAnswerPanel()

        answerPanel = AnswerPanelOverlay(this, screenshot, recognizedText).apply {
            onClose = {
                removeAnswerPanel()
                showFloatingBallAgain()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        windowManager.addView(answerPanel, params)
    }

    private fun removeAnswerPanel() {
        answerPanel?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            it.release()
        }
        answerPanel = null
    }

    // ---- Notification ----

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, EasyHomeworkApp.CHANNEL_FLOATING_BALL)
            .setContentTitle("EasyHomework 运行中")
            .setContentText("点击悬浮球截屏搜题")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}

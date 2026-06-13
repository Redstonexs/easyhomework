package com.easyhomework.app.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.easyhomework.app.EasyHomeworkApp
import com.easyhomework.app.MainActivity
import com.easyhomework.app.R
import com.easyhomework.app.ScreenCapturePermissionActivity
import com.easyhomework.app.overlay.AnswerPanelOverlay
import com.easyhomework.app.overlay.FloatingBallView
import com.easyhomework.app.overlay.RegionSelectorOverlay
import com.easyhomework.app.ui.theme.neutralPalette
import com.easyhomework.app.util.PreferencesManager

/**
 * Foreground service that manages the floating ball overlay.
 * Handles:
 * - Adding/removing the floating ball view (normal + mini mode)
 * - Drag gestures and edge snapping
 * - Click detection to trigger screenshot flow
 * - Long press to close the service
 * - Communication with ScreenCaptureService
 */
class FloatingBallService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var preferencesManager: PreferencesManager
    private var floatingBallView: FloatingBallView? = null
    private var regionSelector: RegionSelectorOverlay? = null
    private var answerPanel: AnswerPanelOverlay? = null
    private var ballMenuView: View? = null
    private var lastRegionScreenshot: Bitmap? = null
    private var awaitingScreenshotResult = false

    private var ballParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val palette by lazy { neutralPalette(this) }
    private val idleFadeRunnable = Runnable { fadeBallToIdle() }

    // Drag tracking
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    // Long press tracking
    private var longPressTriggered = false
    private val longPressRunnable = Runnable {
        if (!isDragging) {
            longPressTriggered = true
            onLongPress()
        }
    }

    companion object {
        const val ACTION_SCREENSHOT_RESULT = "com.easyhomework.SCREENSHOT_RESULT"
        const val EXTRA_SCREENSHOT_PATH = "screenshot_path"
        const val EXTRA_SCREENSHOT_ERROR = "screenshot_error"

        private const val BALL_SIZE_NORMAL = 52
        private const val BALL_TOUCH_SIZE_MINI = 48 // Much larger touch target for mini ball
        private const val DRAG_SLOP_DP = 8f
        private const val LONG_PRESS_DURATION = 500L

        // Delay between hiding the ball and grabbing the frame, so the ball isn't in the shot.
        // Kept just long enough for the hide to reach the compositor (a few frames).
        private const val CAPTURE_HIDE_DELAY_MS = 120L

        private const val EDGE_MARGIN_DP = 8f
        private const val SNAP_ANIM_MS = 220L
        private const val IDLE_FADE_DELAY_MS = 4_000L
        private const val IDLE_ALPHA = 0.4f

        private var instance: FloatingBallService? = null

        fun getInstance(): FloatingBallService? = instance

        fun start(context: Context): String? {
            val intent = Intent(context, FloatingBallService::class.java)
            return try {
                context.startForegroundService(intent)
                null
            } catch (e: Exception) {
                "悬浮球启动失败: ${e.message ?: e.javaClass.simpleName}"
            }
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
        try {
            startForegroundCompat()
            showFloatingBall()
        } catch (e: Exception) {
            preferencesManager.isFloatingBallEnabled = false
            Toast.makeText(this, "悬浮球启动失败: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            removeFloatingBall()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_SCREENSHOT_RESULT -> {
                    if (!awaitingScreenshotResult) return@let
                    awaitingScreenshotResult = false

                    val error = it.getStringExtra(EXTRA_SCREENSHOT_ERROR)
                    if (!error.isNullOrBlank()) {
                        showFloatingBallAgain()
                        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                        return@let
                    }

                    val bitmap = ScreenCaptureService.getLastScreenshot()
                    if (bitmap != null) {
                        showRegionSelector(bitmap)
                    } else {
                        showFloatingBallAgain()
                        Toast.makeText(this, "截屏失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(longPressRunnable)
        cancelIdleFade()
        removeBallMenu()
        removeFloatingBall()
        removeRegionSelector()
        removeAnswerPanel()
        lastRegionScreenshot = null
        ScreenCaptureService.stop(this)
        preferencesManager.isFloatingBallEnabled = false
        super.onDestroy()
    }

    // ---- Floating Ball Management ----

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingBall() {
        if (floatingBallView != null) return

        val isMini = preferencesManager.getLLMConfig().miniBall
        // Mini ball uses larger touch target for easier dragging
        val ballSize = if (isMini) BALL_TOUCH_SIZE_MINI else BALL_SIZE_NORMAL
        val ballSizePx = (ballSize * resources.displayMetrics.density).toInt()

        ballParams = WindowManager.LayoutParams(
            ballSizePx,
            ballSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (preferencesManager.floatingBallX >= 0) {
                preferencesManager.floatingBallX
            } else {
                resources.displayMetrics.widthPixels - ballSizePx - 20
            }
            y = preferencesManager.floatingBallY
        }

        floatingBallView = FloatingBallView(this).apply {
            isMiniMode = isMini
            setOnTouchListener { v, event ->
                handleBallTouch(v, event)
                true
            }
        }

        try {
            windowManager.addView(floatingBallView, ballParams)
        } catch (e: Exception) {
            floatingBallView = null
            preferencesManager.isFloatingBallEnabled = false
            Toast.makeText(this, "悬浮窗显示失败: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

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

        scheduleIdleFade()
    }

    private fun removeFloatingBall() {
        floatingBallView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        floatingBallView = null
    }

    /**
     * Recreate the floating ball (e.g. after settings change between normal/mini).
     */
    fun recreateFloatingBall() {
        removeFloatingBall()
        showFloatingBall()
    }

    fun hideFloatingBall() {
        cancelIdleFade()
        floatingBallView?.visibility = View.GONE
    }

    fun showFloatingBallAgain() {
        awaitingScreenshotResult = false
        floatingBallView?.visibility = View.VISIBLE
        restoreBallAlpha()
        scheduleIdleFade()
    }

    // ---- Touch Handling ----

    private fun handleBallTouch(view: View, event: MotionEvent) {
        val dragSlop = dragSlopPx()
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                cancelIdleFade()
                restoreBallAlpha()
                initialX = ballParams?.x ?: 0
                initialY = ballParams?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                longPressTriggered = false

                // Start long press timer
                handler.postDelayed(longPressRunnable, LONG_PRESS_DURATION)

                // Press feedback
                view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start()
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                val movementSquared = dx * dx + dy * dy
                val dragSlopSquared = dragSlop * dragSlop

                if (movementSquared > dragSlopSquared) {
                    isDragging = true
                    // Cancel long press if dragging
                    handler.removeCallbacks(longPressRunnable)
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
                handler.removeCallbacks(longPressRunnable)

                // Release feedback
                view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()

                if (longPressTriggered) {
                    // Already handled by long press
                    return
                }

                if (!isDragging) {
                    // Click: trigger screenshot
                    onFloatingBallClicked()
                } else {
                    // Snap to nearest edge (or just persist the dropped position).
                    if (preferencesManager.ballEdgeSnap) {
                        snapToNearestEdge()
                    } else {
                        preferencesManager.floatingBallX = ballParams?.x ?: 0
                        preferencesManager.floatingBallY = ballParams?.y ?: 0
                    }
                    scheduleIdleFade()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                scheduleIdleFade()
            }
        }
    }

    private fun dragSlopPx(): Float {
        val systemSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val minSlop = DRAG_SLOP_DP * resources.displayMetrics.density
        return maxOf(systemSlop, minSlop)
    }

    /**
     * Long press handler — opens a small menu (设置 / 历史 / 关闭) instead of immediately
     * closing, so a long press is no longer a destructive accident.
     */
    private fun onLongPress() {
        showBallMenu()
    }

    // ---- Edge snap & idle fade ----

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    /**
     * Animate the ball to whichever vertical screen edge it is closest to and persist the spot.
     */
    private fun snapToNearestEdge() {
        val params = ballParams ?: return
        val view = floatingBallView ?: return
        val ballSize = view.width.takeIf { it > 0 } ?: params.width
        val margin = dp(EDGE_MARGIN_DP).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val leftTarget = margin
        val rightTarget = screenWidth - ballSize - margin
        val targetX = if (params.x + ballSize / 2 < screenWidth / 2) leftTarget else rightTarget
        val maxY = (screenHeight - ballSize - margin).coerceAtLeast(margin)
        val targetY = params.y.coerceIn(margin, maxY)
        val startX = params.x
        val startY = params.y

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val f = animator.animatedValue as Float
                params.x = (startX + (targetX - startX) * f).toInt()
                params.y = (startY + (targetY - startY) * f).toInt()
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {}
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    preferencesManager.floatingBallX = params.x
                    preferencesManager.floatingBallY = params.y
                }
            })
            start()
        }
    }

    private fun scheduleIdleFade() {
        cancelIdleFade()
        if (!preferencesManager.ballIdleFade) return
        handler.postDelayed(idleFadeRunnable, IDLE_FADE_DELAY_MS)
    }

    private fun cancelIdleFade() {
        handler.removeCallbacks(idleFadeRunnable)
    }

    private fun fadeBallToIdle() {
        val view = floatingBallView ?: return
        if (view.visibility != View.VISIBLE || ballMenuView != null) return
        if (!preferencesManager.ballIdleFade) return
        view.animate().alpha(IDLE_ALPHA).setDuration(400).start()
    }

    private fun restoreBallAlpha() {
        floatingBallView?.let { ball ->
            if (ball.alpha < 1f) {
                ball.animate().alpha(1f).setDuration(150).start()
            }
        }
    }

    // ---- Long-press menu ----

    @SuppressLint("ClickableViewAccessibility")
    private fun showBallMenu() {
        if (ballMenuView != null) return
        cancelIdleFade()
        restoreBallAlpha()
        floatingBallView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(18f)
            }
            elevation = dp(12f)
            isClickable = true
            val vPad = dp(6f).toInt()
            setPadding(0, vPad, 0, vPad)
            addView(menuRow("设置") { openMain("settings") })
            addView(menuDivider())
            addView(menuRow("历史记录") { openMain("history") })
            addView(menuDivider())
            addView(menuRow("关闭悬浮球", danger = true) { closeFromMenu() })
        }

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            isFocusableInTouchMode = true
            setOnClickListener { removeBallMenu() }
            setOnKeyListener { _, keyCode, keyEvent ->
                if (keyCode == KeyEvent.KEYCODE_BACK && keyEvent.action == KeyEvent.ACTION_UP) {
                    removeBallMenu()
                    true
                } else {
                    false
                }
            }
        }
        scrim.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER },
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )

        try {
            windowManager.addView(scrim, params)
            ballMenuView = scrim
            scrim.requestFocus()
            card.alpha = 0f
            card.scaleX = 0.92f
            card.scaleY = 0.92f
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        } catch (e: Exception) {
            ballMenuView = null
            Toast.makeText(this, "菜单打开失败: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
            scheduleIdleFade()
        }
    }

    private fun menuRow(label: String, danger: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(if (danger) palette.error else palette.onSurface)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28f).toInt(), dp(14f).toInt(), dp(56f).toInt(), dp(14f).toInt())
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun menuDivider(): View {
        return View(this).apply {
            setBackgroundColor(palette.outlineVariant)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
    }

    private fun removeBallMenu() {
        ballMenuView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        ballMenuView = null
        scheduleIdleFade()
    }

    private fun openMain(destination: String) {
        removeBallMenu()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(MainActivity.EXTRA_START_DESTINATION, destination)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "打开失败: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeFromMenu() {
        removeBallMenu()
        val ball = floatingBallView
        if (ball == null) {
            preferencesManager.isFloatingBallEnabled = false
            stopSelf()
            return
        }
        ball.animate()
            .scaleX(0f).scaleY(0f).alpha(0f)
            .setDuration(250)
            .withEndAction {
                preferencesManager.isFloatingBallEnabled = false
                Toast.makeText(this, "悬浮球已关闭", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
            .start()
    }

    // ---- Screenshot Flow ----

    private fun onFloatingBallClicked() {
        val config = preferencesManager.getLLMConfig()
        when {
            config.apiEndpoint.isBlank() -> {
                Toast.makeText(this, "请先在设置中填写 API 端点", Toast.LENGTH_SHORT).show()
                return
            }
            config.apiKey.isBlank() -> {
                Toast.makeText(this, "请先在设置中填写 API 密钥", Toast.LENGTH_SHORT).show()
                return
            }
            config.modelName.isBlank() -> {
                Toast.makeText(this, "请先在设置中填写模型名称", Toast.LENGTH_SHORT).show()
                return
            }
        }

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

        if (ScreenCaptureService.isProjectionReady()) {
            awaitingScreenshotResult = true
            hideFloatingBall()
            floatingBallView?.postDelayed({
                ScreenCaptureService.requestCapture()
            }, CAPTURE_HIDE_DELAY_MS)
        } else {
            awaitingScreenshotResult = true
            val intent = Intent(this, ScreenCapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                awaitingScreenshotResult = false
                Toast.makeText(this, "截图授权打开失败: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- Region Selector ----

    fun showRegionSelector(screenshot: Bitmap, allowAutoSubmit: Boolean = true) {
        removeRegionSelector()
        lastRegionScreenshot = screenshot
        val shouldAutoSubmit = allowAutoSubmit && preferencesManager.autoSubmitDetectedRegion

        regionSelector = RegionSelectorOverlay(this, screenshot, shouldAutoSubmit).apply {
            onConfirm = { croppedBitmap, recognizedText, sendDirectImage ->
                removeRegionSelector()
                showAnswerPanel(croppedBitmap, recognizedText, sendDirectImage)
            }
            onCancel = {
                removeRegionSelector()
                lastRegionScreenshot = null
                showFloatingBallAgain()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )

        try {
            windowManager.addView(regionSelector, params)
        } catch (e: Exception) {
            regionSelector?.release()
            regionSelector = null
            Toast.makeText(this, "选区打开失败: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            showFloatingBallAgain()
        }
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

    fun showAnswerPanel(screenshot: Bitmap, recognizedText: String, sendDirectImage: Boolean = false) {
        removeAnswerPanel()

        try {
            answerPanel = AnswerPanelOverlay(this, screenshot, recognizedText, sendDirectImage).apply {
                onClose = {
                    removeAnswerPanel()
                    lastRegionScreenshot = null
                    showFloatingBallAgain()
                }
                onReselect = {
                    val sourceScreenshot = lastRegionScreenshot
                    removeAnswerPanel()
                    if (sourceScreenshot != null) {
                        showRegionSelector(sourceScreenshot, allowAutoSubmit = false)
                    } else {
                        showFloatingBallAgain()
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Keep the answer panel focusable so its EditText can own IME focus.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            }

            windowManager.addView(answerPanel, params)
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(this, "面板打开失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            showFloatingBallAgain()
        }
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

    private fun startForegroundCompat() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                EasyHomeworkApp.NOTIFICATION_ID_FLOATING_BALL,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(EasyHomeworkApp.NOTIFICATION_ID_FLOATING_BALL, notification)
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

        return NotificationCompat.Builder(this, EasyHomeworkApp.CHANNEL_FLOATING_BALL)
            .setContentTitle("EasyHomework 运行中")
            .setContentText("点击截屏搜题 · 长按打开菜单")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}

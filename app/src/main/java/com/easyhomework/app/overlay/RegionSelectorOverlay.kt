package com.easyhomework.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.easyhomework.app.ocr.SmartRegionDetector
import com.easyhomework.app.ocr.TextRecognitionManager
import com.easyhomework.app.util.PreferencesManager
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen overlay that displays a screenshot and allows the user to select/adjust
 * a region for OCR. Features:
 * - Smart region auto-detection
 * - 8 drag handles for resizing
 * - Full region drag for repositioning
 * - Dark overlay outside selection
 * - Confirm/cancel buttons
 * - Direct image send for vision models (skip OCR)
 */
@SuppressLint("ViewConstructor")
class RegionSelectorOverlay(
    context: Context,
    private val screenshot: Bitmap,
    private val allowAutoSubmit: Boolean = true,
) : FrameLayout(context) {

    /**
     * @param croppedBitmap The cropped region bitmap
     * @param recognizedText OCR text (empty if sendDirectImage is true)
     * @param sendDirectImage If true, send image directly to vision model without OCR
     */
    var onConfirm: ((Bitmap, String, Boolean) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    // Selection rectangle (in bitmap coordinates)
    private var selectionRect = RectF()

    // Display scale factor
    private var displayScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    // Touch handling
    private var activeHandle = Handle.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDraggingRegion = false

    // Paints
    private val overlayPaint = Paint().apply {
        color = Color.parseColor("#AA000000")
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6C63FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6C63FF")
        style = Paint.Style.FILL
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6C63FF")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val smartDetector = SmartRegionDetector()
    private val preferencesManager = PreferencesManager(context)
    private val isVisionModel = preferencesManager.getLLMConfig().supportsVisionInput()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isLoading = true
    private var isConfirming = false

    // Status text
    private val statusText: TextView
    private val buttonBar: LinearLayout

    private val handleRadius = 16f
    private val cornerLength = 40f
    private val touchSlop = 40f

    private companion object {
        const val AUTO_SUBMIT_CONFIDENCE = 0.82f
        const val AUTO_SUBMIT_DELAY_MS = 450L
        const val STATUS_HIDE_DELAY_MS = 1600L
    }

    enum class Handle {
        NONE,
        TOP_LEFT,
        TOP,
        TOP_RIGHT,
        RIGHT,
        BOTTOM_RIGHT,
        BOTTOM,
        BOTTOM_LEFT,
        LEFT,
    }

    init {
        setWillNotDraw(false)

        // Status text (loading indicator)
        statusText = TextView(context).apply {
            text = "正在分析图片..."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.parseColor("#CC000000"))
        }
        val statusParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        addView(statusText, statusParams)

        // Bottom button bar
        buttonBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 48)
            visibility = View.GONE
        }

        // Cancel button
        val cancelBtn = createButton("✕ 取消", "#FF5252") {
            onCancel?.invoke()
        }

        // Direct image button (for vision models)
        val directImageBtn = if (isVisionModel) {
            createButton("直接识图", "#FF9800") {
                confirmSelection(sendDirectImage = true)
            }
        } else {
            null
        }

        // Confirm OCR button
        val confirmBtn = createButton("✓ OCR 识字", "#6C63FF") {
            confirmSelection(sendDirectImage = false)
        }

        buttonBar.addView(cancelBtn)
        val spacer = View(context)
        buttonBar.addView(spacer, LinearLayout.LayoutParams(24, 1))
        if (directImageBtn != null) {
            buttonBar.addView(directImageBtn)
            val spacer2 = View(context)
            buttonBar.addView(spacer2, LinearLayout.LayoutParams(24, 1))
        }
        buttonBar.addView(confirmBtn)

        val buttonParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }
        addView(buttonBar, buttonParams)

        // Start smart detection
        detectRegion()
    }

    @SuppressLint("SetTextI18n")
    private fun detectRegion() {
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    smartDetector.detectQuestionRegion(screenshot)
                }
                selectionRect = RectF(result.suggestedRegion)
                isLoading = false
                invalidate()

                if (allowAutoSubmit && result.confidence >= AUTO_SUBMIT_CONFIDENCE) {
                    statusText.text = if (isVisionModel) {
                        "已自动框选，正在直接识图..."
                    } else {
                        "已自动框选，正在 OCR 搜题..."
                    }
                    statusText.visibility = View.VISIBLE
                    buttonBar.visibility = View.GONE
                    delay(AUTO_SUBMIT_DELAY_MS)
                    if (isAttachedToWindow && !isConfirming) {
                        confirmSelection(sendDirectImage = isVisionModel)
                    }
                } else {
                    val message = if (allowAutoSubmit) {
                        "已自动框选，请确认或手动调整"
                    } else {
                        "已自动框选，可手动调整后提交"
                    }
                    showManualControls(message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fallback: select center region
                selectionRect = RectF(
                    screenshot.width * 0.1f,
                    screenshot.height * 0.15f,
                    screenshot.width * 0.9f,
                    screenshot.height * 0.75f,
                )
                isLoading = false
                showManualControls("自动检测失败，请手动调整选区")
                invalidate()
            }
        }
    }

    private fun createButton(text: String, bgColor: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(32, 20, 32, 20)
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor(bgColor))
                cornerRadius = 32f
            }
            background = bg
            elevation = 8f
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showManualControls(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
        buttonBar.visibility = View.VISIBLE
        statusText.postDelayed({
            if (!isConfirming && !isLoading) {
                statusText.visibility = View.GONE
            }
        }, STATUS_HIDE_DELAY_MS)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Calculate display parameters
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val bmpWidth = screenshot.width.toFloat()
        val bmpHeight = screenshot.height.toFloat()

        displayScale = min(viewWidth / bmpWidth, viewHeight / bmpHeight)
        offsetX = (viewWidth - bmpWidth * displayScale) / 2f
        offsetY = (viewHeight - bmpHeight * displayScale) / 2f

        // Draw screenshot
        val srcRect = Rect(0, 0, screenshot.width, screenshot.height)
        val dstRect = RectF(offsetX, offsetY, offsetX + bmpWidth * displayScale, offsetY + bmpHeight * displayScale)
        canvas.drawBitmap(screenshot, srcRect, dstRect, null)

        if (isLoading) return

        // Convert selection to display coordinates
        val dispSel = bitmapToDisplay(selectionRect)

        // Draw dark overlay outside selection (4 rectangles)
        // Top
        canvas.drawRect(0f, 0f, viewWidth, dispSel.top, overlayPaint)
        // Bottom
        canvas.drawRect(0f, dispSel.bottom, viewWidth, viewHeight, overlayPaint)
        // Left
        canvas.drawRect(0f, dispSel.top, dispSel.left, dispSel.bottom, overlayPaint)
        // Right
        canvas.drawRect(dispSel.right, dispSel.top, viewWidth, dispSel.bottom, overlayPaint)

        // Draw selection border
        canvas.drawRect(dispSel, borderPaint)

        // Draw corner accents
        drawCorner(canvas, dispSel.left, dispSel.top, 1, 1)
        drawCorner(canvas, dispSel.right, dispSel.top, -1, 1)
        drawCorner(canvas, dispSel.left, dispSel.bottom, 1, -1)
        drawCorner(canvas, dispSel.right, dispSel.bottom, -1, -1)

        // Draw handles
        drawHandle(canvas, dispSel.left, dispSel.top) // TL
        drawHandle(canvas, dispSel.centerX(), dispSel.top) // T
        drawHandle(canvas, dispSel.right, dispSel.top) // TR
        drawHandle(canvas, dispSel.right, dispSel.centerY()) // R
        drawHandle(canvas, dispSel.right, dispSel.bottom) // BR
        drawHandle(canvas, dispSel.centerX(), dispSel.bottom) // B
        drawHandle(canvas, dispSel.left, dispSel.bottom) // BL
        drawHandle(canvas, dispSel.left, dispSel.centerY()) // L
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, dx: Int, dy: Int) {
        canvas.drawLine(x, y, x + cornerLength * dx, y, cornerPaint)
        canvas.drawLine(x, y, x, y + cornerLength * dy, cornerPaint)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handleRadius, handlePaint)
        canvas.drawCircle(x, y, handleRadius, handleBorderPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isLoading || isConfirming) return true

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y

                // Check if touching a handle
                val dispSel = bitmapToDisplay(selectionRect)
                activeHandle = getHandleAt(event.x, event.y, dispSel)

                if (activeHandle == Handle.NONE) {
                    // Check if inside selection (for dragging)
                    isDraggingRegion = dispSel.contains(event.x, event.y)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.x - lastTouchX) / displayScale
                val dy = (event.y - lastTouchY) / displayScale
                lastTouchX = event.x
                lastTouchY = event.y

                if (activeHandle != Handle.NONE) {
                    resizeSelection(activeHandle, dx, dy)
                    invalidate()
                } else if (isDraggingRegion) {
                    moveSelection(dx, dy)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                activeHandle = Handle.NONE
                isDraggingRegion = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getHandleAt(x: Float, y: Float, sel: RectF): Handle {
        val t = touchSlop + handleRadius

        if (dist(x, y, sel.left, sel.top) < t) return Handle.TOP_LEFT
        if (dist(x, y, sel.right, sel.top) < t) return Handle.TOP_RIGHT
        if (dist(x, y, sel.left, sel.bottom) < t) return Handle.BOTTOM_LEFT
        if (dist(x, y, sel.right, sel.bottom) < t) return Handle.BOTTOM_RIGHT
        if (dist(x, y, sel.centerX(), sel.top) < t) return Handle.TOP
        if (dist(x, y, sel.centerX(), sel.bottom) < t) return Handle.BOTTOM
        if (dist(x, y, sel.left, sel.centerY()) < t) return Handle.LEFT
        if (dist(x, y, sel.right, sel.centerY()) < t) return Handle.RIGHT

        return Handle.NONE
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun resizeSelection(handle: Handle, dx: Float, dy: Float) {
        val minSize = 50f
        when (handle) {
            Handle.TOP_LEFT -> {
                selectionRect.left = min(selectionRect.left + dx, selectionRect.right - minSize)
                selectionRect.top = min(selectionRect.top + dy, selectionRect.bottom - minSize)
            }
            Handle.TOP -> {
                selectionRect.top = min(selectionRect.top + dy, selectionRect.bottom - minSize)
            }
            Handle.TOP_RIGHT -> {
                selectionRect.right = max(selectionRect.right + dx, selectionRect.left + minSize)
                selectionRect.top = min(selectionRect.top + dy, selectionRect.bottom - minSize)
            }
            Handle.RIGHT -> {
                selectionRect.right = max(selectionRect.right + dx, selectionRect.left + minSize)
            }
            Handle.BOTTOM_RIGHT -> {
                selectionRect.right = max(selectionRect.right + dx, selectionRect.left + minSize)
                selectionRect.bottom = max(selectionRect.bottom + dy, selectionRect.top + minSize)
            }
            Handle.BOTTOM -> {
                selectionRect.bottom = max(selectionRect.bottom + dy, selectionRect.top + minSize)
            }
            Handle.BOTTOM_LEFT -> {
                selectionRect.left = min(selectionRect.left + dx, selectionRect.right - minSize)
                selectionRect.bottom = max(selectionRect.bottom + dy, selectionRect.top + minSize)
            }
            Handle.LEFT -> {
                selectionRect.left = min(selectionRect.left + dx, selectionRect.right - minSize)
            }
            Handle.NONE -> {}
        }

        // Clamp to bitmap bounds
        selectionRect.left = max(0f, selectionRect.left)
        selectionRect.top = max(0f, selectionRect.top)
        selectionRect.right = min(screenshot.width.toFloat(), selectionRect.right)
        selectionRect.bottom = min(screenshot.height.toFloat(), selectionRect.bottom)
    }

    private fun moveSelection(dx: Float, dy: Float) {
        val w = selectionRect.width()
        val h = selectionRect.height()

        var newLeft = selectionRect.left + dx
        var newTop = selectionRect.top + dy

        // Clamp
        newLeft = max(0f, min(screenshot.width - w, newLeft))
        newTop = max(0f, min(screenshot.height - h, newTop))

        selectionRect.set(newLeft, newTop, newLeft + w, newTop + h)
    }

    private fun bitmapToDisplay(rect: RectF): RectF {
        return RectF(
            rect.left * displayScale + offsetX,
            rect.top * displayScale + offsetY,
            rect.right * displayScale + offsetX,
            rect.bottom * displayScale + offsetY,
        )
    }

    @SuppressLint("SetTextI18n")
    private fun confirmSelection(sendDirectImage: Boolean) {
        if (isConfirming) return
        isConfirming = true

        scope.launch {
            if (sendDirectImage) {
                // Direct image mode: skip OCR, send bitmap directly
                statusText.text = "准备发送图片..."
                statusText.visibility = View.VISIBLE
                buttonBar.visibility = View.GONE

                try {
                    val croppedBitmap = cropSelectedBitmap()
                    onConfirm?.invoke(croppedBitmap, "", true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    isConfirming = false
                    statusText.text = "处理失败: ${e.message}"
                    statusText.postDelayed({
                        statusText.visibility = View.GONE
                        buttonBar.visibility = View.VISIBLE
                    }, 2000)
                }
            } else {
                // OCR mode
                statusText.text = "正在识别文字..."
                statusText.visibility = View.VISIBLE
                buttonBar.visibility = View.GONE

                try {
                    val croppedBitmap = cropSelectedBitmap()
                    val recognizer = TextRecognitionManager()
                    val result = try {
                        recognizer.recognizeText(croppedBitmap)
                    } finally {
                        recognizer.close()
                    }

                    if (result.text.isBlank()) {
                        isConfirming = false
                        statusText.text = "未识别到文字，请重新选择区域"
                        statusText.postDelayed({
                            statusText.visibility = View.GONE
                            buttonBar.visibility = View.VISIBLE
                        }, 2000)
                    } else {
                        val bitmap = croppedBitmap
                        val text = result.text
                        onConfirm?.invoke(bitmap, text, false)
                    }
                } catch (e: Exception) {
                    isConfirming = false
                    statusText.text = "识别失败: ${e.message}"
                    statusText.postDelayed({
                        statusText.visibility = View.GONE
                        buttonBar.visibility = View.VISIBLE
                    }, 2000)
                }
            }
        }
    }

    private fun cropSelectedBitmap(): Bitmap {
        val cropRect = Rect(
            max(0, selectionRect.left.toInt()),
            max(0, selectionRect.top.toInt()),
            min(screenshot.width, selectionRect.right.toInt()),
            min(screenshot.height, selectionRect.bottom.toInt()),
        )
        require(cropRect.width() > 0 && cropRect.height() > 0) { "选区无效" }

        return Bitmap.createBitmap(
            screenshot,
            cropRect.left,
            cropRect.top,
            cropRect.width(),
            cropRect.height(),
        )
    }

    fun release() {
        scope.cancel()
        smartDetector.close()
    }
}

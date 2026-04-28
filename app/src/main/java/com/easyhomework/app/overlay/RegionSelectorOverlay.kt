package com.easyhomework.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import android.view.ViewGroup
import com.easyhomework.app.ocr.SmartRegionDetector
import com.easyhomework.app.ocr.TextRecognitionManager
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen overlay that displays a screenshot and allows the user to select/adjust
 * a region for OCR. Features:
 * - Smart region auto-detection
 * - 8 drag handles for resizing
 * - Full region drag for repositioning
 * - Dark overlay outside selection
 * - Confirm/cancel buttons
 */
@SuppressLint("ViewConstructor")
class RegionSelectorOverlay(
    context: Context,
    private val screenshot: Bitmap
) : FrameLayout(context) {

    var onConfirm: ((Bitmap, String) -> Unit)? = null
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
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isLoading = true

    // Status text
    private val statusText: TextView
    private val buttonBar: LinearLayout

    private val handleRadius = 16f
    private val cornerLength = 40f
    private val touchSlop = 40f

    enum class Handle {
        NONE, TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT
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
            LayoutParams.WRAP_CONTENT
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

        // Confirm button
        val confirmBtn = createButton("✓ 确认选区", "#6C63FF") {
            confirmSelection()
        }

        buttonBar.addView(cancelBtn)
        // Spacer
        val spacer = View(context)
        buttonBar.addView(spacer, LinearLayout.LayoutParams(48, 1))
        buttonBar.addView(confirmBtn)

        val buttonParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
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
                statusText.visibility = View.GONE
                buttonBar.visibility = View.VISIBLE
                invalidate()
            } catch (e: Exception) {
                // Fallback: select center region
                selectionRect = RectF(
                    screenshot.width * 0.1f,
                    screenshot.height * 0.15f,
                    screenshot.width * 0.9f,
                    screenshot.height * 0.75f
                )
                isLoading = false
                statusText.text = "自动检测失败，请手动调整选区"
                statusText.postDelayed({ statusText.visibility = View.GONE }, 2000)
                buttonBar.visibility = View.VISIBLE
                invalidate()
            }
        }
    }

    private fun createButton(text: String, bgColor: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 24, 48, 24)
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor(bgColor))
                cornerRadius = 32f
            }
            background = bg
            elevation = 8f
            setOnClickListener { onClick() }
        }
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
        if (isLoading) return true

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
            rect.bottom * displayScale + offsetY
        )
    }

    private fun confirmSelection() {
        scope.launch {
            statusText.text = "正在识别文字..."
            statusText.visibility = View.VISIBLE
            buttonBar.visibility = View.GONE

            try {
                // Crop the bitmap
                val cropRect = Rect(
                    max(0, selectionRect.left.toInt()),
                    max(0, selectionRect.top.toInt()),
                    min(screenshot.width, selectionRect.right.toInt()),
                    min(screenshot.height, selectionRect.bottom.toInt())
                )

                val croppedBitmap = Bitmap.createBitmap(
                    screenshot,
                    cropRect.left, cropRect.top,
                    cropRect.width(), cropRect.height()
                )

                // OCR
                val recognizer = TextRecognitionManager()
                val result = recognizer.recognizeText(croppedBitmap)
                recognizer.close()

                if (result.text.isBlank()) {
                    statusText.text = "未识别到文字，请重新选择区域"
                    statusText.postDelayed({
                        statusText.visibility = View.GONE
                        buttonBar.visibility = View.VISIBLE
                    }, 2000)
                } else {
                    onConfirm?.invoke(croppedBitmap, result.text)
                }
            } catch (e: Exception) {
                statusText.text = "识别失败: ${e.message}"
                statusText.postDelayed({
                    statusText.visibility = View.GONE
                    buttonBar.visibility = View.VISIBLE
                }, 2000)
            }
        }
    }

    fun release() {
        scope.cancel()
        smartDetector.close()
    }
}

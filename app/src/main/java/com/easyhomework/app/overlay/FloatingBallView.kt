package com.easyhomework.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Custom circular floating ball view with gradient background and breathing animation.
 * Supports normal and mini (compact + semi-transparent) modes.
 */
class FloatingBallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var isMiniMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val gradientColors = intArrayOf(
        Color.parseColor("#7C4DFF"),
        Color.parseColor("#448AFF")
    )

    private val glowColor = Color.parseColor("#806C63FF")

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.OUTER)
    }

    private var breathingScale = 1f
    private var breathingAnimator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        startBreathingAnimation()
    }

    private fun startBreathingAnimation() {
        breathingAnimator = ValueAnimator.ofFloat(1f, 1.06f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                breathingScale = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - if (isMiniMode) 4f else 16f
        val alpha = if (isMiniMode) 160 else 255 // Semi-transparent in mini mode

        if (!isMiniMode) {
            // Draw glow (only in normal mode)
            canvas.save()
            canvas.scale(breathingScale, breathingScale, cx, cy)
            glowPaint.shader = RadialGradient(
                cx, cy, radius + 15f,
                glowColor, Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius + 10f, glowPaint)
            canvas.restore()
        }

        // Draw gradient background circle
        backgroundPaint.shader = LinearGradient(
            cx - radius, cy - radius,
            cx + radius, cy + radius,
            gradientColors, null,
            Shader.TileMode.CLAMP
        )
        backgroundPaint.alpha = alpha
        canvas.drawCircle(cx, cy, radius, backgroundPaint)

        if (isMiniMode) {
            // Mini mode: just a small dot with subtle icon
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.alpha = alpha
                textSize = radius * 0.9f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("✦", cx, cy + radius * 0.3f, dotPaint)
        } else {
            // Normal mode: search + AI icon
            iconPaint.strokeWidth = radius * 0.08f
            iconPaint.alpha = alpha

            val iconSize = radius * 0.45f
            val iconCx = cx - iconSize * 0.1f
            val iconCy = cy - iconSize * 0.1f

            // Glass circle
            canvas.drawCircle(iconCx, iconCy, iconSize * 0.55f, iconPaint)

            // Handle
            val handleStartX = iconCx + iconSize * 0.4f
            val handleStartY = iconCy + iconSize * 0.4f
            val handleEndX = iconCx + iconSize * 0.85f
            val handleEndY = iconCy + iconSize * 0.85f
            iconPaint.strokeWidth = radius * 0.1f
            canvas.drawLine(handleStartX, handleStartY, handleEndX, handleEndY, iconPaint)

            // "AI" text
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.alpha = alpha
                textSize = iconSize * 0.4f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("AI", iconCx, iconCy + iconSize * 0.15f, textPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        breathingAnimator?.cancel()
    }
}

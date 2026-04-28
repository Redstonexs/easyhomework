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
 * Displays a search/book icon.
 */
class FloatingBallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

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
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.OUTER)
    }

    private var breathingScale = 1f
    private var breathingAnimator: ValueAnimator? = null

    init {
        // Enable software rendering for blur effect
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        startBreathingAnimation()
    }

    private fun startBreathingAnimation() {
        breathingAnimator = ValueAnimator.ofFloat(1f, 1.08f, 1f).apply {
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
        val radius = min(width, height) / 2f - 20f

        // Draw glow
        canvas.save()
        canvas.scale(breathingScale, breathingScale, cx, cy)
        glowPaint.shader = RadialGradient(
            cx, cy, radius + 15f,
            glowColor, Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius + 10f, glowPaint)
        canvas.restore()

        // Draw gradient background circle
        backgroundPaint.shader = LinearGradient(
            cx - radius, cy - radius,
            cx + radius, cy + radius,
            gradientColors, null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, backgroundPaint)

        // Draw search icon (magnifying glass)
        val iconSize = radius * 0.45f
        val iconCx = cx - iconSize * 0.1f
        val iconCy = cy - iconSize * 0.1f

        // Glass circle
        iconPaint.strokeWidth = radius * 0.08f
        canvas.drawCircle(iconCx, iconCy, iconSize * 0.55f, iconPaint)

        // Handle
        val handleStartX = iconCx + iconSize * 0.4f
        val handleStartY = iconCy + iconSize * 0.4f
        val handleEndX = iconCx + iconSize * 0.85f
        val handleEndY = iconCy + iconSize * 0.85f
        iconPaint.strokeWidth = radius * 0.1f
        canvas.drawLine(handleStartX, handleStartY, handleEndX, handleEndY, iconPaint)

        // Small "AI" text inside the glass
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = iconSize * 0.4f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("AI", iconCx, iconCy + iconSize * 0.15f, textPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        breathingAnimator?.cancel()
    }
}

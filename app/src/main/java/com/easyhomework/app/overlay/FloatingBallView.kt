package com.easyhomework.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.easyhomework.app.ui.theme.neutralPalette
import kotlin.math.min

/**
 * Custom circular floating ball view with gradient background and breathing animation.
 * Supports normal and mini (compact + semi-transparent) modes.
 */
class FloatingBallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var isMiniMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val palette = neutralPalette(context)
    private val glowColor = Color.argb(if (palette.isDark) 120 else 80, 0, 0, 0)

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.onPrimary
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.OUTER)
    }
    private val miniPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (palette.isDark) Color.parseColor("#B3555555") else Color.parseColor("#B3D0D0D0")
        style = Paint.Style.FILL
    }
    private val miniBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (palette.isDark) Color.parseColor("#B3FFFFFF") else Color.parseColor("#B3111111")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val miniTouchHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (palette.isDark) Color.parseColor("#24444444") else Color.parseColor("#24111111")
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.onPrimary
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
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
        val radius = min(width, height) / 2f - if (isMiniMode) 2f else 4f

        if (isMiniMode) {
            val touchHintRadius = min(width, height) * (MINI_RADIUS_FRACTION + MINI_TOUCH_HINT_EXTRA_FRACTION)
            val miniRadius = min(width, height) * MINI_RADIUS_FRACTION
            canvas.drawCircle(cx, cy, touchHintRadius, miniTouchHintPaint)
            canvas.drawCircle(cx, cy, miniRadius, miniPaint)
            canvas.drawCircle(cx, cy, miniRadius, miniBorderPaint)
        } else {
            val alpha = 220

            // Draw subtle glow
            canvas.save()
            canvas.scale(breathingScale, breathingScale, cx, cy)
            glowPaint.shader = RadialGradient(
                cx, cy, radius + 8f,
                glowColor, Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius + 5f, glowPaint)
            canvas.restore()

            backgroundPaint.shader = null
            backgroundPaint.color = palette.primary
            backgroundPaint.alpha = alpha
            canvas.drawCircle(cx, cy, radius, backgroundPaint)

            // Small dot icon
            dotPaint.alpha = alpha
            dotPaint.textSize = radius * 0.9f
            canvas.drawText("✦", cx, cy + radius * 0.3f, dotPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        breathingAnimator?.cancel()
    }

    private companion object {
        const val MINI_RADIUS_FRACTION = 0.21f
        const val MINI_TOUCH_HINT_EXTRA_FRACTION = 0.06f
    }
}

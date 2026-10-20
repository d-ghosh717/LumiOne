package com.lumione.player.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * A sleek, high-fidelity Cyan Pulse Audio Waveform Visualizer.
 * Matches the official LumiOne cyan waveform brand identity.
 * Animates rhythmically when playback is active; rests at baseline when paused.
 */
class CyanWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val barRect = RectF()
    private val barCount = 28
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var isPlaying = false

    // Baseline bar height distribution (symmetrical acoustic waveform profile)
    private val baseFractions = floatArrayOf(
        0.18f, 0.28f, 0.42f, 0.35f, 0.55f, 0.70f, 0.48f,
        0.82f, 0.95f, 0.65f, 0.88f, 1.00f, 0.78f, 0.90f,
        0.90f, 0.78f, 1.00f, 0.88f, 0.65f, 0.95f, 0.82f,
        0.48f, 0.70f, 0.55f, 0.35f, 0.42f, 0.28f, 0.18f
    )

    init {
        setupAnimator()
    }

    private fun setupAnimator() {
        animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                phase = va.animatedValue as Float
                invalidate()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h > 0) {
            val gradient = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(
                    Color.parseColor("#38D9FF"),
                    Color.parseColor("#258BFF"),
                    Color.parseColor("#1255A6")
                ),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
            barPaint.shader = gradient
        }
    }

    fun setPlaying(playing: Boolean) {
        if (isPlaying == playing) return
        isPlaying = playing
        if (playing) {
            if (animator?.isPaused == true) {
                animator?.resume()
            } else if (animator?.isRunning != true) {
                animator?.start()
            }
        } else {
            animator?.pause()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val totalSpacing = w * 0.40f
        val gap = totalSpacing / (barCount + 1)
        val barWidth = (w - totalSpacing) / barCount
        val cornerRadius = barWidth / 2f
        val centerY = h / 2f

        for (i in 0 until barCount) {
            val baseFrac = baseFractions[i]
            val modulation = if (isPlaying) {
                0.3f * sin(phase + i * 0.45f).toFloat()
            } else {
                0f
            }
            val currentFrac = (baseFrac + modulation).coerceIn(0.12f, 1.0f)
            val barH = h * currentFrac * 0.85f

            val left = gap + i * (barWidth + gap)
            val right = left + barWidth
            val top = centerY - barH / 2f
            val bottom = centerY + barH / 2f

            barRect.set(left, top, right, bottom)
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isPlaying && animator?.isPaused == true) {
            animator?.resume()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.pause()
    }
}

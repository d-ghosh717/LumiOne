package com.lumione.player.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

/**
 * A mathematically precise rotating Vinyl Disc component.
 *
 * Ensures that outer vinyl, concentric sound grooves, specular highlights,
 * circular album art label, and center spindle share the EXACT same center (cx, cy)
 * and rotate together as a single physical entity around that center.
 *
 * Automatically detects and trims YouTube 4:3 letterboxing so that album artwork
 * is ALWAYS a true, flawless circle with no rectangular black cuts.
 */
class VinylDiscView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Center & radii
    private var cx = 0f
    private var cy = 0f
    private var discRadius = 0f
    private var labelRadius = 0f
    private var spindleHoleRadius = 0f
    private var spindleRimRadius = 0f

    // Rotation state
    private var currentRotation = 0f
    private var animator: ValueAnimator? = null
    private var isPlaying = false

    // Artwork
    private var artworkBitmap: Bitmap? = null
    private var artworkShader: BitmapShader? = null
    private val shaderMatrix = Matrix()

    // Paints
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0A0B0E") // Deep vinyl black
    }

    private val discEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#1B1E26")
    }

    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#161820")
    }

    private val grooveHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.85f
        color = Color.parseColor("#262936")
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#141822")
    }

    // High-contrast clean border rim separating artwork label from vinyl grooves
    private val labelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#38D9FF")
        alpha = 190
    }

    private val labelOuterRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#182438")
    }

    private val spindleRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#8E95A5") // Silver metallic rim
    }

    private val spindleHolePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#060709") // Deep spindle center hole
    }

    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelClipPath = Path()

    init {
        setupRotationAnimator()
    }

    private fun setupRotationAnimator() {
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 18000L // 18 seconds per full revolution (smooth, elegant)
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { va ->
                currentRotation = va.animatedValue as Float
                invalidate()
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)

        val size = if (wMode == MeasureSpec.EXACTLY && hMode == MeasureSpec.EXACTLY) {
            minOf(w, h)
        } else if (wMode == MeasureSpec.EXACTLY) {
            w
        } else if (hMode == MeasureSpec.EXACTLY) {
            h
        } else {
            minOf(w, h).takeIf { it > 0 } ?: 200
        }
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val diameter = minOf(w, h).toFloat()
        cx = w / 2f
        cy = h / 2f
        discRadius = (diameter / 2f) - 2f
        // Large, clear circular label (~52% of disc radius)
        labelRadius = discRadius * 0.52f
        spindleRimRadius = discRadius * 0.08f
        spindleHoleRadius = discRadius * 0.048f

        labelClipPath.reset()
        labelClipPath.addCircle(cx, cy, labelRadius, Path.Direction.CW)

        // Subtle specular sheen
        val sheenColors = intArrayOf(
            Color.TRANSPARENT,
            Color.argb(16, 255, 255, 255),
            Color.TRANSPARENT,
            Color.argb(16, 255, 255, 255),
            Color.TRANSPARENT
        )
        val sheenPositions = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        sheenPaint.shader = SweepGradient(cx, cy, sheenColors, sheenPositions)

        updateShaderMatrix()
    }

    /**
     * Slices off solid black letterbox bars that YouTube bakes into 4:3 hqdefault images.
     */
    private fun trimBlackLetterbox(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 10 || height <= 10) return source

        fun isBlack(color: Int): Boolean {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return r <= 36 && g <= 36 && b <= 36
        }

        val scanLimit = (height * 0.28f).toInt()
        val sampleXs = intArrayOf(
            (width * 0.15f).toInt(),
            (width * 0.35f).toInt(),
            (width * 0.50f).toInt(),
            (width * 0.65f).toInt(),
            (width * 0.85f).toInt()
        )

        var topCut = 0
        for (y in 0 until scanLimit) {
            var rowAllBlack = true
            for (x in sampleXs) {
                if (!isBlack(source.getPixel(x, y))) {
                    rowAllBlack = false
                    break
                }
            }
            if (rowAllBlack) {
                topCut = y + 1
            } else {
                break
            }
        }

        var bottomCut = 0
        for (y in height - 1 downTo (height - scanLimit)) {
            var rowAllBlack = true
            for (x in sampleXs) {
                if (!isBlack(source.getPixel(x, y))) {
                    rowAllBlack = false
                    break
                }
            }
            if (rowAllBlack) {
                bottomCut = height - y
            } else {
                break
            }
        }

        if (topCut >= 4 || bottomCut >= 4) {
            val validHeight = height - topCut - bottomCut
            if (validHeight >= height * 0.45f) {
                return try {
                    Bitmap.createBitmap(source, 0, topCut, width, validHeight)
                } catch (e: Throwable) {
                    source
                }
            }
        }
        return source
    }

    private fun updateShaderMatrix() {
        val bmp = artworkBitmap ?: return
        if (discRadius <= 0) return

        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        // Provide slight 4% overscan so that any residual 1px edge artifacts are cropped outside the circle
        val targetSize = labelRadius * 2f * 1.04f

        // Center crop to completely fill the circular target viewport without stretching
        val scale = maxOf(targetSize / bw, targetSize / bh)
        val dx = cx - (bw * scale) / 2f
        val dy = cy - (bh * scale) / 2f

        shaderMatrix.reset()
        shaderMatrix.setScale(scale, scale)
        shaderMatrix.postTranslate(dx, dy)
        artworkShader?.setLocalMatrix(shaderMatrix)
    }

    /**
     * Set album artwork bitmap directly. Trims letterbox bars and builds circular shader.
     */
    fun setArtworkBitmap(bitmap: Bitmap?) {
        val cleanBitmap = bitmap?.let { trimBlackLetterbox(it) }
        artworkBitmap = cleanBitmap
        if (cleanBitmap != null) {
            artworkShader = BitmapShader(cleanBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            labelPaint.shader = artworkShader
            updateShaderMatrix()
        } else {
            artworkShader = null
            labelPaint.shader = null
        }
        invalidate()
    }

    /**
     * Load album artwork from a URL using Glide. Automatically converts YouTube
     * hqdefault URLs to unletterboxed mqdefault URLs.
     */
    fun loadArtworkUrl(url: String?) {
        if (url.isNullOrBlank()) {
            setArtworkBitmap(null)
            return
        }

        val cleanUrl = if (url.contains("/hqdefault.jpg")) {
            url.replace("/hqdefault.jpg", "/mqdefault.jpg")
        } else if (url.contains("/sddefault.jpg")) {
            url.replace("/sddefault.jpg", "/mqdefault.jpg")
        } else {
            url
        }

        Glide.with(this)
            .asBitmap()
            .load(cleanUrl)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    setArtworkBitmap(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    setArtworkBitmap(null)
                }
            })
    }

    /**
     * Reset rotation angle back to 0.
     */
    fun resetRotation() {
        currentRotation = 0f
        animator?.currentPlayTime = 0L
        invalidate()
    }

    /**
     * Set playback state: starts or resumes rotation when true; pauses in place when false.
     */
    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        val anim = animator ?: return
        if (playing) {
            if (anim.isPaused) {
                anim.resume()
            } else if (!anim.isRunning) {
                anim.start()
            }
        } else {
            if (anim.isRunning) {
                anim.pause()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (discRadius <= 0f) return

        // ── Rotate the Entire Disc Assembly Around Exact Center (cx, cy) ─────
        // The outer vinyl, grooves, specular highlight sheen, album art label,
        // and center spindle rotate together smoothly as a single physical entity.
        canvas.save()
        canvas.rotate(currentRotation, cx, cy)

        // 1a. Base Vinyl Platter
        canvas.drawCircle(cx, cy, discRadius, discPaint)
        canvas.drawCircle(cx, cy, discRadius, discEdgePaint)

        // 1b. Concentric Vinyl Sound Grooves (between label and outer edge)
        val grooveSpan = discRadius - labelRadius
        val ringCount = 14
        for (i in 1..ringCount) {
            val r = labelRadius + (grooveSpan * (i.toFloat() / (ringCount + 1)))
            val paint = if (i % 3 == 0) grooveHighlightPaint else groovePaint
            canvas.drawCircle(cx, cy, r, paint)
        }

        // 1c. Subtle Specular Highlight Sheen (rotates with the grooves)
        canvas.drawCircle(cx, cy, discRadius, sheenPaint)

        // 2. Circular Album Art Label (rotates in sync with the disc)
        labelClipPath.reset()
        labelClipPath.addCircle(cx, cy, labelRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(labelClipPath)
        if (artworkShader != null) {
            canvas.drawCircle(cx, cy, labelRadius, labelPaint)
        } else {
            // Sleek placeholder when artwork is loading or unavailable
            canvas.drawCircle(cx, cy, labelRadius, placeholderPaint)
            canvas.drawCircle(cx, cy, labelRadius * 0.7f, groovePaint)
        }
        canvas.restore()

        // 3. Crisp Border Rims Around Label
        canvas.drawCircle(cx, cy, labelRadius + 1f, labelOuterRimPaint)
        canvas.drawCircle(cx, cy, labelRadius, labelBorderPaint)

        // 4. Center Spindle (Silver Rim + Deep Center Hole)
        canvas.drawCircle(cx, cy, spindleRimRadius, spindleRimPaint)
        canvas.drawCircle(cx, cy, spindleHoleRadius, spindleHolePaint)

        canvas.restore()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            if (isPlaying) {
                val anim = animator
                if (anim != null) {
                    if (anim.isPaused) anim.resume()
                    else if (!anim.isRunning) anim.start()
                }
            }
        } else {
            animator?.pause()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isPlaying) {
            val anim = animator
            if (anim != null) {
                if (anim.isPaused) anim.resume()
                else if (!anim.isRunning) anim.start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.pause()
    }
}

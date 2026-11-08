package com.desu1243.darker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max

class DimmingOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }
    private val maskCompositePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val oldMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val newMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    private var dimAmount = 0f
    private var textDimAmount = 0.175f
    private var textRegions: List<RectF> = emptyList()
    private var previousTextRegions: List<RectF> = emptyList()
    private var sourceWidth = 1
    private var sourceHeight = 1
    private var marginPx = 12f * density
    private var cornerRadiusPx = 10f * density
    private var featherPx = 12f * density
    private var revealProgress = 0f
    private var revealTarget = 0f
    private var animator: ValueAnimator? = null
    private var transitionProgress = 1f
    private var transitionAnimator: ValueAnimator? = null
    private var fadeDurationMs = 200L
    private var jitterTolerancePx = 5f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setDimAmount(amount: Float) {
        dimAmount = amount.coerceIn(0f, OverlayService.MAX_DIM_AMOUNT)
        textDimAmount = textDimAmount.coerceAtMost(dimAmount)
        invalidate()
    }

    fun setTextDimAmount(amount: Float) {
        textDimAmount = amount.coerceIn(0f, dimAmount)
        invalidate()
    }

    fun setRefreshInterval(intervalMs: Long) {
        fadeDurationMs = intervalMs.coerceIn(50L, 500L) / 2L
    }

    fun setJitterTolerance(tolerancePx: Float) {
        jitterTolerancePx = tolerancePx.coerceIn(0f, 20f)
    }

    fun setTextStyle(marginDp: Float, cornerRadiusDp: Float, featherDp: Float) {
        marginPx = marginDp.coerceAtLeast(0f) * density
        cornerRadiusPx = cornerRadiusDp.coerceAtLeast(0f) * density
        featherPx = featherDp.coerceAtLeast(0f) * density
        invalidate()
    }

    fun setTextRegions(
        regions: List<Rect>,
        imageWidth: Int,
        imageHeight: Int,
        animated: Boolean,
    ) {
        val sameDimensions = sourceWidth == imageWidth && sourceHeight == imageHeight
        val displayed = if (sameDimensions) textRegions.map {
            MaskRegion(it.left, it.top, it.right, it.bottom)
        } else emptyList()
        val detected = regions.map {
            MaskRegion(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
        }
        val newRegions = MaskJitterFilter.stabilize(
            displayed,
            detected,
            if (animated) jitterTolerancePx else 0f,
            if (width > 0) width.toFloat() / imageWidth.coerceAtLeast(1) else 1f,
        ).map { RectF(it.left, it.top, it.right, it.bottom) }
        // Faster OCR often returns the very same mask while its fade is still
        // running. Let that animation finish instead of restarting it.
        if (animated && revealTarget == 1f && newRegions == textRegions &&
            sourceWidth == imageWidth && sourceHeight == imageHeight) return
        sourceWidth = imageWidth.coerceAtLeast(1)
        sourceHeight = imageHeight.coerceAtLeast(1)

        transitionAnimator?.cancel()
        if (!animated) {
            previousTextRegions = emptyList()
            textRegions = newRegions
            transitionProgress = 1f
            animateReveal(1f, 1f, 0L)
            return
        }

        if (revealProgress <= 0.02f || textRegions.isEmpty()) {
            previousTextRegions = emptyList()
            textRegions = newRegions
            transitionProgress = 1f
            revealProgress = 0f
        } else {
            // Cross-fade the geometric difference. Both masks are rendered as
            // opaque unions and then added with complementary alpha, so their
            // common pixels remain fully visible while only entering and
            // leaving pixels fade.
            previousTextRegions = textRegions
            textRegions = newRegions
            transitionProgress = 0f
            transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = fadeDurationMs
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    transitionProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        animateReveal(revealProgress, 1f, fadeDurationMs)
    }

    fun hideTextRegions(animated: Boolean) {
        if (textRegions.isEmpty()) return
        if (animated) {
            animateReveal(revealProgress, 0f, fadeDurationMs)
        } else {
            animator?.cancel()
            revealTarget = 0f
            revealProgress = 0f
            invalidate()
        }
    }

    fun clearTextRegions() {
        animator?.cancel()
        transitionAnimator?.cancel()
        textRegions = emptyList()
        previousTextRegions = emptyList()
        revealProgress = 0f
        revealTarget = 0f
        transitionProgress = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pixelAlpha = ((dimAmount / OverlayService.MAX_DIM_AMOUNT) * 255f)
            .toInt()
            .coerceIn(0, 255)
        dimPaint.color = Color.argb(pixelAlpha, 0, 0, 0)

        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        if (
            revealProgress > 0f &&
            (textRegions.isNotEmpty() || previousTextRegions.isNotEmpty())
        ) {
            val scaleX = width.toFloat() / sourceWidth
            // Preserve captured display coordinates instead of vertically
            // stretching content when navigation bars reduce overlay height.
            val scaleY = scaleX
            val scaledMarginX = marginPx
            val scaledMarginY = marginPx
            val radius = cornerRadiusPx

            val removalFraction = if (dimAmount > 0f) {
                (1f - textDimAmount / dimAmount).coerceIn(0f, 1f)
            } else {
                0f
            }
            maskCompositePaint.alpha = (255f * revealProgress * removalFraction)
                .toInt()
                .coerceIn(0, 255)
            val maskLayer = canvas.saveLayer(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                maskCompositePaint,
            )
            cutoutPaint.alpha = 255
            cutoutPaint.maskFilter = if (featherPx > 0f) {
                BlurMaskFilter(featherPx, BlurMaskFilter.Blur.NORMAL)
            } else {
                null
            }

            if (previousTextRegions.isNotEmpty() && transitionProgress < 1f) {
                oldMaskPaint.alpha = (255f * (1f - transitionProgress))
                    .toInt()
                    .coerceIn(0, 255)
                val oldLayer = canvas.saveLayer(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    oldMaskPaint,
                )
                drawRegions(
                    canvas,
                    previousTextRegions,
                    scaleX,
                    scaleY,
                    scaledMarginX,
                    scaledMarginY,
                    radius,
                )
                canvas.restoreToCount(oldLayer)

                newMaskPaint.alpha = (255f * transitionProgress)
                    .toInt()
                    .coerceIn(0, 255)
                val newLayer = canvas.saveLayer(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    newMaskPaint,
                )
                drawRegions(
                    canvas,
                    textRegions,
                    scaleX,
                    scaleY,
                    scaledMarginX,
                    scaledMarginY,
                    radius,
                )
                canvas.restoreToCount(newLayer)
            } else {
                drawRegions(
                    canvas,
                    textRegions,
                    scaleX,
                    scaleY,
                    scaledMarginX,
                    scaledMarginY,
                    radius,
                )
            }
            cutoutPaint.maskFilter = null
            canvas.restoreToCount(maskLayer)
        }
        canvas.restoreToCount(layer)
    }

    private fun drawRegions(
        canvas: Canvas,
        regions: List<RectF>,
        scaleX: Float,
        scaleY: Float,
        scaledMarginX: Float,
        scaledMarginY: Float,
        radius: Float,
    ) {
        val safeRadius = max(0f, radius)
        regions.forEach { source ->
            val region = RectF(
                source.left * scaleX - scaledMarginX,
                source.top * scaleY - scaledMarginY,
                source.right * scaleX + scaledMarginX,
                source.bottom * scaleY + scaledMarginY,
            )
            canvas.drawRoundRect(region, safeRadius, safeRadius, cutoutPaint)
        }
    }

    private fun animateReveal(from: Float, to: Float, durationMs: Long) {
        animator?.cancel()
        revealTarget = to
        if (durationMs == 0L) {
            revealProgress = to
            invalidate()
            return
        }
        revealProgress = from
        animator = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                revealProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}

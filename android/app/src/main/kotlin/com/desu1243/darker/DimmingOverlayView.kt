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
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    private var dimAmount = 0f
    private var textRegions: List<RectF> = emptyList()
    private var sourceWidth = 1
    private var sourceHeight = 1
    private var marginPx = 12f * density
    private var cornerRadiusPx = 10f * density
    private var featherPx = 12f * density
    private var revealProgress = 0f
    private var animator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setDimAmount(amount: Float) {
        dimAmount = amount.coerceIn(0f, OverlayService.MAX_DIM_AMOUNT)
        invalidate()
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
        sourceWidth = imageWidth.coerceAtLeast(1)
        sourceHeight = imageHeight.coerceAtLeast(1)
        textRegions = regions.map { RectF(it) }
        animateReveal(
            if (animated) revealProgress else 1f,
            1f,
            if (animated) 180L else 0L,
        )
    }

    fun hideTextRegions(animated: Boolean) {
        if (textRegions.isEmpty()) return
        if (animated) {
            animateReveal(revealProgress, 0f, 120L)
        } else {
            animator?.cancel()
            revealProgress = 0f
            invalidate()
        }
    }

    fun clearTextRegions() {
        animator?.cancel()
        textRegions = emptyList()
        revealProgress = 0f
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

        if (revealProgress > 0f && textRegions.isNotEmpty()) {
            val scaleX = width.toFloat() / sourceWidth
            // Preserve captured display coordinates instead of vertically
            // stretching content when navigation bars reduce overlay height.
            val scaleY = scaleX
            val scaledMarginX = marginPx
            val scaledMarginY = marginPx
            val radius = cornerRadiusPx

            cutoutPaint.alpha = (255f * revealProgress).toInt().coerceIn(0, 255)
            cutoutPaint.maskFilter = if (featherPx > 0f) {
                BlurMaskFilter(featherPx, BlurMaskFilter.Blur.NORMAL)
            } else {
                null
            }

            textRegions.forEach { source ->
                val region = RectF(
                    source.left * scaleX - scaledMarginX,
                    source.top * scaleY - scaledMarginY,
                    source.right * scaleX + scaledMarginX,
                    source.bottom * scaleY + scaledMarginY,
                )
                val safeRadius = max(0f, radius)
                canvas.drawRoundRect(region, safeRadius, safeRadius, cutoutPaint)
            }
            cutoutPaint.maskFilter = null
        }
        canvas.restoreToCount(layer)
    }

    private fun animateReveal(from: Float, to: Float, durationMs: Long) {
        animator?.cancel()
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

package com.desu1243.darker

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlin.math.max

/** The sole implementation of software overlay composition; one instance per thread. */
internal class OverlayAlphaRenderer {
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

    fun draw(canvas: Canvas, state: OverlayAppearance) {
        val pixelAlpha = ((state.dimAmount / OverlayService.MAX_DIM_AMOUNT) * 255f)
            .toInt()
            .coerceIn(0, 255)
        dimPaint.color = Color.argb(pixelAlpha, 0, 0, 0)

        val layer = canvas.saveLayer(0f, 0f, state.width.toFloat(), state.height.toFloat(), null)
        canvas.drawRect(0f, 0f, state.width.toFloat(), state.height.toFloat(), dimPaint)

        if (
            state.revealProgress > 0f &&
            (state.textRegions.isNotEmpty() || state.previousTextRegions.isNotEmpty())
        ) {
            val scaleX = state.width.toFloat() / state.sourceWidth
            // Preserve captured display coordinates instead of vertically
            // stretching content when navigation bars reduce overlay height.
            val scaleY = scaleX
            val scaledMarginX = state.marginPx
            val scaledMarginY = state.marginPx
            val radius = state.cornerRadiusPx

            val removalFraction = if (state.dimAmount > 0f) {
                (1f - state.textDimAmount / state.dimAmount).coerceIn(0f, 1f)
            } else {
                0f
            }
            maskCompositePaint.alpha = (255f * state.revealProgress * removalFraction)
                .toInt()
                .coerceIn(0, 255)
            val maskLayer = canvas.saveLayer(
                0f,
                0f,
                state.width.toFloat(),
                state.height.toFloat(),
                maskCompositePaint,
            )
            cutoutPaint.alpha = 255
            cutoutPaint.maskFilter = if (state.featherPx > 0f) {
                BlurMaskFilter(state.featherPx, BlurMaskFilter.Blur.NORMAL)
            } else {
                null
            }

            if (state.previousTextRegions.isNotEmpty() && state.transitionProgress < 1f) {
                oldMaskPaint.alpha = (255f * (1f - state.transitionProgress))
                    .toInt()
                    .coerceIn(0, 255)
                val oldLayer = canvas.saveLayer(
                    0f,
                    0f,
                    state.width.toFloat(),
                    state.height.toFloat(),
                    oldMaskPaint,
                )
                drawRegions(
                    canvas,
                    state.previousTextRegions,
                    scaleX,
                    scaleY,
                    scaledMarginX,
                    scaledMarginY,
                    radius,
                )
                canvas.restoreToCount(oldLayer)

                newMaskPaint.alpha = (255f * state.transitionProgress)
                    .toInt()
                    .coerceIn(0, 255)
                val newLayer = canvas.saveLayer(
                    0f,
                    0f,
                    state.width.toFloat(),
                    state.height.toFloat(),
                    newMaskPaint,
                )
                drawRegions(
                    canvas,
                    state.textRegions,
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
                    state.textRegions,
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
        regions: List<MaskRegion>,
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
}

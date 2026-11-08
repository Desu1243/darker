package com.desu1243.darker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

class DimmingOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val alphaRenderer = OverlayAlphaRenderer()
    private var lastRendered: OverlayRenderSnapshot? = null
    private var renderVersion = 0L

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

    private fun appearance() = OverlayAppearance(
        dimAmount, textDimAmount, OverlayService.MAX_DIM_AMOUNT,
        textRegions.map { MaskRegion(it.left, it.top, it.right, it.bottom) },
        previousTextRegions.map { MaskRegion(it.left, it.top, it.right, it.bottom) },
        sourceWidth, sourceHeight, revealProgress, transitionProgress,
        marginPx, cornerRadiusPx, featherPx, width, height,
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = appearance()
        alphaRenderer.draw(canvas, state)
        val now = System.nanoTime()
        val old = lastRendered
        lastRendered = OverlayRenderSnapshot(
            state, ++renderVersion, now,
            if (old?.appearance == state) old.stableSinceNanos else now,
        )
    }

    // Main-thread only. Comparing pending state also rejects setters not drawn yet.
    internal fun reconstructionSnapshot(): OverlayRenderSnapshot? {
        val rendered = lastRendered ?: return null
        if (animator?.isRunning == true || transitionAnimator?.isRunning == true ||
            rendered.appearance != appearance()) return null
        return rendered
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

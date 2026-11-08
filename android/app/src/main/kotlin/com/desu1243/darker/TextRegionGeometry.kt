package com.desu1243.darker

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** All tracker geometry is in capture pixels; style/tolerance enter in overlay pixels. */
internal data class TextRegionTrackingConfig(
    val captureWidth: Int,
    val captureHeight: Int,
    val overlayWidth: Int,
    val overlayHeight: Int,
    val marginPx: Float,
    val cornerRadiusPx: Float,
    val featherPx: Float,
    val jitterTolerancePx: Float = 5f,
    val refreshIntervalMs: Long = 400,
) {
    val scale get() = overlayWidth.toFloat() / captureWidth.coerceAtLeast(1)
    fun capturePixels(overlayPixels: Float) = overlayPixels / scale.coerceAtLeast(0.001f)
    val tolerance get() = capturePixels(jitterTolerancePx.coerceIn(0f, 20f))
    val shrinkTimeMs get() = max(600L, refreshIntervalMs.coerceIn(50, 500) * 2)
    val missingTimeMs get() = max(350L, (refreshIntervalMs.coerceIn(50, 500) * 1.5).toLong())
    fun sameMapping(other: TextRegionTrackingConfig) =
        captureWidth == other.captureWidth && captureHeight == other.captureHeight &&
            overlayWidth == other.overlayWidth && overlayHeight == other.overlayHeight &&
            marginPx == other.marginPx && cornerRadiusPx == other.cornerRadiusPx && featherPx == other.featherPx
}

internal object TextRegionGeometry {
    val order = compareBy<MaskRegion>({ it.top }, { it.left }, { it.bottom }, { it.right })
    fun lowerBound(values: FloatArray, value: Float): Int {
        var left = 0
        var right = values.size
        while (left < right) {
            val middle = (left + right) ushr 1
            if (values[middle] < value) left = middle + 1 else right = middle
        }
        return left
    }
    fun upperBound(values: FloatArray, value: Float): Int {
        var left = 0
        var right = values.size
        while (left < right) {
            val middle = (left + right) ushr 1
            if (values[middle] <= value) left = middle + 1 else right = middle
        }
        return left
    }
    fun width(r: MaskRegion) = r.right - r.left
    fun height(r: MaskRegion) = r.bottom - r.top
    fun cx(r: MaskRegion) = (r.left + r.right) / 2
    fun cy(r: MaskRegion) = (r.top + r.bottom) / 2
    fun area(r: MaskRegion) = max(0f, width(r)) * max(0f, height(r))
    fun intersection(a: MaskRegion, b: MaskRegion) =
        max(0f, min(a.right, b.right) - max(a.left, b.left)) *
            max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
    fun iou(a: MaskRegion, b: MaskRegion): Float {
        val intersection = intersection(a, b)
        return intersection / max(0.001f, area(a) + area(b) - intersection)
    }
    fun union(a: MaskRegion, b: MaskRegion) = MaskRegion(
        min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom),
    )
    fun edgeDistance(a: MaskRegion, b: MaskRegion) = max(
        max(abs(a.left - b.left), abs(a.top - b.top)),
        max(abs(a.right - b.right), abs(a.bottom - b.bottom)),
    )
    fun centerDistance(a: MaskRegion, b: MaskRegion) = hypot(cx(a) - cx(b), cy(a) - cy(b))
}

internal object SafeAreaEvaluator {
    // Conservative approximation to >=98% mask coverage: Skia's usual
    // radius-to-sigma conversion, a 3-sigma erosion, plus 1 px for raster AA.
    // Actual raster coverage is checked independently in native graphics tests.
    fun contains(stable: MaskRegion, observed: MaskRegion, config: TextRegionTrackingConfig): Boolean {
        val scale = config.scale
        if (!scale.isFinite() || scale <= 0) return false
        val blurInset = if (config.featherPx > 0) 3f * (0.57735f * config.featherPx + 0.5f) + 1f else 1f
        val left = stable.left * scale - config.marginPx + blurInset
        val top = stable.top * scale - config.marginPx + blurInset
        val right = stable.right * scale + config.marginPx - blurInset
        val bottom = stable.bottom * scale + config.marginPx - blurInset
        if (left >= right || top >= bottom) return false
        val radius = max(0f, min(config.cornerRadiusPx, min(
            (stable.right - stable.left) * scale / 2 + config.marginPx,
            (stable.bottom - stable.top) * scale / 2 + config.marginPx,
        )) - blurInset)
        val padding = 2f // Overlay pixels, independent of density and capture size.
        if (observed.left * scale - padding < 0 || observed.top * scale - padding < 0 ||
            observed.right * scale + padding > config.overlayWidth ||
            observed.bottom * scale + padding > config.overlayHeight) return false
        fun corner(x: Float, y: Float): Boolean {
            if (x < left || x > right || y < top || y > bottom) return false
            val dx = max(max(left + radius - x, x - (right - radius)), 0f)
            val dy = max(max(top + radius - y, y - (bottom - radius)), 0f)
            return dx * dx + dy * dy <= radius * radius
        }
        return corner(observed.left * scale - padding, observed.top * scale - padding) &&
            corner(observed.right * scale + padding, observed.top * scale - padding) &&
            corner(observed.left * scale - padding, observed.bottom * scale + padding) &&
            corner(observed.right * scale + padding, observed.bottom * scale + padding)
    }
}

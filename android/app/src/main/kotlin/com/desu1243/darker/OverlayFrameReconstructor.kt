package com.desu1243.darker

import android.graphics.Bitmap
import android.graphics.Canvas
import kotlin.math.roundToInt

internal object OverlayInverseMath {
    const val TRANSMISSION_FLOOR = 0.05f

    fun channel(value: Int, alpha: Float): Int {
        if (!alpha.isFinite() || alpha !in 0f..1f ||
            1f - alpha < TRANSMISSION_FLOOR) return value
        return (value / (1f - alpha)).roundToInt().coerceIn(0, 255)
    }

    fun pixel(pixel: Int, alpha: Float): Int =
        (pixel and -0x1000000) or
            (channel((pixel ushr 16) and 255, alpha) shl 16) or
            (channel((pixel ushr 8) and 255, alpha) shl 8) or
            channel(pixel and 255, alpha)
}

/** Worker-confined, in-place reconstruction of an exclusively owned OCR bitmap. */
internal class OverlayFrameReconstructor : AutoCloseable {
    private val renderer = OverlayAlphaRenderer()
    private var raster: Bitmap? = null
    private var cachedAppearance: OverlayAppearance? = null
    private var alphaBytes = ByteArray(0)
    private val channelLookup = ByteArray(256 * 256)
    private var minAlpha = 0f
    private var maxAlpha = 0f
    private var pixels = IntArray(0)

    data class Metrics(val pixels: Int, val minAlpha: Float, val maxAlpha: Float, val bufferBytes: Long)

    fun reconstruct(bitmap: Bitmap, snapshot: OverlayRenderSnapshot): Metrics? {
        val state = snapshot.appearance
        if (bitmap.config != Bitmap.Config.ARGB_8888 || !bitmap.isMutable ||
            bitmap.width != state.width || bitmap.height != state.height ||
            !state.windowAlpha.isFinite() || state.windowAlpha !in 0f..1f) return null
        var mask = raster
        if (mask == null || mask.width != bitmap.width || mask.height != bitmap.height) {
            mask?.recycle()
            raster = null
            cachedAppearance = null
            mask = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            raster = mask
            alphaBytes = ByteArray(bitmap.width * bitmap.height)
            pixels = IntArray(bitmap.width * bitmap.height)
        }
        if (cachedAppearance != state) {
            // Invalidate first: an interrupted render must never reuse old alpha.
            cachedAppearance = null
            mask.eraseColor(0)
            renderer.draw(Canvas(mask), state)
            mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
            var minByte = 255
            var maxByte = 0
            for (i in pixels.indices) {
                val alpha = pixels[i] ushr 24
                alphaBytes[i] = alpha.toByte()
                minByte = minOf(minByte, alpha)
                maxByte = maxOf(maxByte, alpha)
            }
            minAlpha = minByte / 255f * state.windowAlpha
            maxAlpha = maxByte / 255f * state.windowAlpha
            // Inspect alpha before changing pixels. Opaque sources are lost,
            // and cannot be recovered by clamping a denominator.
            if (1f - maxAlpha < OverlayInverseMath.TRANSMISSION_FLOOR) return null
            for (alpha in 0..255) {
                val effective = alpha / 255f * state.windowAlpha
                for (channel in 0..255) {
                    channelLookup[(alpha shl 8) or channel] =
                        OverlayInverseMath.channel(channel, effective).toByte()
                }
            }
            cachedAppearance = state
        }
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val offset = (alphaBytes[i].toInt() and 255) shl 8
            val red = channelLookup[offset or ((p ushr 16) and 255)].toInt() and 255
            val green = channelLookup[offset or ((p ushr 8) and 255)].toInt() and 255
            val blue = channelLookup[offset or (p and 255)].toInt() and 255
            pixels[i] = (p and -0x1000000) or (red shl 16) or (green shl 8) or blue
        }
        // Do not mutate the input until rendering and inverse calculation succeed.
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Metrics(bitmap.width * bitmap.height, minAlpha, maxAlpha,
            mask.allocationByteCount.toLong() + 4L * pixels.size + alphaBytes.size + channelLookup.size)
    }

    override fun close() {
        raster?.recycle()
        raster = null
        cachedAppearance = null
        alphaBytes = ByteArray(0)
        pixels = IntArray(0)
    }
}

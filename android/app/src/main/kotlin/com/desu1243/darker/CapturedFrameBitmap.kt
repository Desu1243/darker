package com.desu1243.darker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.Image
import java.nio.ByteBuffer

/** Copies capture planes; the caller retains responsibility for closing Image. */
internal object CapturedFrameBitmap {
    fun copy(image: Image): Bitmap {
        val plane = image.planes[0]
        return copy(image.width, image.height, plane.pixelStride, plane.rowStride, plane.buffer)
    }

    internal fun copy(width: Int, height: Int, pixelStride: Int, rowStride: Int, buffer: ByteBuffer): Bitmap {
        require(pixelStride == 4 && rowStride >= width * pixelStride && rowStride % pixelStride == 0)
        val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        try {
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            if (paddedWidth == width) return padded
            // A subset Bitmap.createBitmap(source, ...) may be immutable.
            // Crop into a mutable OCR bitmap so row-padded captures also support
            // in-place reconstruction without a third full-size copy.
            val cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(cropped).drawBitmap(padded, 0f, 0f, null)
            } catch (error: Throwable) {
                cropped.recycle()
                throw error
            }
            padded.recycle()
            return cropped
        } catch (error: Throwable) {
            padded.recycle()
            throw error
        }
    }
}

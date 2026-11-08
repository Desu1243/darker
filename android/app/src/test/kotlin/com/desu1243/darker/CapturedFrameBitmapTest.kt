package com.desu1243.darker

import java.nio.ByteBuffer
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapturedFrameBitmapTest {
    private fun rgbaBuffer(padding: Int): ByteBuffer {
        // Ask this runtime for its native bitmap storage order. Windows Skia's
        // native byte order differs from Android; this test checks copy/crop,
        // not MediaProjection's platform-specific plane format.
        val reference = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        for (y in 0..1) for (x in 0..1) {
            reference.setPixel(x, y, -0x1000000 or ((10 + x) shl 16) or ((20 + y) shl 8) or 30)
        }
        val packed = ByteBuffer.allocateDirect(16)
        reference.copyPixelsToBuffer(packed)
        packed.rewind()
        val padded = ByteBuffer.allocateDirect((2 + padding) * 2 * 4)
        repeat(2) {
            repeat(8) { padded.put(packed.get()) }
            repeat(padding * 4) { padded.put(0.toByte()) }
        }
        reference.recycle()
        padded.rewind()
        return padded
    }

    @Test fun paddedAndUnpaddedCopiesAreMutableAlignedAndIndependentOfCaptureBuffer() {
        for (padding in listOf(0, 2)) {
            val buffer = rgbaBuffer(padding)
            val bitmap = CapturedFrameBitmap.copy(2, 2, 4, (2 + padding) * 4, buffer)
            buffer.rewind()
            while (buffer.hasRemaining()) buffer.put(0.toByte())
            assertTrue(bitmap.isMutable)
            assertEquals(2, bitmap.width)
            assertEquals(2, bitmap.height)
            assertEquals(0xff0a141e.toInt(), bitmap.getPixel(0, 0))
            assertEquals(0xff0b151e.toInt(), bitmap.getPixel(1, 1))
            val state = OverlayAppearance(0f, 0f, 0.7f, emptyList(), emptyList(),
                2, 2, 0f, 1f, 0f, 0f, 0f, 2, 2)
            OverlayFrameReconstructor().use {
                assertNotNull(it.reconstruct(bitmap, OverlayRenderSnapshot(state, 1, 0, 0)))
            }
            assertEquals(0xff0b151e.toInt(), bitmap.getPixel(1, 1))
            bitmap.recycle()
            assertTrue(bitmap.isRecycled)
        }
    }
}

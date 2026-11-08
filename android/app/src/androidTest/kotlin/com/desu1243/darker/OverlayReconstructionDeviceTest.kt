package com.desu1243.darker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** Real Android Canvas smoke/benchmark; deliberately does not capture the screen. */
@RunWith(AndroidJUnit4::class)
class OverlayReconstructionDeviceTest {
    @Test fun paddedAndroidRgbaPlanesKeepChannelOrderAndMutableCrop() {
        val buffer = ByteBuffer.allocateDirect(32)
        repeat(2) {
            repeat(2) { buffer.put(byteArrayOf(10, 20, 30, 255.toByte())) }
            repeat(8) { buffer.put(0.toByte()) }
        }
        buffer.rewind()
        val bitmap = CapturedFrameBitmap.copy(2, 2, 4, 16, buffer)
        assertTrue(bitmap.isMutable)
        assertEquals(0xff0a141e.toInt(), bitmap.getPixel(1, 1))
        bitmap.recycle()
    }

    @Test fun nativeCanvasBoundaryAndFullHdReconstruction() {
        val state = OverlayAppearance(0.35f, 0.1f, 0.7f,
            listOf(MaskRegion(200f, 300f, 800f, 400f)), emptyList(),
            1080, 2400, 1f, 1f, 12f, 10f, 12f, 1080, 2400)
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val alpha = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        OverlayAlphaRenderer().draw(Canvas(alpha), state)
        assertEquals(127, alpha.getPixel(0, 0) ushr 24)
        assertTrue((alpha.getPixel(200, 350) ushr 24) < 127)
        val snapshot = OverlayRenderSnapshot(state, 1, 0, 0)
        OverlayFrameReconstructor().use { reconstructor ->
            val timings = mutableListOf<Double>()
            repeat(6) { iteration ->
                bitmap.eraseColor(0xff404040.toInt())
                val start = System.nanoTime()
                val metrics = reconstructor.reconstruct(bitmap, snapshot)
                val duration = (System.nanoTime() - start) / 1_000_000.0
                assertNotNull(metrics)
                assertTrue((bitmap.getPixel(0, 0) and 255) in 97..99)
                if (iteration > 0) timings.add(duration)
                Log.i("DarkerBenchmark", "iteration=$iteration durationMs=$duration buffers=${metrics?.bufferBytes}")
            }
            Log.i("DarkerBenchmark", "warmMedianMs=${timings.sorted()[timings.size / 2]}")
        }
        alpha.recycle()
        bitmap.recycle()
    }
}

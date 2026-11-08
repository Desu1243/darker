package com.desu1243.darker

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayAlphaRendererTest {
    private val box = MaskRegion(16f, 16f, 48f, 48f)
    private fun raster(state: OverlayAppearance): Bitmap =
        Bitmap.createBitmap(state.width, state.height, Bitmap.Config.ARGB_8888).also {
            OverlayAlphaRenderer().draw(Canvas(it), state)
        }
    private fun alpha(b: Bitmap, x: Int, y: Int) = b.getPixel(x, y) ushr 24

    @Test fun backgroundAndTwoStageWindowAlpha() {
        val b = raster(appearance())
        assertEquals(127, alpha(b, 0, 0))
        b.eraseColor(0xff646464.toInt())
        OverlayFrameReconstructor().use {
            it.reconstruct(b, OverlayRenderSnapshot(appearance(), 1, 0, 0))
        }
        // 100 / (1 - (127/255 * 0.70)) = 153.52 -> 154.
        // Applying window alpha twice or omitting it gives a different result.
        assertEquals(0xff9a9a9a.toInt(), b.getPixel(0, 0))
        b.recycle()
    }

    @Test fun fullAndPartialRevealAndTextDimming() {
        for (reveal in listOf(0f, 0.25f, 0.5f, 1f)) {
            val b = raster(appearance(regions = listOf(box), reveal = reveal))
            val removal = (255 * reveal).toInt()
            val expected = (127 * (1f - removal / 255f)).roundToInt()
            assertTrue(abs(expected - alpha(b, 32, 32)) <= 1)
            assertEquals(127, alpha(b, 0, 0))
            b.recycle()
        }
        val b = raster(appearance(textDim = 0.175f, regions = listOf(box)))
        assertTrue(abs(alpha(b, 32, 32) - 64) <= 1)
        b.recycle()
    }

    @Test fun roundedCornersRetainDimming() {
        val b = raster(appearance(regions = listOf(box), radius = 12f))
        assertEquals(0, alpha(b, 32, 32))
        assertTrue(alpha(b, 16, 16) > 100)
        b.recycle()
    }

    @Test fun blurredBoundaryAndOverlapsUseCanvasSourceOver() {
        val single = raster(appearance(regions = listOf(box), feather = 6f))
        val overlap = raster(appearance(regions = listOf(box, box), feather = 6f))
        val edge = alpha(single, 16, 32)
        assertTrue("Expected feather coverage, got $edge", edge in 1..126)
        assertTrue(alpha(overlap, 16, 32) < edge)
        assertTrue(alpha(single, 12, 32) > edge)
        single.recycle()
        overlap.recycle()
    }

    @Test fun geometryCrossFadeKeepsCommonInteriorAndBlendsDifference() {
        val old = MaskRegion(0f, 16f, 32f, 48f)
        val new = MaskRegion(16f, 16f, 48f, 48f)
        for (t in listOf(0f, 0.25f, 0.5f, 1f)) {
            val b = raster(appearance(regions = listOf(new), old = listOf(old), transition = t))
            assertTrue(alpha(b, 24, 32) <= 1) // Complementary integer weights sum to 254 or 255.
            assertTrue(abs(alpha(b, 40, 32) - (127 * (1 - t)).roundToInt()) <= 1)
            b.recycle()
        }
    }

    @Test fun reconstructSyntheticRasterIncludingBoundariesAndDifferentRgb() {
        val state = appearance(regions = listOf(box), feather = 5f, radius = 8f, reveal = 0.65f)
        val mask = raster(state)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 64) {
            val transmission = 1f - alpha(mask, x, y) / 255f * state.windowAlpha
            val r = (x * 4 * transmission).roundToInt()
            val g = (y * 4 * transmission).roundToInt()
            val b = (127 * transmission).roundToInt()
            bitmap.setPixel(x, y, -0x1000000 or (r shl 16) or (g shl 8) or b)
        }
        OverlayFrameReconstructor().use { reconstructor ->
            assertNotNull(reconstructor.reconstruct(bitmap, OverlayRenderSnapshot(state, 1, 0, 0)))
            for (y in 0 until 64) for (x in 0 until 64) {
                val pixel = bitmap.getPixel(x, y)
                assertTrue(abs(((pixel ushr 16) and 255) - x * 4) <= 2)
                assertTrue(abs(((pixel ushr 8) and 255) - y * 4) <= 2)
                assertTrue(abs((pixel and 255) - 127) <= 2)
            }
        }
        bitmap.recycle()
        mask.recycle()
    }

    @Test fun dimensionsMismatchIsUnmodifiedAndBuffersSurviveResizeAndClose() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff123456.toInt())
        val reconstructor = OverlayFrameReconstructor()
        assertNull(reconstructor.reconstruct(bitmap, OverlayRenderSnapshot(appearance(), 1, 0, 0)))
        assertEquals(0xff123456.toInt(), bitmap.getPixel(0, 0))
        val state = appearance(dim = 0f, width = 32, height = 32)
        repeat(2) {
            assertNotNull(reconstructor.reconstruct(bitmap, OverlayRenderSnapshot(state, 2, 0, 0)))
            assertEquals(0xff123456.toInt(), bitmap.getPixel(0, 0))
            reconstructor.close()
        }
        val smaller = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        smaller.eraseColor(0xff123456.toInt())
        assertNotNull(reconstructor.reconstruct(smaller,
            OverlayRenderSnapshot(appearance(dim = 0f, width = 16, height = 16), 3, 0, 0)))
        assertEquals(0xff123456.toInt(), smaller.getPixel(0, 0))
        reconstructor.close()
        smaller.recycle()
        bitmap.recycle()
    }

    @Test fun viewPublishesOnlyDrawnCoherentStateAndRejectsPendingUpdates() {
        val view = DimmingOverlayView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 64, 64)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        assertNull(view.reconstructionSnapshot())
        view.setDimAmount(0.35f)
        bitmap.eraseColor(0)
        view.draw(Canvas(bitmap))
        val first = view.reconstructionSnapshot()!!
        view.setTextStyle(1f, 2f, 3f)
        assertNull(view.reconstructionSnapshot())
        bitmap.eraseColor(0)
        view.draw(Canvas(bitmap))
        val second = view.reconstructionSnapshot()!!
        assertTrue(second.version > first.version)
        assertTrue(second.stableSinceNanos >= first.stableSinceNanos)
        assertEquals(0f, first.appearance.revealProgress)
        view.setTextRegions(listOf(android.graphics.Rect(16, 16, 48, 48)), 64, 64, false)
        assertNull(view.reconstructionSnapshot())
        bitmap.eraseColor(0)
        view.draw(Canvas(bitmap))
        assertEquals(1f, view.reconstructionSnapshot()!!.appearance.revealProgress)
        // The actual view and standalone alpha renderer use the same operations.
        val expected = raster(view.reconstructionSnapshot()!!.appearance)
        for (y in 0 until 64) for (x in 0 until 64) {
            assertEquals(alpha(expected, x, y), alpha(bitmap, x, y))
        }
        expected.recycle()
        bitmap.recycle()
    }

    @Test fun unrecoverableFrameFallsBackBeforeAnyPixelMutation() {
        val state = OverlayAppearance(0.7f, 0f, 1f, emptyList(), emptyList(),
            64, 64, 1f, 1f, 0f, 0f, 0f, 64, 64)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff123456.toInt())
        OverlayFrameReconstructor().use {
            assertNull(it.reconstruct(bitmap, OverlayRenderSnapshot(state, 1, 0, 0)))
        }
        assertEquals(0xff123456.toInt(), bitmap.getPixel(32, 32))
        bitmap.recycle()
    }

    @Test fun interruptedGeometryAndRevealAnimationsNeverPublishEligibleState() {
        val view = DimmingOverlayView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 64, 64)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        view.setTextRegions(listOf(android.graphics.Rect(16, 16, 48, 48)), 64, 64, false)
        view.draw(Canvas(bitmap))
        assertNotNull(view.reconstructionSnapshot())
        for (left in listOf(24, 4)) {
            view.setTextRegions(listOf(android.graphics.Rect(left, 16, left + 20, 48)), 64, 64, true)
            bitmap.eraseColor(0)
            view.draw(Canvas(bitmap))
            assertNull(view.reconstructionSnapshot())
        }
        view.clearTextRegions()
        bitmap.eraseColor(0)
        view.draw(Canvas(bitmap))
        assertNotNull(view.reconstructionSnapshot())
        bitmap.recycle()
    }
}

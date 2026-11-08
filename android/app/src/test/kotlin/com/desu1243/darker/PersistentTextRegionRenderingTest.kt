package com.desu1243.darker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil
import kotlin.math.floor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersistentTextRegionRenderingTest {
    private fun view() = DimmingOverlayView(RuntimeEnvironment.getApplication()).apply {
        layout(0, 0, 500, 500)
        setDimAmount(0.35f)
        setTextStyle(40f, 10f, 4f)
    }
    private val box = Rect(100, 200, 300, 230)
    private fun draw(view: DimmingOverlayView): OverlayRenderSnapshot? {
        val bitmap = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()
        return view.reconstructionSnapshot()
    }

    @Test fun harmlessObservationsMissingLineAndRecoveryDoNotRestartAnimationsOrChangeRasterState() {
        val view = view()
        view.setTextRegions(listOf(box), 500, 500, false, 0)
        val first = draw(view)!!.appearance
        repeat(20) { i ->
            val observation = Rect(100 + i % 3, 200, 300 - i % 3, 230)
            view.setTextRegions(listOf(observation), 500, 500, true, 100L + i * 100)
            assertEquals(first, draw(view)!!.appearance)
        }
        view.setTextRegions(emptyList(), 500, 500, true, 2200)
        assertEquals(first, draw(view)!!.appearance)
        view.setTextRegions(listOf(box), 500, 500, true, 2300)
        assertEquals(first, draw(view)!!.appearance)
        assertEquals(0, view.geometryTransitionStarts)
        assertEquals(0, view.revealAnimationStarts)
    }

    @Test fun meaningfulExpansionAndRemovalUseExistingGeometryFade() {
        val view = view()
        view.setTextRegions(listOf(box), 500, 500, false, 0)
        view.setTextRegions(listOf(Rect(100, 200, 370, 230)), 500, 500, true, 100)
        assertEquals(1, view.geometryTransitionStarts)
        assertNull(draw(view)) // Reconstruction still bypasses animated masks.
        view.setTextRegions(emptyList(), 500, 500, true, 600)
        assertEquals(1, view.geometryTransitionStarts)
        view.setTextRegions(emptyList(), 500, 500, true, 1100)
        assertEquals(2, view.geometryTransitionStarts)
    }

    @Test fun newLineUsesExistingRevealAndIdenticalObservationsDoNotRestartIt() {
        val view = view()
        view.setTextRegions(listOf(box), 500, 500, true, 0)
        assertEquals(1, view.revealAnimationStarts)
        view.setTextRegions(listOf(box), 500, 500, true, 100)
        assertEquals(1, view.revealAnimationStarts)
    }

    @Test fun staleCallbackDoesNotChangeGeometryOrDimensionsAndResetInvalidatesOldGeneration() {
        val view = view()
        view.setTextRegions(listOf(box), 500, 500, false, 100, 3)
        val first = draw(view)!!.appearance
        view.setTextRegions(listOf(Rect(20, 20, 80, 40)), 100, 100, true, 200, 2)
        assertEquals(first, draw(view)!!.appearance)
        view.clearTextRegions(4)
        view.setTextRegions(listOf(box), 500, 500, true, 300, 3)
        assertTrue(draw(view)!!.appearance.textRegions.isEmpty())
    }

    @Test fun resizeClearsTrackerAndVisibleMaskImmediately() {
        val view = view()
        view.setTextRegions(listOf(box), 500, 500, false, 0)
        view.layout(0, 0, 400, 400)
        assertTrue(draw(view)!!.appearance.textRegions.isEmpty())
    }

    @Test
    @Config(qualifiers = "xhdpi")
    fun densityConvertsDpStylesOnceWhileToleranceRemainsOverlayPixels() {
        val view = view()
        view.setTextRegions(listOf(box), 500, 500, false, 0)
        val state = draw(view)!!.appearance
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        assertEquals(2f, density)
        assertEquals(40 * density, state.marginPx)
        assertEquals(4 * density, state.featherPx)
        view.setTextStyle(0f, 0f, 0f)
        view.setJitterTolerance(5f)
        view.setTextRegions(listOf(box), 500, 500, false, 100)
        view.setTextRegions(listOf(Rect(100, 202, 300, 232)), 500, 500, true, 200)
        assertEquals(listOf(MaskRegion(100f, 200f, 300f, 230f)), draw(view)!!.appearance.textRegions)
    }

    @Test fun safeAnalyticalContainmentAgreesWithActualSharedBlurRaster() {
        val stable = MaskRegion(100f, 100f, 300f, 140f)
        var accepted = 0
        for (scale in listOf(0.5f, 1f, 2f)) for (feather in listOf(0f, 4f, 12f)) {
            val size = (500 * scale).toInt()
            val config = TextRegionTrackingConfig(500, 500, size, size,
                if (feather == 12f) 40f else 24f, 18f, feather)
            val appearance = OverlayAppearance(0.7f, 0f, 0.7f, listOf(stable), emptyList(),
                500, 500, 1f, 1f, config.marginPx, config.cornerRadiusPx, feather, size, size)
            val raster = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            OverlayAlphaRenderer().draw(Canvas(raster), appearance)
            for (dx in -20..20 step 5) for (dy in -10..10 step 5) {
                val observed = MaskRegion(stable.left + dx, stable.top + dy, stable.right + dx, stable.bottom + dy)
                if (!SafeAreaEvaluator.contains(stable, observed, config)) continue
                accepted++
                val left = floor(observed.left * scale - 2).toInt()
                val top = floor(observed.top * scale - 2).toInt()
                val right = ceil(observed.right * scale + 2).toInt()
                val bottom = ceil(observed.bottom * scale + 2).toInt()
                for (y in top..bottom) for (x in left..right) {
                    val remainingAlpha = raster.getPixel(x, y) ushr 24
                    assertTrue("Unsafe coverage at scale=$scale blur=$feather x=$x y=$y alpha=$remainingAlpha",
                        remainingAlpha <= 5) // Mask coverage >= 250/255 (>0.98).
                }
            }
            raster.recycle()
        }
        assertTrue("Must test substantial accepted interiors", accepted > 50)
    }

    @Test fun reconstructionStillFeedsOcrInputBeforeTrackedStableGeometry() {
        val view = view()
        val bitmap = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff646464.toInt())
        view.setTextRegions(listOf(box), 500, 500, false, 0)
        val snapshot = draw(view)!!
        OverlayFrameReconstructor().use { assertNotNull(it.reconstruct(bitmap, snapshot)) }
        assertTrue((bitmap.getPixel(0, 0) and 255) > 100)
        // Synthetic raw line observations stand in for ML Kit output; they are
        // deliberately delivered through the same view entry point as the service.
        view.setTextRegions(listOf(Rect(102, 201, 298, 231)), 500, 500, true, 100)
        assertEquals(snapshot.appearance.textRegions, draw(view)!!.appearance.textRegions)
        bitmap.recycle()
    }
}

package com.desu1243.darker

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun appearance(
    dim: Float = 0.35f, textDim: Float = 0f,
    regions: List<MaskRegion> = emptyList(), old: List<MaskRegion> = emptyList(),
    reveal: Float = 1f, transition: Float = 1f, feather: Float = 0f,
    radius: Float = 0f, width: Int = 64, height: Int = 64,
) = OverlayAppearance(dim, textDim, 0.7f, regions, old, width, height,
    reveal, transition, 0f, radius, feather, width, height)

class OverlayReconstructionTest {
    @Test fun zeroAlphaIsIdentity() {
        for (v in 0..255) assertEquals(v, OverlayInverseMath.channel(v, 0f))
        assertEquals(0x804080c0.toInt(), OverlayInverseMath.pixel(0x804080c0.toInt(), 0f))
    }

    @Test fun syntheticUniformAndPartialOverlaysRecoverWithinQuantizationBound() {
        for (alpha in listOf(0f, 0.1f, 0.35f, 0.7f, 0.94f)) {
            // Rounding the capture introduces <= 0.5/transmission error,
            // plus <= 0.5 when rounding the inverse to a channel integer.
            val tolerance = 0.5f / (1f - alpha) + 0.5f
            for (original in 0..255) {
                val captured = (original * (1f - alpha)).roundToInt()
                assertTrue(abs(original - OverlayInverseMath.channel(captured, alpha)) <= tolerance)
            }
        }
    }

    @Test fun independentChannelsAndAlphaPreserved() {
        assertEquals(0x80142850.toInt(), OverlayInverseMath.pixel(0x800a1428.toInt(), 0.5f))
    }

    @Test fun invalidAlphaAndIrrecoverableTransmissionAreIdentity() {
        for (alpha in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 1.1f, 1f, 0.99f)) {
            assertEquals(90, OverlayInverseMath.channel(90, alpha))
        }
    }

    @Test fun brightPixelsClampAndIntegerRoundingIsNearest() {
        assertEquals(255, OverlayInverseMath.channel(250, 0.7f))
        assertEquals(0, OverlayInverseMath.channel(0, 0.7f))
        assertEquals(3, OverlayInverseMath.channel(2, 0.35f))
    }

    private val snapshot = OverlayRenderSnapshot(appearance(), 7, 1_000_000_000L, 1_000_000_000L)
    private fun reason(
        s: OverlayRenderSnapshot? = snapshot, validated: Boolean = true,
        w: Int = 64, h: Int = 64, timestamp: Long = 1_200_000_000L,
        now: Long = 1_250_000_000L, generation: Int = 1, current: Int = 1,
    ) = OverlayFrameEligibility.bypassReason(validated, s, w, h, timestamp, now, generation, current)

    @Test fun stableMatchingFrameAccepted() { assertNull(reason()) }
    @Test fun overlayPresenceRequiresExplicitValidation() { assertEquals("unvalidated-session", reason(validated = false)) }
    @Test fun noSnapshotFallsBack() { assertEquals("no-stable-snapshot", reason(s = null)) }
    @Test fun captureGenerationChangeFallsBack() { assertEquals("capture-generation", reason(current = 2)) }
    @Test fun mismatchedDimensionsFallBack() {
        assertEquals("dimensions", reason(w = 32))
        assertEquals("dimensions", reason(h = 63))
        assertEquals("dimensions", reason(w = 0))
    }
    @Test fun staleFutureAndUnknownTimestampFallBack() {
        for (t in listOf(0L, 1_300_000_000L)) assertEquals("timestamp-or-stale", reason(timestamp = t))
        assertEquals("timestamp-or-stale", reason(now = 4_000_000_000L))
    }
    @Test fun captureBeforeDrawOrDuringSettleFallsBack() {
        for (t in listOf(900_000_000L, 1_050_000_000L)) assertEquals("unsettled-state", reason(timestamp = t))
    }
    @Test fun oldStableStateIsValidForFreshFrame() {
        assertNull(reason(timestamp = 10_000_000_000L, now = 10_010_000_000L))
    }
    @Test fun snapshotDefensivelyCopiesGeometry() {
        val source = mutableListOf(MaskRegion(1f, 2f, 3f, 4f))
        val a = appearance(regions = source)
        source.clear()
        assertEquals(1, a.textRegions.size)
        try {
            (a.textRegions as MutableList).clear()
            fail("Mutable snapshot")
        } catch (_: UnsupportedOperationException) { }
    }
}

package com.desu1243.darker

import org.junit.Assert.assertEquals
import org.junit.Test

class MaskJitterFilterTest {
    private val anchor = MaskRegion(10f, 20f, 110f, 40f)

    private fun moved(dy: Float) = anchor.copy(top = anchor.top + dy, bottom = anchor.bottom + dy)

    @Test fun jitterWithinFivePixelsKeepsDisplayedAnchor() {
        val jitter = MaskRegion(14f, 15f, 108f, 45f)
        assertEquals(listOf(anchor), MaskJitterFilter.stabilize(listOf(anchor), listOf(jitter), 5f, 1f))
    }

    @Test fun slowScrollAccumulatesAgainstDisplayedPosition() {
        var displayed = listOf(anchor)
        for (offset in listOf(2f, 4f, 5f)) {
            displayed = MaskJitterFilter.stabilize(displayed, listOf(moved(offset)), 5f, 1f)
            assertEquals(listOf(anchor), displayed)
        }
        assertEquals(listOf(moved(6f)), MaskJitterFilter.stabilize(displayed, listOf(moved(6f)), 5f, 1f))
    }

    @Test fun ocrOrderDoesNotChangeMask() {
        val second = moved(40f)
        assertEquals(listOf(anchor, second), MaskJitterFilter.stabilize(
            listOf(anchor, second), listOf(second, moved(3f)), 5f, 1f,
        ))
    }

    @Test fun zeroToleranceAcceptsOnePixelMovement() {
        assertEquals(listOf(moved(1f)), MaskJitterFilter.stabilize(listOf(anchor), listOf(moved(1f)), 0f, 1f))
    }

    @Test fun toleranceUsesScreenPixelsWhenCaptureIsScaled() {
        assertEquals(listOf(anchor), MaskJitterFilter.stabilize(listOf(anchor), listOf(moved(2f)), 5f, 2f))
        assertEquals(listOf(moved(3f)), MaskJitterFilter.stabilize(listOf(anchor), listOf(moved(3f)), 5f, 2f))
    }

    @Test fun movingBoxDoesNotDisturbStableNeighbor() {
        val second = moved(40f)
        val movedSecond = moved(48f)
        assertEquals(listOf(anchor, movedSecond), MaskJitterFilter.stabilize(
            listOf(anchor, second), listOf(moved(3f), movedSecond), 5f, 1f,
        ))
    }

    @Test fun addedAndRemovedBoxesAreNotSuppressed() {
        val second = moved(40f)
        assertEquals(listOf(anchor, second), MaskJitterFilter.stabilize(listOf(anchor), listOf(anchor, second), 5f, 1f))
        assertEquals(listOf(second), MaskJitterFilter.stabilize(listOf(anchor, second), listOf(second), 5f, 1f))
        assertEquals(emptyList<MaskRegion>(), MaskJitterFilter.stabilize(listOf(anchor), emptyList(), 5f, 1f))
    }

    @Test fun eachAnchorCanBeMatchedOnlyOnce() {
        assertEquals(listOf(anchor, moved(2f)), MaskJitterFilter.stabilize(
            listOf(anchor), listOf(moved(1f), moved(2f)), 5f, 1f,
        ))
    }
}

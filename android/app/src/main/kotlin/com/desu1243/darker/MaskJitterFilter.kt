package com.desu1243.darker

import kotlin.math.abs
import kotlin.math.max

internal data class MaskRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal object MaskJitterFilter {
    fun stabilize(
        displayed: List<MaskRegion>,
        detected: List<MaskRegion>,
        tolerancePx: Float,
        sourceToScreenScale: Float,
    ): List<MaskRegion> {
        val tolerance = tolerancePx.coerceAtLeast(0f) /
            sourceToScreenScale.coerceAtLeast(0.001f)
        val used = BooleanArray(displayed.size)
        return detected.map { region ->
            val closest = displayed.indices
                .filter { !used[it] }
                .minByOrNull { edgeDistance(displayed[it], region) }
            if (closest != null && edgeDistance(displayed[closest], region) <= tolerance) {
                used[closest] = true
                // Keep the displayed anchor, not the last detection. Small scroll
                // steps therefore accumulate until they exceed the tolerance.
                displayed[closest]
            } else {
                region
            }
        }.sortedWith(compareBy({ it.top }, { it.left }, { it.bottom }, { it.right }))
    }

    private fun edgeDistance(a: MaskRegion, b: MaskRegion): Float = max(
        max(abs(a.left - b.left), abs(a.top - b.top)),
        max(abs(a.right - b.right), abs(a.bottom - b.bottom)),
    )
}

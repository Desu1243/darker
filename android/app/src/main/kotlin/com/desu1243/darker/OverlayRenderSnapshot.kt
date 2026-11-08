package com.desu1243.darker

import java.util.Collections

/** Value geometry, never Android's mutable RectF or animator objects. */
@ConsistentCopyVisibility
internal data class OverlayAppearance private constructor(
    val dimAmount: Float,
    val textDimAmount: Float,
    val windowAlpha: Float,
    val textRegions: List<MaskRegion>,
    val previousTextRegions: List<MaskRegion>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val revealProgress: Float,
    val transitionProgress: Float,
    val marginPx: Float,
    val cornerRadiusPx: Float,
    val featherPx: Float,
    val width: Int,
    val height: Int,
) {
    companion object {
        operator fun invoke(
            dimAmount: Float, textDimAmount: Float, windowAlpha: Float,
            textRegions: List<MaskRegion>, previousTextRegions: List<MaskRegion>,
            sourceWidth: Int, sourceHeight: Int, revealProgress: Float,
            transitionProgress: Float, marginPx: Float, cornerRadiusPx: Float,
            featherPx: Float, width: Int, height: Int,
        ) = OverlayAppearance(
            dimAmount, textDimAmount, windowAlpha,
            Collections.unmodifiableList(ArrayList(textRegions)),
            Collections.unmodifiableList(ArrayList(previousTextRegions)),
            sourceWidth, sourceHeight, revealProgress, transitionProgress,
            marginPx, cornerRadiusPx, featherPx, width, height,
        )
    }
}

internal data class OverlayRenderSnapshot(
    val appearance: OverlayAppearance,
    val version: Long,
    val drawnAtNanos: Long,
    val stableSinceNanos: Long,
)

/** Only stable states are accepted: no inference of presentation from onDraw. */
internal object OverlayFrameEligibility {
    const val SETTLE_NANOS = 100_000_000L
    const val MAX_FRAME_AGE_NANOS = 2_000_000_000L

    fun bypassReason(
        validatedSession: Boolean, snapshot: OverlayRenderSnapshot?,
        width: Int, height: Int, timestampNanos: Long, acquiredNanos: Long,
        frameGeneration: Int, currentGeneration: Int,
    ): String? {
        if (!validatedSession) return "unvalidated-session"
        if (frameGeneration != currentGeneration) return "capture-generation"
        val s = snapshot ?: return "no-stable-snapshot"
        val a = s.appearance
        // Existing display mapping has no verified offsets. Support only identity
        // alignment; do not guess where navigation bars or app letterboxing are.
        if (width <= 0 || height <= 0 || a.width != width || a.height != height ||
            (a.textRegions.isNotEmpty() &&
                (a.sourceWidth != width || a.sourceHeight != height))) return "dimensions"
        if (timestampNanos <= 0 || timestampNanos > acquiredNanos ||
            acquiredNanos - timestampNanos > MAX_FRAME_AGE_NANOS) return "timestamp-or-stale"
        if (timestampNanos < s.stableSinceNanos ||
            timestampNanos - s.stableSinceNanos < SETTLE_NANOS) return "unsettled-state"
        if (!a.windowAlpha.isFinite() || a.windowAlpha !in 0f..1f) return "invalid-alpha"
        return null
    }
}

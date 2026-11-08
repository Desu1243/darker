package com.desu1243.darker

import org.junit.Assert.*
import org.junit.Test

class PersistentTextRegionTrackerTest {
    private val box = MaskRegion(100f, 200f, 300f, 230f)
    private val config = TextRegionTrackingConfig(1000, 2000, 1000, 2000, 40f, 10f, 4f)
    private fun shift(b: MaskRegion = box, x: Float = 0f, y: Float = 0f) =
        MaskRegion(b.left + x, b.top + y, b.right + x, b.bottom + y)
    private class Sequence(val config: TextRegionTrackingConfig) {
        val tracker = PersistentTextRegionTracker()
        var now = 0L
        fun update(vararg boxes: MaskRegion, elapsed: Long = 100): TextRegionUpdate {
            now += elapsed
            return tracker.update(boxes.toList(), now, config)
        }
        fun only() = tracker.trackedRegions.single()
    }

    @Test fun identicalObservationsReuseRegionGeometryAndOutputCollection() {
        val s = Sequence(config)
        val first = s.update(box).renderRegions
        val region = s.only()
        val stable = region.stableBounds
        repeat(100) {
            val result = s.update(box)
            assertSame(first, result.renderRegions)
            assertSame(region, s.only())
            assertSame(stable, s.only().stableBounds)
            assertEquals(0, result.metrics.geometryUpdates)
            assertEquals(1, result.metrics.safeHits)
        }
    }

    @Test fun randomAndAlternatingEdgeJitterNeverChangesSafeOutput() {
        val s = Sequence(config)
        val initial = s.update(box).renderRegions
        val offsets = listOf(3f, -1f, 2f, 0f, 4f, -2f, 1f)
        repeat(50) {
            val d = offsets[it % offsets.size]
            val observed = MaskRegion(box.left + d, box.top - d / 2, box.right - d, box.bottom + d / 2)
            assertSame(initial, s.update(observed).renderRegions)
            assertEquals(observed, s.only().lastObservedBounds)
        }
    }

    @Test fun arbitraryOcrOrderPreservesIdsAndOutput() {
        val a = box
        val b = shift(y = 45f)
        val c = shift(y = 90f)
        val s = Sequence(config)
        val first = s.update(c, a, b).renderRegions
        val ids = s.tracker.trackedRegions.associate { it.lastObservedBounds to it.id }
        for (order in listOf(listOf(b, c, a), listOf(a, c, b), listOf(c, b, a))) {
            val result = s.tracker.update(order, s.now + 100, config)
            s.now += 100
            assertSame(first, result.renderRegions)
            assertEquals(ids, s.tracker.trackedRegions.associate { it.lastObservedBounds to it.id })
        }
    }

    @Test fun movingNeighborDoesNotDisturbStationaryRegion() {
        val neighbor = shift(y = 70f)
        val s = Sequence(config)
        s.update(box, neighbor)
        val first = s.tracker.trackedRegions.first()
        val neighborId = s.tracker.trackedRegions.last().id
        s.update(shift(x = 2f), shift(neighbor, y = 12f))
        assertEquals(box, first.stableBounds)
        val moving = s.tracker.trackedRegions.single { it.id == neighborId }
        assertEquals(shift(neighbor, y = 12f), moving.stableBounds)
    }

    @Test fun slowCumulativeMovementEventuallyFollowsObservationWithoutGrowingTrail() {
        val s = Sequence(config)
        s.update(box)
        val id = s.only().id
        for (offset in 1..20) {
            s.update(shift(y = offset.toFloat()))
            assertEquals(id, s.only().id)
        }
        assertEquals(shift(y = 20f), s.only().stableBounds)
        assertEquals(30f, s.only().stableBounds.bottom - s.only().stableBounds.top)
    }

    @Test fun safeAreaIncludesWholeRectangleAndPadding() {
        assertTrue(SafeAreaEvaluator.contains(box, shift(x = 10f), config))
        assertFalse(SafeAreaEvaluator.contains(box, box.copy(right = 340f), config))
        assertFalse(SafeAreaEvaluator.contains(box, box.copy(top = 150f), config))
    }

    @Test fun roundedCornersRejectBoundingRectOnlyContainment() {
        val c = config.copy(marginPx = 4f, cornerRadiusPx = 18f, featherPx = 0f)
        assertFalse(SafeAreaEvaluator.contains(box, box, c))
        assertTrue(SafeAreaEvaluator.contains(box, box.copy(left = 115f, right = 285f), c))
    }

    @Test fun blurRemovesFreedomNearExpandedEdges() {
        val observed = box.copy(right = box.right + 15f)
        assertTrue(SafeAreaEvaluator.contains(box, observed, config.copy(marginPx = 20f, featherPx = 0f)))
        assertFalse(SafeAreaEvaluator.contains(box, observed, config.copy(marginPx = 20f, featherPx = 12f)))
    }

    @Test fun impossibleInteriorFallsBackToPixelToleranceWithoutEnlargingInitialMask() {
        for (c in listOf(config.copy(marginPx = 0f), config.copy(marginPx = 12f, featherPx = 12f))) {
            val s = Sequence(c)
            s.update(box)
            assertEquals(box, s.only().stableBounds)
            assertEquals(box, s.update(shift(y = 4f)).renderRegions.single())
            s.update(shift(y = 8f))
            assertTrue(s.only().stableBounds.bottom >= 238f)
        }
    }

    @Test fun sharpEdgesAndScaledCaptureHaveExplicitUnits() {
        val c = config.copy(marginPx = 12f, featherPx = 0f, overlayWidth = 2000, overlayHeight = 4000)
        assertTrue(SafeAreaEvaluator.contains(box, shift(x = 3f), c))
        assertFalse(SafeAreaEvaluator.contains(box, shift(x = 6f), c))
        val s = Sequence(c.copy(marginPx = 0f))
        s.update(box)
        assertEquals(box, s.update(shift(y = 2f)).renderRegions.single())
        assertNotEquals(box, s.update(shift(y = 3f)).renderRegions.single())
    }

    @Test fun expansionChangesOnlyInsufficientEdgeAndRespondsInOneObservation() {
        val s = Sequence(config.copy(marginPx = 12f, featherPx = 4f))
        s.update(box)
        val result = s.update(box.copy(right = 320f))
        val stable = s.only().stableBounds
        assertEquals(box.left, stable.left)
        assertEquals(box.top, stable.top)
        assertEquals(box.bottom, stable.bottom)
        assertTrue(stable.right >= 320f)
        assertEquals(1, result.metrics.expansions)
    }

    @Test fun singleContractionIsHeldAndSustainedContractionEventuallyAccepted() {
        val s = Sequence(config)
        s.update(box)
        val smaller = box.copy(right = 240f)
        assertEquals(box, s.update(smaller).renderRegions.single())
        s.update(smaller, elapsed = 300)
        assertEquals(box, s.update(smaller, elapsed = 300).renderRegions.single())
        val result = s.update(smaller, elapsed = 200)
        assertEquals(smaller, result.renderRegions.single())
        assertEquals(1, result.metrics.contractions)
    }

    @Test fun shrinkRequiresBothEvidenceCountAndElapsedTimeAtAllRefreshIntervals() {
        for (interval in listOf(50L, 400L, 500L)) {
            val s = Sequence(config.copy(refreshIntervalMs = interval))
            s.update(box)
            val smaller = box.copy(right = 240f)
            repeat(2) { assertEquals(box, s.update(smaller, elapsed = 10).renderRegions.single()) }
            val result = s.update(smaller, elapsed = maxOf(600, 2 * interval))
            assertEquals(smaller, result.renderRegions.single())
        }
    }

    @Test fun inconsistentContractionsNeverAccumulateEvidence() {
        val s = Sequence(config)
        val initial = s.update(box).renderRegions
        repeat(20) { i ->
            val right = if (i % 2 == 0) 240f else 260f
            assertSame(initial, s.update(box.copy(right = right), elapsed = 200).renderRegions)
        }
    }

    @Test fun smallRepeatedErrorsDoNotAccumulateMarginOrDrift() {
        val s = Sequence(config)
        val initial = s.update(box).renderRegions
        repeat(200) { i ->
            val offset = listOf(0f, 3f, -1f, 2f, 0f, 4f)[i % 6]
            assertSame(initial, s.update(shift(x = offset)).renderRegions)
        }
    }

    @Test fun oneMissingLineSurvivesAndReturnsWithSameIdentityAndGeometry() {
        val neighbor = shift(y = 70f)
        val s = Sequence(config)
        val initial = s.update(box, neighbor).renderRegions
        val id = s.tracker.trackedRegions.first().id
        val missing = s.update(neighbor)
        assertSame(initial, missing.renderRegions)
        assertEquals(1, missing.metrics.missing)
        assertEquals(RegionState.MISSING, s.tracker.trackedRegions.first().state)
        assertSame(initial, s.update(box, neighbor).renderRegions)
        assertEquals(id, s.tracker.trackedRegions.first().id)
        assertEquals(RegionState.ACTIVE, s.tracker.trackedRegions.first().state)
    }

    @Test fun permanentlyMissingLineIsRetiredAfterObservationsAndTimeout() {
        val neighbor = shift(y = 70f)
        val s = Sequence(config)
        s.update(box, neighbor)
        assertEquals(2, s.update(neighbor, elapsed = 700).renderRegions.size)
        assertEquals(listOf(neighbor), s.update(neighbor, elapsed = 100).renderRegions)
    }

    @Test fun oneEmptyResultIsDebouncedEvenAfterLongGap() {
        val s = Sequence(config)
        val initial = s.update(box).renderRegions
        assertSame(initial, s.update(elapsed = 5000).renderRegions)
        assertTrue(s.update(elapsed = 100).renderRegions.isEmpty())
    }

    @Test fun emptyPageEventuallyClearsAtFastRefreshWithoutAClockTimer() {
        val s = Sequence(config.copy(refreshIntervalMs = 50))
        s.update(box)
        assertEquals(1, s.update(elapsed = 50).renderRegions.size)
        assertEquals(1, s.update(elapsed = 50).renderRegions.size)
        s.update(elapsed = 50)
        assertTrue(s.update(elapsed = 50).renderRegions.isEmpty())
    }

    @Test fun newUnrelatedPageImmediatelyRetiresOldIds() {
        val s = Sequence(config)
        s.update(box, shift(y = 70f))
        val oldIds = s.tracker.trackedRegions.map { it.id }.toSet()
        val newPage = shift(x = 500f, y = 400f)
        val result = s.update(newPage)
        assertEquals(listOf(newPage), result.renderRegions)
        assertEquals(2, result.metrics.removed)
        assertTrue(s.tracker.trackedRegions.none { it.id in oldIds })
    }

    @Test fun coherentScrollRetiresUnmatchedLinesWithoutGhosts() {
        val nearTop = MaskRegion(100f, 0f, 300f, 30f)
        val a = shift(y = 0f)
        val b = shift(y = 70f)
        val s = Sequence(config)
        s.update(nearTop, a, b)
        val result = s.update(shift(a, y = -12f), shift(b, y = -12f))
        assertEquals(2, result.renderRegions.size)
        assertEquals(2, result.metrics.moving)
        assertEquals(1, result.metrics.removed)
    }

    @Test fun fastScrollReinitializesUnmatchedGeometryPromptly() {
        val s = Sequence(config)
        s.update(box, shift(y = 70f))
        val oldIds = s.tracker.trackedRegions.map { it.id }.toSet()
        val fresh = listOf(shift(y = 500f), shift(y = 570f))
        assertEquals(fresh, s.update(*fresh.toTypedArray()).renderRegions)
        assertTrue(s.tracker.trackedRegions.none { it.id in oldIds })
    }

    @Test fun nearbySimilarLinesKeepOneToOneIdentityDuringMotionAndOrderChanges() {
        val s = Sequence(config)
        val a = box
        val b = shift(y = 35f)
        s.update(a, b)
        val ids = s.tracker.trackedRegions.map { it.id }
        repeat(8) { step ->
            val moved = (step + 1) * 3f
            s.update(shift(b, y = moved), shift(a, y = moved))
            assertEquals(ids, s.tracker.trackedRegions.map { it.id })
            assertEquals(shift(a, y = moved), s.tracker.trackedRegions[0].lastObservedBounds)
            assertEquals(shift(b, y = moved), s.tracker.trackedRegions[1].lastObservedBounds)
        }
    }

    @Test fun newLineBetweenExistingLinesGetsNewIdentityWithoutSwappingNeighbors() {
        val s = Sequence(config)
        val b = shift(y = 100f)
        s.update(box, b)
        val ids = s.tracker.trackedRegions.associate { it.lastObservedBounds to it.id }
        val inserted = shift(y = 50f)
        s.update(b, inserted, box)
        assertEquals(3, s.tracker.trackedRegions.size)
        ids.forEach { (bounds, id) -> assertEquals(id, s.tracker.trackedRegions.single { it.lastObservedBounds == bounds }.id) }
    }

    @Test fun oneFrameSplitPreservesOriginalRegionWithoutDuplicateMasks() {
        val s = Sequence(config)
        val initial = s.update(box).renderRegions
        val id = s.only().id
        val left = box.copy(right = 190f)
        val right = box.copy(left = 200f)
        assertSame(initial, s.update(right, left).renderRegions)
        assertEquals(1, s.tracker.trackedRegions.size)
        assertSame(initial, s.update(box).renderRegions)
        assertEquals(id, s.only().id)
    }

    @Test fun sustainedSplitChangesTopologyOnlyAfterShortEvidenceWindow() {
        val s = Sequence(config)
        s.update(box)
        val oldId = s.only().id
        val a = box.copy(right = 190f)
        val b = box.copy(left = 200f)
        s.update(a, b)
        val result = s.update(a, b, elapsed = 400)
        assertEquals(listOf(a, b), result.renderRegions)
        assertTrue(s.tracker.trackedRegions.none { it.id == oldId })
    }

    @Test fun temporaryMergePreservesBothOriginalRegionsAndIds() {
        val s = Sequence(config)
        val b = shift(y = 35f)
        val initial = s.update(box, b).renderRegions
        val ids = s.tracker.trackedRegions.map { it.id }
        val merged = TextRegionGeometry.union(box, b)
        assertSame(initial, s.update(merged).renderRegions)
        assertSame(initial, s.update(box, b).renderRegions)
        assertEquals(ids, s.tracker.trackedRegions.map { it.id })
    }

    @Test fun sustainedMergeReplacesOldRegionsWithoutOverlappingDuplicate() {
        val s = Sequence(config)
        val b = shift(y = 35f)
        s.update(box, b)
        val merged = TextRegionGeometry.union(box, b)
        s.update(merged)
        assertEquals(listOf(merged), s.update(merged, elapsed = 400).renderRegions)
        assertEquals(1, s.tracker.trackedRegions.size)
    }

    @Test fun resetClearsImmediatelyAndDoesNotRecycleIds() {
        val s = Sequence(config)
        s.update(box)
        val id = s.only().id
        s.tracker.reset(2)
        assertTrue(s.tracker.trackedRegions.isEmpty())
        s.tracker.update(listOf(box), s.now + 100, config, 2)
        assertTrue(s.only().id > id)
    }

    @Test fun staleGenerationAndOutOfOrderCallbacksCannotChangeState() {
        val tracker = PersistentTextRegionTracker()
        val first = tracker.update(listOf(box), 100, config, 3).renderRegions
        assertSame(first, tracker.update(listOf(shift(x = 500f)), 200, config, 2).renderRegions)
        assertSame(first, tracker.update(emptyList(), 50, config, 3).renderRegions)
        assertEquals(box, tracker.trackedRegions.single().lastObservedBounds)
    }

    @Test fun dimensionScaleAndStyleChangesResetAnchorsButLiveTimingAndToleranceDoNot() {
        for (newConfig in listOf(config.copy(captureWidth = 900), config.copy(overlayWidth = 2000),
            config.copy(overlayHeight = 1900), config.copy(marginPx = 12f))) {
            val tracker = PersistentTextRegionTracker()
            tracker.update(listOf(box), 0, config)
            val id = tracker.trackedRegions.single().id
            tracker.update(listOf(box), 100, newConfig)
            assertNotEquals(id, tracker.trackedRegions.single().id)
        }
        val tracker = PersistentTextRegionTracker()
        tracker.update(listOf(box), 0, config)
        val id = tracker.trackedRegions.single().id
        tracker.update(listOf(box), 100, config.copy(refreshIntervalMs = 50, jitterTolerancePx = 0f))
        assertEquals(id, tracker.trackedRegions.single().id)
    }

    @Test fun zeroToleranceFallbackRespondsToOnePixelMotionAndLiveToleranceIsFunctional() {
        val tracker = PersistentTextRegionTracker()
        val c = config.copy(marginPx = 0f, jitterTolerancePx = 5f)
        tracker.update(listOf(box), 0, c)
        assertEquals(listOf(box), tracker.update(listOf(shift(y = 1f)), 100, c).renderRegions)
        assertNotEquals(listOf(box), tracker.update(listOf(shift(y = 2f)), 200, c.copy(jitterTolerancePx = 0f)).renderRegions)
    }

    @Test fun invalidDuplicateAndOffscreenDetectionsNeverCreateInvalidRegions() {
        val tracker = PersistentTextRegionTracker()
        val result = tracker.update(listOf(box, box, box.copy(left = Float.NaN),
            box.copy(right = box.left), shift(x = -1000f)), 0, config)
        assertEquals(listOf(box), result.renderRegions)
    }

    @Test fun activeRegionCountAndHistoryRemainBounded() {
        val tracker = PersistentTextRegionTracker()
        val detections = (0..600).map { i -> MaskRegion(10f, i * 3f, 50f, i * 3f + 2) }
        tracker.update(detections, 0, config)
        assertEquals(PersistentTextRegionTracker.MAX_REGIONS, tracker.trackedRegions.size)
        tracker.update(emptyList(), 500, config)
        tracker.update(emptyList(), 1000, config)
        assertTrue(tracker.trackedRegions.isEmpty())
    }

    @Test fun globalAssignmentAvoidsGreedyStealingFromConstrainedNeighbor() {
        val a = box
        val b = shift(y = 20f)
        val shared = shift(y = 8f)
        val constrained = shift(y = -20f)
        val assignment = TextRegionMatcher.match(listOf(a, b), listOf(constrained, shared), config)
        assertArrayEquals(intArrayOf(0, 1), assignment)
        val reverse = TextRegionMatcher.match(listOf(a, b), listOf(shared, constrained), config)
        assertArrayEquals(intArrayOf(1, 0), reverse)
    }

    @Test fun changingAmbiguousSplitsCannotPreserveOldMaskIndefinitely() {
        val s = Sequence(config)
        s.update(box)
        val oldId = s.only().id
        repeat(10) { i ->
            val split = if (i % 2 == 0) 170f else 220f
            s.update(box.copy(right = split), box.copy(left = split + 5), elapsed = 100)
        }
        assertTrue(s.tracker.trackedRegions.none { it.id == oldId })
    }

    @Test fun singleMovingLineLeavingScreenRetiresOnFirstMissingObservation() {
        val s = Sequence(config.copy(refreshIntervalMs = 50))
        val nearTop = MaskRegion(100f, 25f, 300f, 55f)
        s.update(nearTop)
        s.update(shift(nearTop, y = -12f))
        s.update(shift(nearTop, y = -24f))
        assertTrue(s.update().renderRegions.isEmpty())
    }

    @Test fun establishedMovementStopsImmediatelyWhenObservationsStopMoving() {
        val s = Sequence(config)
        s.update(box)
        s.update(shift(y = 12f))
        val stopped = shift(y = 24f)
        s.update(stopped)
        val output = s.update(stopped).renderRegions
        repeat(20) { i ->
            val jitter = if (i % 2 == 0) 2f else -1f
            assertSame(output, s.update(shift(stopped, x = jitter)).renderRegions)
            assertEquals(0, s.update(stopped).metrics.moving)
        }
    }

    @Test fun coherentMovingMajorityDoesNotMoveStationaryMinority() {
        val s = Sequence(config)
        val lines = (0..3).map { shift(y = it * 70f) }
        s.update(*lines.toTypedArray())
        val stationary = s.tracker.trackedRegions.last()
        val next = lines.take(3).map { shift(it, y = 12f) } + shift(lines.last(), x = 2f)
        val result = s.update(*next.toTypedArray())
        assertEquals(3, result.metrics.moving)
        assertEquals(lines.last(), stationary.stableBounds)
    }

    @Test fun newerGenerationResetsObservationClockBeforeCheckingTimestampOrder() {
        val tracker = PersistentTextRegionTracker()
        tracker.update(listOf(box), 1000, config, 1)
        val oldId = tracker.trackedRegions.single().id
        assertTrue(tracker.update(listOf(box), 0, config, 2).accepted)
        assertNotEquals(oldId, tracker.trackedRegions.single().id)
    }

    @Test fun splitDuringCoherentScrollDoesNotLeaveOldGroupedMaskBehind() {
        val s = Sequence(config)
        val a = shift(y = 70f)
        val b = shift(y = 140f)
        s.update(box, a, b)
        val oldId = s.tracker.trackedRegions.first().id
        val splitA = shift(box.copy(right = 190f), y = 12f)
        val splitB = shift(box.copy(left = 200f), y = 12f)
        val result = s.update(splitA, splitB, shift(a, y = 12f), shift(b, y = 12f))
        assertTrue(s.tracker.trackedRegions.none { it.id == oldId })
        assertTrue(result.renderRegions.containsAll(listOf(splitA, splitB)))
        assertFalse(result.renderRegions.contains(box))
    }

    @Test fun stationaryAmbiguousNeighborIsNotForcedToFollowCoherentScroll() {
        val s = Sequence(config)
        val a = shift(y = 70f)
        val b = shift(y = 140f)
        s.update(box, a, b)
        val oldId = s.tracker.trackedRegions.first().id
        val result = s.update(box.copy(right = 190f), box.copy(left = 200f),
            shift(a, y = 12f), shift(b, y = 12f))
        assertTrue(s.tracker.trackedRegions.any { it.id == oldId })
        assertTrue(result.renderRegions.contains(box))
        assertEquals(3, result.renderRegions.size)
    }

    @Test fun oneAmbiguousGroupDoesNotProtectUnrelatedOldPageRegions() {
        val s = Sequence(config)
        val oldPage = (0..4).map { shift(y = it * 70f) }
        s.update(*oldPage.toTypedArray())
        val fresh = shift(x = 500f, y = 800f)
        val result = s.update(box.copy(right = 190f), box.copy(left = 200f), fresh)
        assertEquals(listOf(box, fresh), result.renderRegions)
        assertEquals(4, result.metrics.removed)
    }
}

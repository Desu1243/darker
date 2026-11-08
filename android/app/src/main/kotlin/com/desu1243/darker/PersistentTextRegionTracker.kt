package com.desu1243.darker

import java.util.Collections
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import com.desu1243.darker.TextRegionGeometry.cx
import com.desu1243.darker.TextRegionGeometry.cy
import com.desu1243.darker.TextRegionGeometry.height
import com.desu1243.darker.TextRegionGeometry.width

internal enum class RegionState { NEW, ACTIVE, MISSING, REMOVING }

/** Persistent objects; observation metadata never participates in render equality. */
internal class TrackedTextRegion(val id: Long, bounds: MaskRegion, now: Long) {
    var stableBounds = bounds
        internal set
    var lastObservedBounds = bounds
        internal set
    var lastSeenAtMs = now
        internal set
    var consecutiveMatches = 1
        internal set
    var consecutiveMisses = 0
        internal set
    var state = RegionState.NEW
        internal set
    internal var dx = 0f
    internal var dy = 0f
    internal var movementStreak = 0
    internal var groupingSinceMs = -1L
    internal var wasMoving = false
    internal val shrinkCandidate = FloatArray(4) { Float.NaN }
    internal val shrinkSince = LongArray(4)
    internal val shrinkCount = IntArray(4)
}

internal data class TrackingMetrics(
    var active: Int = 0, var added: Int = 0, var matched: Int = 0, var missing: Int = 0,
    var removed: Int = 0, var safeHits: Int = 0, var geometryUpdates: Int = 0,
    var expansions: Int = 0, var contractions: Int = 0, var moving: Int = 0,
    var grouped: Int = 0, var suppressed: Int = 0, var dropped: Int = 0,
    var elapsedNanos: Long = 0, var geometryUpdateRate: Double = 0.0,
    var maxMissingAgeMs: Long = 0, var maxObservedOffsetPx: Float = 0f,
)

internal data class TextRegionUpdate(val renderRegions: List<MaskRegion>, val metrics: TrackingMetrics, val accepted: Boolean = true)

/** Confined to the UI/OCR callback thread. No Android, pixels, text strings or wall clock. */
internal class PersistentTextRegionTracker {
    companion object { const val MAX_REGIONS = 512 }
    private val tracks = linkedMapOf<Long, TrackedTextRegion>()
    val trackedRegions: List<TrackedTextRegion> get() = tracks.values.toList()
    private var nextId = 1L
    private var generation = 0L
    private var lastAtMs = -1L
    private var previousConfig: TextRegionTrackingConfig? = null
    private var rendered: List<MaskRegion> = emptyList()
    private var updates = 0L
    private var changedUpdates = 0L
    private data class GroupEvidence(var since: Long, var count: Int, var union: MaskRegion)
    private val groups = mutableMapOf<String, GroupEvidence>()
    private data class PendingGrouping(val tracks: List<TrackedTextRegion>, val detections: List<Int>)

    fun reset(newGeneration: Long? = null) {
        tracks.clear()
        groups.clear()
        rendered = emptyList()
        lastAtMs = -1
        previousConfig = null
        updates = 0
        changedUpdates = 0
        if (newGeneration != null) generation = max(generation, newGeneration)
        // IDs are never recycled, including across mapping/capture resets.
    }

    fun update(
        raw: List<MaskRegion>, nowMs: Long, config: TextRegionTrackingConfig, captureGeneration: Long = 0,
    ): TextRegionUpdate {
        val started = System.nanoTime()
        val metrics = TrackingMetrics()
        if (captureGeneration < generation || (captureGeneration == generation && nowMs < lastAtMs)) return TextRegionUpdate(rendered, metrics, accepted = false)
        if (captureGeneration > generation || previousConfig?.sameMapping(config) == false) reset(captureGeneration)
        generation = captureGeneration
        previousConfig = config
        lastAtMs = nowMs
        val detections = raw.asSequence().filter {
            it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite()
        }.map {
            MaskRegion(it.left.coerceIn(0f, config.captureWidth.coerceAtLeast(0).toFloat()),
                it.top.coerceIn(0f, config.captureHeight.coerceAtLeast(0).toFloat()),
                it.right.coerceIn(0f, config.captureWidth.coerceAtLeast(0).toFloat()),
                it.bottom.coerceIn(0f, config.captureHeight.coerceAtLeast(0).toFloat()))
        }.filter { width(it) > 0 && height(it) > 0 }.distinct().sortedWith(TextRegionGeometry.order)
            .take(MAX_REGIONS).toList()
        val before = rendered
        val old = tracks.values.toList()
        val protected = mutableSetOf<Long>()
        val consumed = mutableSetOf<Int>()
        val pendingGroups = resolveGrouping(old, detections, nowMs, config, protected, consumed, metrics)
        val available = old.filter { it.id !in protected && tracks.containsKey(it.id) }
        val detectionIndices = detections.indices.filter { it !in consumed }
        val remaining = detectionIndices.map { detections[it] }
        val assignment = TextRegionMatcher.match(available.map { it.lastObservedBounds }, remaining, config)
        val matches = available.indices.filter { assignment[it] >= 0 }
        val coherent = coherentMotion(matches.map { available[it] to remaining[assignment[it]] }, config)
        if (coherent.isNotEmpty()) {
            val movingMatches = matches.filter { available[it].id in coherent }
            val dx = movingMatches.map { cx(remaining[assignment[it]]) - cx(available[it].lastObservedBounds) }
                .sorted()[movingMatches.size / 2]
            val dy = movingMatches.map { cy(remaining[assignment[it]]) - cy(available[it].lastObservedBounds) }
                .sorted()[movingMatches.size / 2]
            for (group in pendingGroups) {
                val previous = group.tracks.map { it.lastObservedBounds }.reduce(TextRegionGeometry::union)
                val current = group.detections.map { detections[it] }.reduce(TextRegionGeometry::union)
                if (hypot(cx(current) - cx(previous) - dx, cy(current) - cy(previous) - dy) <= config.capturePixels(3f)) {
                    // Clear scroll evidence overrides topology probation: leaving
                    // old grouped masks behind would create a stationary ghost.
                    group.tracks.forEach { retire(it, metrics) }
                    group.detections.forEach { add(detections[it], nowMs, metrics) }
                }
            }
        }
        for (i in matches) {
            val track = available[i]
            val observed = remaining[assignment[i]]
            consumed.add(detectionIndices[assignment[i]])
            updateMatched(track, observed, nowMs, config, track.id in coherent, metrics)
            metrics.matched++
        }
        // New, unrelated detections with little continuity indicate a page/fast
        // scroll. One empty OCR result is deliberately insufficient evidence.
        val sceneChange = detections.isNotEmpty() && old.isNotEmpty() &&
            (matches.size + protected.size).toFloat() / old.size < 0.25f
        for (i in available.indices) if (assignment[i] < 0) {
            val track = available[i]
            val observed = track.lastObservedBounds
            val leavingScreen = track.wasMoving && (
                (track.dy < 0 && observed.top + track.dy < 0) ||
                (track.dy > 0 && observed.bottom + track.dy > config.captureHeight) ||
                (track.dx < 0 && observed.left + track.dx < 0) ||
                (track.dx > 0 && observed.right + track.dx > config.captureWidth))
            track.consecutiveMisses++
            track.consecutiveMatches = 0
            track.state = RegionState.MISSING
            track.movementStreak = 0
            track.groupingSinceMs = -1
            track.wasMoving = false
            clearShrink(track)
            val timeout = if (detections.isEmpty()) max(180L, config.refreshIntervalMs.coerceIn(50, 500))
                else config.missingTimeMs
            if (sceneChange || coherent.isNotEmpty() || leavingScreen ||
                (track.consecutiveMisses >= 2 && nowMs - track.lastSeenAtMs >= timeout)) {
                retire(track, metrics)
            }
        }
        for (i in detections.indices) if (i !in consumed) add(detections[i], nowMs, metrics)
        val geometry = tracks.values.map { it.stableBounds }.distinct().sortedWith(TextRegionGeometry.order)
        if (geometry != before) {
            rendered = Collections.unmodifiableList(geometry)
            metrics.geometryUpdates = 1
            changedUpdates++
        }
        updates++
        metrics.geometryUpdateRate = changedUpdates.toDouble() / updates
        metrics.suppressed = if (metrics.geometryUpdates == 0) 1 else 0
        metrics.active = tracks.size
        metrics.missing = tracks.values.count { it.state == RegionState.MISSING }
        metrics.maxMissingAgeMs = tracks.values.filter { it.state == RegionState.MISSING }
            .maxOfOrNull { nowMs - it.lastSeenAtMs } ?: 0
        metrics.maxObservedOffsetPx = tracks.values.maxOfOrNull {
            TextRegionGeometry.edgeDistance(it.stableBounds, it.lastObservedBounds) * config.scale
        } ?: 0f
        metrics.elapsedNanos = System.nanoTime() - started
        return TextRegionUpdate(rendered, metrics)
    }

    private fun add(bounds: MaskRegion, now: Long, metrics: TrackingMetrics) {
        if (tracks.size >= MAX_REGIONS) { metrics.dropped++; return }
        val region = TrackedTextRegion(nextId++, bounds, now)
        tracks[region.id] = region
        metrics.added++
    }

    private fun retire(region: TrackedTextRegion, metrics: TrackingMetrics) {
        region.state = RegionState.REMOVING
        if (tracks.remove(region.id) != null) metrics.removed++
    }

    private fun clearShrink(track: TrackedTextRegion) {
        track.shrinkCandidate.fill(Float.NaN)
        track.shrinkCount.fill(0)
    }

    private fun shapeSimilar(a: MaskRegion, b: MaskRegion) =
        width(b) / width(a) in 0.8f..1.25f && height(b) / height(a) in 0.8f..1.25f

    private fun coherentMotion(matches: List<Pair<TrackedTextRegion, MaskRegion>>, config: TextRegionTrackingConfig): Set<Long> {
        if (matches.size < 2) return emptySet()
        val plausible = matches.filter { shapeSimilar(it.first.lastObservedBounds, it.second) }
        if (plausible.size < 2) return emptySet()
        val dx = plausible.map { cx(it.second) - cx(it.first.lastObservedBounds) }.sorted()[plausible.size / 2]
        val dy = plausible.map { cy(it.second) - cy(it.first.lastObservedBounds) }.sorted()[plausible.size / 2]
        if (hypot(dx, dy) < config.capturePixels(max(1.25f, min(config.jitterTolerancePx * 0.5f, 3f)))) return emptySet()
        val agreeing = plausible.filter {
            hypot(cx(it.second) - cx(it.first.lastObservedBounds) - dx,
                cy(it.second) - cy(it.first.lastObservedBounds) - dy) <= config.capturePixels(1.5f) &&
                TextRegionGeometry.centerDistance(it.first.stableBounds, it.second) > max(config.tolerance, config.capturePixels(2f))
        }
        return if (agreeing.size >= 2 && agreeing.size.toFloat() / matches.size >= 0.7f)
            agreeing.map { it.first.id }.toSet() else emptySet()
    }

    private fun updateMatched(
        track: TrackedTextRegion, observed: MaskRegion, now: Long,
        config: TextRegionTrackingConfig, coherent: Boolean, metrics: TrackingMetrics,
    ) {
        val previous = track.lastObservedBounds
        val dx = cx(observed) - cx(previous)
        val dy = cy(observed) - cy(previous)
        val magnitude = hypot(dx, dy)
        val similar = shapeSimilar(previous, observed)
        val consistent = dx * track.dx + dy * track.dy >
            0.8f * magnitude * hypot(track.dx, track.dy)
        track.movementStreak = if (similar && magnitude > config.capturePixels(0.75f)) {
            if (consistent) min(3, track.movementStreak + 1) else 1
        } else 0
        val fast = similar && magnitude > max(config.capturePixels(6f), max(config.tolerance * 2, height(previous) * 0.35f))
        val sustained = similar && track.movementStreak >= 2 &&
            TextRegionGeometry.centerDistance(track.stableBounds, observed) > max(config.tolerance, config.capturePixels(2f))
        val continuing = track.wasMoving && similar && consistent && track.movementStreak >= 2
        val moving = coherent || fast || sustained || continuing
        track.wasMoving = moving
        track.groupingSinceMs = -1
        track.lastObservedBounds = observed
        track.lastSeenAtMs = now
        track.consecutiveMatches++
        track.consecutiveMisses = 0
        track.state = RegionState.ACTIVE
        track.dx = dx
        track.dy = dy
        if (moving) {
            track.stableBounds = observed
            clearShrink(track)
            metrics.moving++
            return
        }
        val stable = track.stableBounds
        val safeAvailable = SafeAreaEvaluator.contains(stable, stable, config)
        val safe = safeAvailable && SafeAreaEvaluator.contains(stable, observed, config)
        val held = safe || (!safeAvailable && TextRegionGeometry.edgeDistance(stable, observed) <= config.tolerance)
        if (safe) metrics.safeHits++
        val values = floatArrayOf(stable.left, stable.top, stable.right, stable.bottom)
        val obs = floatArrayOf(observed.left, observed.top, observed.right, observed.bottom)
        val buffer = config.capturePixels(1f)
        var expanded = false
        var contracted = false
        for (edge in 0..3) {
            val direction = if (edge < 2) 1f else -1f // Positive is contraction.
            val change = (obs[edge] - values[edge]) * direction
            if (!held && change < 0) {
                values[edge] = obs[edge] - direction * buffer
                track.shrinkCandidate[edge] = Float.NaN
                track.shrinkCount[edge] = 0
                expanded = true
                continue
            }
            val dimension = if (edge == 0 || edge == 2) width(stable) else height(stable)
            val shrinkThreshold = max(config.capturePixels(4f), max(2 * config.tolerance, dimension * 0.15f))
            if (change <= shrinkThreshold) {
                track.shrinkCandidate[edge] = Float.NaN
                track.shrinkCount[edge] = 0
                continue
            }
            val candidate = track.shrinkCandidate[edge]
            if (!candidate.isFinite() || abs(candidate - obs[edge]) > config.capturePixels(2f)) {
                track.shrinkCandidate[edge] = obs[edge]
                track.shrinkSince[edge] = now
                track.shrinkCount[edge] = 1
            } else {
                // The outward-most consistent proposal protects all supporting observations.
                track.shrinkCandidate[edge] = if (edge < 2) min(candidate, obs[edge]) else max(candidate, obs[edge])
                track.shrinkCount[edge]++
                if (track.shrinkCount[edge] >= 3 && now - track.shrinkSince[edge] >= config.shrinkTimeMs) {
                    values[edge] = track.shrinkCandidate[edge]
                    contracted = true
                }
            }
        }
        val proposed = MaskRegion(values[0], values[1], values[2], values[3])
        if (width(proposed) > 0 && height(proposed) > 0 &&
            (!contracted || !safeAvailable || SafeAreaEvaluator.contains(proposed, observed, config))) {
            if (proposed != stable) track.stableBounds = proposed
            if (expanded) metrics.expansions++
            if (contracted) metrics.contractions++
        } else if (expanded) {
            track.stableBounds = TextRegionGeometry.union(stable, observed)
            metrics.expansions++
        }
    }

    private fun resolveGrouping(
        old: List<TrackedTextRegion>, detections: List<MaskRegion>, now: Long,
        config: TextRegionTrackingConfig, protected: MutableSet<Long>, consumed: MutableSet<Int>, metrics: TrackingMetrics,
    ): List<PendingGrouping> {
        val pending = mutableListOf<PendingGrouping>()
        val parent = IntArray(old.size + detections.size) { it }
        fun root(index: Int): Int {
            var i = index
            while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i] }
            return i
        }
        // Detections are sorted by top. Prefix maximum bottoms bound every
        // possible vertical intersection, including unusually tall rectangles.
        val tops = FloatArray(detections.size) { detections[it].top }
        val maximumBottoms = FloatArray(detections.size)
        var bottom = Float.NEGATIVE_INFINITY
        for (j in detections.indices) {
            bottom = max(bottom, detections[j].bottom)
            maximumBottoms[j] = bottom
        }
        for (i in old.indices) {
            val a = old[i].lastObservedBounds
            val first = TextRegionGeometry.upperBound(maximumBottoms, a.top)
            val end = TextRegionGeometry.lowerBound(tops, a.bottom)
            for (j in first until end) {
                val b = detections[j]
                if (TextRegionGeometry.intersection(a, b) / min(TextRegionGeometry.area(a), TextRegionGeometry.area(b)) >= 0.5f) {
                    parent[root(old.size + j)] = root(i)
                }
            }
        }
        val oldComponents = old.indices.groupBy { root(it) }
        val newComponents = detections.indices.groupBy { root(old.size + it) }
        val usedKeys = mutableSetOf<String>()
        for ((component, indices) in oldComponents) {
            val newIndices = newComponents[component] ?: continue
            if (indices.size == newIndices.size || (indices.size != 1 && newIndices.size != 1)) continue
            val oldUnion = indices.map { old[it].lastObservedBounds }.reduce(TextRegionGeometry::union)
            val newUnion = newIndices.map { detections[it] }.reduce(TextRegionGeometry::union)
            if (TextRegionGeometry.iou(oldUnion, newUnion) < 0.4f) continue
            val key = indices.joinToString(",") { old[it].id.toString() } + ":${newIndices.size}"
            usedKeys.add(key)
            val previous = groups[key]
            val evidence = if (previous == null || TextRegionGeometry.iou(previous.union, newUnion) < 0.85f) {
                GroupEvidence(now, 1, newUnion).also { groups[key] = it }
            } else previous.apply { count++; union = newUnion }
            indices.forEach { if (old[it].groupingSinceMs < 0) old[it].groupingSinceMs = now }
            val expired = indices.any {
                now - old[it].groupingSinceMs >= max(750L, config.refreshIntervalMs.coerceIn(50, 500) * 2)
            }
            val confirmed = expired || (evidence.count >= 2 &&
                now - evidence.since >= max(350L, config.refreshIntervalMs.coerceIn(50, 500)))
            indices.forEach {
                val track = old[it]
                protected.add(track.id)
                clearShrink(track)
                if (confirmed) retire(track, metrics)
            }
            consumed.addAll(newIndices)
            metrics.grouped += indices.size
            if (confirmed) newIndices.forEach { add(detections[it], now, metrics) }
            else pending.add(PendingGrouping(indices.map { old[it] }, newIndices))
        }
        groups.keys.retainAll(usedKeys)
        return pending
    }
}

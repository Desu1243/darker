package com.desu1243.darker

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Deterministic minimum-cost one-to-one assignment with an unmatched slot per row. */
internal object TextRegionMatcher {
    private const val UNMATCHED = 4.0
    private fun cost(old: MaskRegion, new: MaskRegion, config: TextRegionTrackingConfig): Double {
        val h = max(TextRegionGeometry.height(old), TextRegionGeometry.height(new))
        val w = max(TextRegionGeometry.width(old), TextRegionGeometry.width(new))
        val dx = abs(TextRegionGeometry.cx(old) - TextRegionGeometry.cx(new))
        val dy = abs(TextRegionGeometry.cy(old) - TextRegionGeometry.cy(new))
        val heightRatio = min(TextRegionGeometry.height(old), TextRegionGeometry.height(new)) / h
        val widthRatio = min(TextRegionGeometry.width(old), TextRegionGeometry.width(new)) / w
        if (dy > max(config.capturePixels(10f), h * 0.9f) ||
            dx > max(config.capturePixels(20f), w * 0.35f) ||
            heightRatio < 0.5f || widthRatio < 0.3f) return 1e6
        return (3 * dy / h + dx / w + 1 - TextRegionGeometry.iou(old, new) -
            ln(widthRatio) - 1.5f * ln(heightRatio)).toDouble()
    }

    fun match(old: List<MaskRegion>, detections: List<MaskRegion>, config: TextRegionTrackingConfig): IntArray {
        val result = IntArray(old.size) { -1 }
        if (old.isEmpty() || detections.isEmpty()) return result
        // Plausible edges form independent assignment components. Most reading
        // pages have one small component per line rather than one dense matrix.
        val parent = IntArray(old.size + detections.size) { it }
        fun root(index: Int): Int {
            var i = index
            while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i] }
            return i
        }
        data class Edge(val detection: Int, val cost: Double)
        val edges = Array(old.size) { mutableListOf<Edge>() }
        val byCenter = detections.indices.sortedBy { TextRegionGeometry.cy(detections[it]) }
        val centers = FloatArray(byCenter.size) { TextRegionGeometry.cy(detections[byCenter[it]]) }
        val largestHeight = max(old.maxOf { TextRegionGeometry.height(it) }, detections.maxOf { TextRegionGeometry.height(it) })
        val verticalGate = max(config.capturePixels(10f), largestHeight * 0.9f)
        for (i in old.indices) {
            val center = TextRegionGeometry.cy(old[i])
            val first = TextRegionGeometry.lowerBound(centers, center - verticalGate)
            val end = TextRegionGeometry.upperBound(centers, center + verticalGate)
            for (k in first until end) {
                val j = byCenter[k]
                val score = cost(old[i], detections[j], config)
                if (score < UNMATCHED) {
                    edges[i].add(Edge(j, score))
                    parent[root(old.size + j)] = root(i)
                }
            }
        }
        val components = old.indices.filter { edges[it].isNotEmpty() }.groupBy { root(it) }
        for (rows in components.values) {
            val columns = rows.flatMap { edges[it].map { edge -> edge.detection } }.distinct().sorted()
            if (rows.size == 1 && columns.size == 1) {
                result[rows[0]] = columns[0]
                continue
            }
            val positions = columns.withIndex().associate { it.value to it.index }
            val costs = Array(rows.size) { DoubleArray(columns.size) { 1e6 } }
            rows.forEachIndexed { row, original ->
                edges[original].forEach { edge -> costs[row][positions.getValue(edge.detection)] = edge.cost }
            }
            val assignment = assign(costs, columns.size)
            rows.forEachIndexed { i, original ->
                if (assignment[i] >= 0) result[original] = columns[assignment[i]]
            }
        }
        return result
    }

    private fun assign(costs: Array<DoubleArray>, detectionCount: Int): IntArray {
        val n = costs.size
        val m = detectionCount + n
        val result = IntArray(n) { -1 }
        val u = DoubleArray(n + 1)
        val v = DoubleArray(m + 1)
        val p = IntArray(m + 1)
        val way = IntArray(m + 1)
        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
            val used = BooleanArray(m + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.POSITIVE_INFINITY
                var j1 = 0
                for (j in 1..m) if (!used[j]) {
                    val base = if (j <= detectionCount) costs[i0 - 1][j - 1] else UNMATCHED
                    val cur = base - u[i0] - v[j]
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0 }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j }
                }
                for (j in 0..m) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minv[j] -= delta
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        for (j in 1..detectionCount) if (p[j] != 0 && costs[p[j] - 1][j - 1] < UNMATCHED) {
            result[p[j] - 1] = j - 1
        }
        return result
    }
}

package com.desu1243.darker

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic observations on Android; no captures or recognized strings. */
@RunWith(AndroidJUnit4::class)
class PersistentTextRegionDeviceTest {
    @Test fun stationaryTrackingBenchmarkAndContinuousMotion() {
        for (lineCount in listOf(100, 300)) {
            val tracker = PersistentTextRegionTracker()
            val config = TextRegionTrackingConfig(1080, 2400, 1080, 2400, 12f, 10f, 12f)
            val spacing = if (lineCount == 100) 20f else 7f
            val height = if (lineCount == 100) 12f else 5f
            val original = (0 until lineCount).map { i ->
                MaskRegion(100f, 50 + i * spacing, 900f, 50 + i * spacing + height)
            }
            val initial = tracker.update(original, 0, config).renderRegions
            val times = mutableListOf<Double>()
            repeat(70) { frame ->
                val observations = original.mapIndexed { i, box ->
                    val delta = ((i + frame) % 3 - 1) * 0.3f
                    box.copy(left = box.left + delta, right = box.right - delta)
                }.reversed()
                val result = tracker.update(observations, (frame + 1) * 50L, config)
                assertSame(initial, result.renderRegions)
                assertEquals(lineCount, result.metrics.matched)
                assertEquals(0, result.metrics.geometryUpdates)
                if (frame >= 10) times.add(result.metrics.elapsedNanos / 1_000_000.0)
            }
            val sorted = times.sorted()
            Log.i("DarkerTrackingBenchmark", "lines=$lineCount medianMs=${sorted[sorted.size / 2]} " +
                "p95Ms=${sorted[(sorted.size * 0.95).toInt()]} unchangedFrames=70/70")
        }
        val tracker = PersistentTextRegionTracker()
        val config = TextRegionTrackingConfig(1080, 2400, 1080, 2400, 40f, 10f, 4f)
        val initial = MaskRegion(100f, 500f, 500f, 530f)
        tracker.update(listOf(initial), 0, config)
        val id = tracker.trackedRegions.single().id
        repeat(20) { step ->
            val offset = (step + 1) * 2f
            val observed = initial.copy(top = initial.top - offset, bottom = initial.bottom - offset)
            tracker.update(listOf(observed), (step + 1) * 50L, config)
            assertEquals(id, tracker.trackedRegions.single().id)
            if (step >= 3) assertEquals(observed, tracker.trackedRegions.single().stableBounds)
        }
    }
}

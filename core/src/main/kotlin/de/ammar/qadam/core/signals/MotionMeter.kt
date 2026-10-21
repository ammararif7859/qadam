package de.ammar.qadam.core.signals

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Motion level (SPEC §4, signal 5): the RMS of linear acceleration over a sliding window.
 *
 * RMS — root mean square: square every reading, average, take the root. It is the typical
 * size of the shaking: about 0.02 m/s² on a table, more in a hand, much more when carried.
 *
 * Once per second the meter computes the RMS of the last [windowS] seconds. A summary
 * row then asks for the mean and the 90th percentile of those per-second values.
 */
class MotionMeter(windowS: Double) {
    private val windowNs = (windowS * 1e9).toLong()
    private val times = ArrayDeque<Long>()
    private val squares = ArrayDeque<Double>()
    private var lastSecond = Long.MIN_VALUE
    private val values = mutableListOf<Double>()

    /** Feeds one linear-acceleration sample (m/s²) with its timestamp in nanoseconds. */
    fun onLinear(tNs: Long, x: Double, y: Double, z: Double) {
        times.addLast(tNs)
        squares.addLast(x * x + y * y + z * z)
        while (times.isNotEmpty() && times.first() < tNs - windowNs) {
            times.removeFirst(); squares.removeFirst()
        }
        val second = Math.floorDiv(tNs, 1_000_000_000L)
        if (second != lastSecond) {
            lastSecond = second
            values += sqrt(squares.sum() / squares.size)
        }
    }

    /** Mean and 90th percentile of the per-second RMS values since the last call; then clears. */
    fun flush(): Pair<Double?, Double?> {
        if (values.isEmpty()) return null to null
        val mean = values.average()
        val p90 = percentile(values, 0.9)
        values.clear()
        return mean to p90
    }

    /** Forget the window (call when the sensor stops, so old samples don't leak into new rows). */
    fun reset() {
        times.clear(); squares.clear(); values.clear(); lastSecond = Long.MIN_VALUE
    }

    companion object {
        /** Nearest-rank percentile, [q] in 0..1. */
        fun percentile(xs: List<Double>, q: Double): Double {
            require(xs.isNotEmpty())
            val sorted = xs.sorted()
            val rank = ceil(q * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}

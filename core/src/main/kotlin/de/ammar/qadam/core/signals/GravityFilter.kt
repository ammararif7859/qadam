package de.ammar.qadam.core.signals

/**
 * Fallback for phones without a gravity sensor (SPEC §4).
 *
 * A low-pass filter splits the accelerometer reading in two: the slow part is gravity,
 * the rest is linear acceleration (the shaking). The time constant [tauS] sets "slow":
 * changes faster than about [tauS] seconds count as shaking.
 *
 * Timestamps are in nanoseconds, as Android's `SensorEvent.timestamp`.
 */
class GravityFilter(private val tauS: Double) {
    var gx = 0.0; private set
    var gy = 0.0; private set
    var gz = 0.0; private set
    var lx = 0.0; private set
    var ly = 0.0; private set
    var lz = 0.0; private set

    private var lastNs = -1L

    /** True once the first sample has arrived. */
    val ready: Boolean get() = lastNs >= 0

    fun update(tNs: Long, ax: Double, ay: Double, az: Double) {
        if (lastNs < 0 || tNs - lastNs > GAP_NS) {
            gx = ax; gy = ay; gz = az
        } else {
            val dt = (tNs - lastNs).coerceAtLeast(0) / 1e9
            val alpha = dt / (tauS + dt)
            gx += alpha * (ax - gx)
            gy += alpha * (ay - gy)
            gz += alpha * (az - gz)
        }
        lastNs = tNs
        lx = ax - gx; ly = ay - gy; lz = az - gz
    }

    fun reset() {
        lastNs = -1L
    }

    private companion object {
        /** After a pause in samples longer than this, start again from the new reading. */
        const val GAP_NS = 2_000_000_000L
    }
}

package de.ammar.qadam.core.signals

import de.ammar.qadam.core.config.Thresholds
import kotlin.math.sqrt

/**
 * Step detector from the accelerometer, for phones without `TYPE_STEP_DETECTOR` (SPEC §4).
 * Ammar's Tecno Pova 5 Pro lists no step sensor, so this is the main step source there.
 *
 * How it works:
 * 1. Take the size of the acceleration vector, so the phone's orientation doesn't matter.
 * 2. Smooth it lightly (removes sensor noise) and track its slow average (gravity + drift).
 * 3. The difference is the bounce of walking. A step is a peak of the bounce that is
 *    high enough ([minPeak]) and follows the previous peak at walking rhythm
 *    (between [minHz] and [maxHz] steps per second).
 * 4. Peaks count only inside a run of at least [minRun] peaks at a steady pace (each gap
 *    within ±35 % of the run's average). A single bump, turning over in bed or random
 *    fidgeting never makes such a run. Inside a confirmed run, one missed peak (a gap of
 *    about two steps) is counted as two steps instead of breaking the run.
 *
 * The rate of samples can be anything from about 10 Hz up; only timestamps matter.
 */
class AccelStepDetector(
    private val minHz: Double = 1.0,
    private val maxHz: Double = 2.5,
    private val minPeak: Double = 0.8,
    private val minRun: Int = 4,
    private val smoothS: Double = 0.05,
    private val baselineS: Double = 1.0,
) {
    constructor(t: Thresholds) : this(
        minHz = t["step.min_hz"],
        maxHz = t["step.max_hz"],
        minPeak = t["step.min_peak"],
        minRun = t.int("step.min_run"),
        smoothS = t["step.smooth_s"],
    )

    private val minGapNs = (1e9 / maxHz).toLong()
    private val maxGapNs = (1e9 / minHz).toLong()

    private var lastNs = -1L
    private var smooth = 0.0
    private var base = 0.0
    private var prevD = 0.0
    private var prevNs = 0L
    private var rising = false
    private var armed = true
    private var lastPeakNs = -1L
    private var run = 0
    private var period = 0.0 // average time between steps in the current run (ns)

    /** All steps counted since creation. */
    var total = 0
        private set

    /**
     * Feeds one accelerometer sample (m/s², including gravity).
     * Returns how many steps were confirmed by this sample: usually 0 or 1, and [minRun]
     * at the moment a run becomes long enough (its earlier peaks are counted then).
     */
    fun onSample(tNs: Long, x: Double, y: Double, z: Double): Int {
        val m = sqrt(x * x + y * y + z * z)
        if (lastNs < 0 || tNs - lastNs > GAP_NS) {
            // First sample, or the sensor was off: start fresh.
            smooth = m; base = m; prevD = 0.0; prevNs = tNs
            rising = false; armed = true; lastPeakNs = -1L; run = 0
            lastNs = tNs
            return 0
        }
        val dt = (tNs - lastNs) / 1e9
        lastNs = tNs
        if (dt <= 0.0) return 0

        smooth += (m - smooth) * dt / (smoothS + dt)
        base += (smooth - base) * dt / (baselineS + dt)
        val d = smooth - base

        var steps = 0
        if (d < prevD) {
            if (rising && armed && prevD >= minPeak) {
                steps = onPeak(prevNs)
                armed = false
            }
            rising = false
        } else if (d > prevD) {
            rising = true
        }
        // Hysteresis: after a peak, the bounce must fall back below the average first.
        if (d < 0.0) armed = true

        prevD = d
        prevNs = tNs
        total += steps
        return steps
    }

    private fun onPeak(tNs: Long): Int {
        if (lastPeakNs < 0) return startRun(tNs)
        val gap = (tNs - lastPeakNs).toDouble()
        if (gap < minGapNs) return 0 // too soon after the last peak: a double bounce, ignore it

        if (run == 1) {
            // Second peak of a run: only the walking-rhythm limits apply.
            if (gap > maxGapNs) return startRun(tNs)
            lastPeakNs = tNs; run = 2; period = gap
            return if (run >= minRun) minRun else 0
        }

        val ratio = gap / period
        val newSteps = when {
            // Same pace as the run so far: one more step.
            ratio in STEADY && gap <= maxGapNs -> 1
            // Exactly one peak missed inside a confirmed run (too low to see): two steps.
            run >= minRun && ratio in ONE_MISSED -> 2
            // Pace broken: this peak starts a new run.
            else -> return startRun(tNs)
        }
        lastPeakNs = tNs
        period = 0.7 * period + 0.3 * (gap / newSteps)
        val before = run
        run += newSteps
        return when {
            before >= minRun -> newSteps
            run >= minRun -> run // the run just became long enough: count all its steps
            else -> 0
        }
    }

    private fun startRun(tNs: Long): Int {
        lastPeakNs = tNs; run = 1; period = 0.0
        return 0
    }

    private companion object {
        const val GAP_NS = 1_000_000_000L

        /** A step keeps the run's pace if its gap is within ±35 % of the average gap. */
        val STEADY = 0.65..1.35

        /** A gap of about two steps: one peak was missed. */
        val ONE_MISSED = 1.7..2.3
    }
}

package de.ammar.qadam.core.logger

import de.ammar.qadam.core.config.Thresholds
import de.ammar.qadam.core.model.AppCategory
import de.ammar.qadam.core.model.DriftState
import de.ammar.qadam.core.model.Label
import de.ammar.qadam.core.model.SummaryRow
import de.ammar.qadam.core.model.TimeWindow
import de.ammar.qadam.core.signals.MotionMeter

/** What the app knows at the moment a row closes (the rest comes from the accumulator). */
data class RowContext(
    val timeMs: Long,
    val screenOn: Boolean,
    val app: String?,
    val appCategory: AppCategory?,
    val sessionS: Int,
    val stepsLast5min: Int,
    val audio: Boolean,
    val headphones: Boolean,
    val charging: Boolean,
    val choreMode: Boolean,
    val window: TimeWindow,
    val state: DriftState,
    val level: Int,
)

/**
 * Collects everything that happens between two summary rows (SPEC §4): counts of scrolls,
 * taps and steps; motion level; mean gravity; how much of the time the proximity sensor
 * said "near"; the time-weighted mean light level; and a sampling label if one came in.
 *
 * Sensor timestamps are nanoseconds since boot (Android's `SensorEvent.timestamp`).
 * [flush] turns the collected values into one [SummaryRow] and starts the next row.
 */
class RowAccumulator(t: Thresholds = Thresholds.DEFAULT) {
    private val motion = MotionMeter(t["motion.window_s"])

    private var rowStartNs = -1L
    private var scrolls = 0
    private var taps = 0
    private var steps = 0
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var gN = 0
    private var label: Label? = null

    // Proximity and light report only on change, so their values are weighted by time.
    private var near: Boolean? = null
    private var nearSinceNs = 0L
    private var nearNs = 0L
    private var proxSeenNs = 0L
    private var lux: Double? = null
    private var luxSinceNs = 0L
    private var luxWeighted = 0.0
    private var luxSeenNs = 0L

    fun addScrolls(n: Int) { scrolls += n }
    fun addTaps(n: Int) { taps += n }
    fun addSteps(n: Int) { steps += n }
    fun setLabel(l: Label) { label = l }

    fun onLinear(tNs: Long, x: Double, y: Double, z: Double) {
        if (rowStartNs < 0) rowStartNs = tNs
        motion.onLinear(tNs, x, y, z)
    }

    fun onGravity(tNs: Long, x: Double, y: Double, z: Double) {
        if (rowStartNs < 0) rowStartNs = tNs
        gx += x; gy += y; gz += z; gN++
    }

    fun onProximity(tNs: Long, isNear: Boolean) {
        closeProximity(tNs)
        near = isNear
        nearSinceNs = tNs
    }

    fun onLight(tNs: Long, value: Double) {
        closeLight(tNs)
        lux = value
        luxSinceNs = tNs
    }

    /** Call when the sensors are switched off: their readings stop being valid. */
    fun sensorsStopped(nowNs: Long) {
        closeProximity(nowNs); near = null
        closeLight(nowNs); lux = null
        motion.reset()
    }

    private fun closeProximity(nowNs: Long) {
        val n = near ?: return
        val dt = (nowNs - nearSinceNs).coerceAtLeast(0)
        proxSeenNs += dt
        if (n) nearNs += dt
        nearSinceNs = nowNs
    }

    private fun closeLight(nowNs: Long) {
        val l = lux ?: return
        val dt = (nowNs - luxSinceNs).coerceAtLeast(0)
        luxSeenNs += dt
        luxWeighted += l * dt
        luxSinceNs = nowNs
    }

    /** Closes the current row at [nowNs] (since boot) and returns it. */
    fun flush(nowNs: Long, ctx: RowContext): SummaryRow {
        closeProximity(nowNs)
        closeLight(nowNs)
        val (rmsMean, rmsP90) = motion.flush()
        val proximity = when {
            proxSeenNs > 0 -> nearNs.toDouble() / proxSeenNs
            near != null -> if (near == true) 1.0 else 0.0
            else -> null
        }
        val luxMean = when {
            luxSeenNs > 0 -> luxWeighted / luxSeenNs
            else -> lux
        }
        val row = SummaryRow(
            timeMs = ctx.timeMs,
            screenOn = ctx.screenOn,
            app = ctx.app,
            appCategory = ctx.appCategory,
            sessionS = ctx.sessionS,
            scrolls = scrolls,
            taps = taps,
            steps = steps,
            stepsLast5min = ctx.stepsLast5min,
            motionRmsMean = rmsMean,
            motionRmsP90 = rmsP90,
            gravityX = if (gN > 0) gx / gN else null,
            gravityY = if (gN > 0) gy / gN else null,
            gravityZ = if (gN > 0) gz / gN else null,
            proximityNear = proximity,
            lux = luxMean,
            audio = ctx.audio,
            headphones = ctx.headphones,
            charging = ctx.charging,
            choreMode = ctx.choreMode,
            window = ctx.window,
            state = ctx.state,
            level = ctx.level,
            label = label,
        )
        // Start the next row.
        rowStartNs = nowNs
        scrolls = 0; taps = 0; steps = 0
        gx = 0.0; gy = 0.0; gz = 0.0; gN = 0
        label = null
        nearNs = 0L; proxSeenNs = 0L
        luxWeighted = 0.0; luxSeenNs = 0L
        return row
    }
}

/** SPEC §4 row timing: every 30 s while the screen is on or audio plays, else every 5 min. */
object RowTiming {
    fun intervalMs(t: Thresholds, screenOn: Boolean, audio: Boolean): Long =
        if (screenOn || audio) t.ms("summary.active_s") else t.ms("summary.idle_s")

    /** Sensors 5-8 (and the accelerometer fallback) run only while the screen is on or audio plays. */
    fun sensorsWanted(screenOn: Boolean, audio: Boolean): Boolean = screenOn || audio
}

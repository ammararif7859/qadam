package de.ammar.qadam.core.logger

import de.ammar.qadam.core.model.AppCategory
import de.ammar.qadam.core.model.DriftState
import de.ammar.qadam.core.model.EventRow
import de.ammar.qadam.core.model.EventType
import de.ammar.qadam.core.model.Label
import de.ammar.qadam.core.model.SummaryRow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * One day in numbers: what the Today screen shows, what the morning notification says,
 * and what is kept after 30 days when the summary rows are deleted (SPEC §10 retention).
 */
data class DayTotals(
    val date: LocalDate,
    /** Minutes in a drift state of SPEC §6 (DRIFT_SCREEN, DRIFT_SCROLL, DRIFT_AUDIO, NIGHT). */
    val driftMin: Double,
    /** Minutes with a drift app in front (screen on), drifting or not. */
    val driftAppMin: Double,
    val choreMin: Double,
    val triggers: Int,
    val falsePositives: Int,
    val overrides: Int,
    val pauses: Int,
    val serviceOffMin: Double,
    val accessibilityOffMin: Double,
    val labels: Map<Label, Int>,
)

object Reports {
    private val DRIFT_STATES = setOf(DriftState.DRIFT_SCREEN, DriftState.DRIFT_SCROLL, DriftState.DRIFT_AUDIO, DriftState.NIGHT)

    /** Longest stretch one row may stand for: a 5-minute idle row (more means the service was off). */
    private const val MAX_ROW_MS = 5 * 60_000L

    /** Minutes each row stands for: the time since the previous row, at most 5 min. */
    fun rowMinutes(rows: List<SummaryRow>): List<Double> {
        val sorted = rows.sortedBy { it.timeMs }
        return sorted.mapIndexed { i, r ->
            val ms = if (i == 0) {
                if (r.screenOn || r.audio) 30_000L else MAX_ROW_MS
            } else {
                (r.timeMs - sorted[i - 1].timeMs).coerceIn(0, MAX_ROW_MS)
            }
            ms / 60_000.0
        }
    }

    fun totals(date: LocalDate, rows: List<SummaryRow>, events: List<EventRow>, zone: ZoneId): DayTotals {
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayRows = rows.filter { it.timeMs in from until to }.sortedBy { it.timeMs }
        val minutes = rowMinutes(dayRows)
        var drift = 0.0; var driftApp = 0.0; var chore = 0.0
        dayRows.forEachIndexed { i, r ->
            if (r.state in DRIFT_STATES) drift += minutes[i]
            if (r.screenOn && r.appCategory == AppCategory.DRIFT) driftApp += minutes[i]
            if (r.choreMode) chore += minutes[i]
        }
        val dayEvents = events.filter { it.timeMs in from until to }
        val (svcOff, accOff) = offTimes(events, from, to)
        return DayTotals(
            date = date,
            driftMin = drift,
            driftAppMin = driftApp,
            choreMin = chore,
            triggers = dayEvents.count { it.type == EventType.INTERVENTION },
            falsePositives = dayEvents.count { it.type == EventType.RESPONSE && it.detail.startsWith("wrong") },
            overrides = dayEvents.count { it.type == EventType.OVERRIDE },
            pauses = dayEvents.count { it.type == EventType.PAUSE },
            serviceOffMin = svcOff / 60_000.0,
            accessibilityOffMin = accOff / 60_000.0,
            labels = dayRows.mapNotNull { it.label }.groupingBy { it }.eachCount(),
        )
    }

    /**
     * How long the foreground service and the accessibility service were off inside
     * [fromMs, toMs) (SPEC §8). Service gaps come as SERVICE_GAP events with detail
     * "from=<ms> to=<ms>"; accessibility as ACCESSIBILITY_OFF … ACCESSIBILITY_ON pairs.
     */
    fun offTimes(events: List<EventRow>, fromMs: Long, toMs: Long): Pair<Long, Long> {
        fun overlap(a: Long, b: Long) = (minOf(b, toMs) - maxOf(a, fromMs)).coerceAtLeast(0)
        var svc = 0L
        for (e in events.filter { it.type == EventType.SERVICE_GAP }) {
            val f = Regex("from=(\\d+)").find(e.detail)?.groupValues?.get(1)?.toLong() ?: continue
            val t = Regex("to=(\\d+)").find(e.detail)?.groupValues?.get(1)?.toLong() ?: continue
            svc += overlap(f, t)
        }
        var acc = 0L
        var offAt: Long? = null
        for (e in events.sortedBy { it.timeMs }) {
            when (e.type) {
                EventType.ACCESSIBILITY_OFF -> if (offAt == null) offAt = e.timeMs
                EventType.ACCESSIBILITY_ON -> offAt?.let { acc += overlap(it, e.timeMs); offAt = null }
                else -> {}
            }
        }
        offAt?.let { acc += overlap(it, toMs) }
        return svc to acc
    }

    /** The service writes a heartbeat (a summary row) at least every 5 min; a longer silence is a gap. */
    fun gapEvent(lastHeartbeatMs: Long?, nowMs: Long): EventRow? {
        if (lastHeartbeatMs == null) return null
        return if (nowMs - lastHeartbeatMs > MAX_ROW_MS + 60_000L) {
            EventRow(nowMs, EventType.SERVICE_GAP, "from=$lastHeartbeatMs to=$nowMs")
        } else {
            null
        }
    }

    fun minutesText(min: Double): String {
        val m = Math.round(min).toInt()
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    /** The morning notification (SPEC §8): yesterday's numbers and how long Qadam was off. */
    fun morningText(t: DayTotals): String = buildString {
        append("Yesterday: drift ${minutesText(t.driftMin)}, ${t.triggers} nudges, chores ${minutesText(t.choreMin)}.")
        if (t.serviceOffMin >= 1) append(" Qadam was off ${minutesText(t.serviceOffMin)}.")
        if (t.accessibilityOffMin >= 1) append(" The scroll counter was off ${minutesText(t.accessibilityOffMin)}.")
        if (t.overrides > 0) append(" Overrides: ${t.overrides}.")
    }

    /** Show the morning summary at the first screen-on after night end, once per day. */
    fun morningDue(nowMs: Long, zone: ZoneId, nightEnd: LocalTime, lastShownDay: LocalDate?, screenOn: Boolean): Boolean {
        if (!screenOn) return false
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        return local.toLocalTime() >= nightEnd && local.toLocalDate() != lastShownDay
    }

    /** Summary rows older than this are folded into [DayTotals] and deleted (SPEC §10: 30 days). */
    fun retentionCutoff(nowMs: Long, zone: ZoneId, keepDays: Long = 30): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().minusDays(keepDays).atStartOfDay(zone).toInstant().toEpochMilli()
}

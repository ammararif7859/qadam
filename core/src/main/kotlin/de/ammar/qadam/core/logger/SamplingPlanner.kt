package de.ammar.qadam.core.logger

import de.ammar.qadam.core.config.Thresholds
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Experience sampling (SPEC §11): about 5 random prompts a day, "Right now: chores /
 * studying / eating / resting on purpose / drifting / other", shown while the screen is on.
 *
 * Each day gets its own random plan: [perDay] times between [startHour]:00 and bedtime,
 * at least [minGapMin] minutes apart. A prompt that falls due while the screen is off waits
 * for the screen to come on, at most [waitMin] minutes; then it is dropped.
 *
 * The plan for a day depends only on the date and a seed stored once per install, so the
 * app can recompute it after a restart without storing it.
 */
class SamplingPlanner(
    private val perDay: Int,
    private val startHour: Int,
    private val minGapMin: Int,
    private val waitMin: Int,
) {
    constructor(t: Thresholds) : this(
        perDay = t.int("sampling.per_day"),
        startHour = t.int("sampling.start_hour"),
        minGapMin = t.int("sampling.min_gap_min"),
        waitMin = t.int("sampling.wait_min"),
    )

    /** The prompt times for [date], sorted. Fewer than [perDay] only if the day is too short. */
    fun plan(date: LocalDate, bedtime: LocalTime, installSeed: Long): List<LocalDateTime> {
        val start = startHour * 60
        var end = bedtime.hour * 60 + bedtime.minute
        if (end <= start) end = 24 * 60 // bedtime after midnight: sample until midnight
        val span = end - start
        val rnd = Random(installSeed xor (date.toEpochDay() * 0x9E3779B97F4A7C15uL.toLong()))
        var n = perDay
        while (n > 0) {
            if ((n - 1) * minGapMin < span) {
                repeat(2000) {
                    val mins = List(n) { start + rnd.nextInt(span) }.sorted()
                    if (mins.zipWithNext().all { (a, b) -> b - a >= minGapMin }) {
                        return mins.map { date.atStartOfDay().plusMinutes(it.toLong()) }
                    }
                }
            }
            n-- // the day is too short for this many prompts: try one fewer
        }
        return emptyList()
    }

    /**
     * Which planned prompt (if any) to show now. [fired] holds the planned times already
     * shown or dropped (epoch ms). Returns the planned time to show, or null.
     */
    fun due(
        nowMs: Long,
        screenOn: Boolean,
        plan: List<LocalDateTime>,
        fired: Set<Long>,
        zone: ZoneId,
    ): Long? {
        if (!screenOn) return null
        val waitMs = waitMin * 60_000L
        return plan.map { it.atZone(zone).toInstant().toEpochMilli() }
            .filter { it !in fired && it <= nowMs && nowMs - it <= waitMs }
            .maxOrNull()
    }

    /** Planned times that passed without the screen coming on in time: mark them dropped. */
    fun expired(nowMs: Long, plan: List<LocalDateTime>, fired: Set<Long>, zone: ZoneId): List<Long> {
        val waitMs = waitMin * 60_000L
        return plan.map { it.atZone(zone).toInstant().toEpochMilli() }
            .filter { it !in fired && nowMs - it > waitMs }
    }
}

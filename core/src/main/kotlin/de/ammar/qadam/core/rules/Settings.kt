package de.ammar.qadam.core.rules

import de.ammar.qadam.core.config.Thresholds
import de.ammar.qadam.core.model.AppCategory
import de.ammar.qadam.core.model.TimeWindow
import java.time.LocalTime

/** A daily time range, e.g. 13:00–14:00. It may wrap midnight (23:30–06:00). End is exclusive. */
data class TimeRange(val start: LocalTime, val end: LocalTime) {
    operator fun contains(t: LocalTime): Boolean =
        if (start <= end) t >= start && t < end else t >= start || t < end

    /** Every minute of the day (0..1439) inside the range; used to compare windows (§8). */
    fun minutes(): Set<Int> {
        val s = start.hour * 60 + start.minute
        val e = end.hour * 60 + end.minute
        return if (s <= e) (s until e).toSet() else ((s until 1440) + (0 until e)).toSet()
    }

    override fun toString() = "$start-$end"

    companion object {
        /** Parses "13:00-14:00". */
        fun parse(s: String): TimeRange {
            val (a, b) = s.trim().split("-").map { LocalTime.parse(it.trim()) }
            return TimeRange(a, b)
        }
    }
}

/**
 * Everything Ammar sets on the Rules screen (SPEC §10), with the defaults of the 21 Oct run.
 * Changes go through [Commitment] so loosenings wait 24 hours (SPEC §8).
 */
data class Settings(
    /** Drift apps (SPEC §5): only these can trigger anything. Picked from installed apps. */
    val driftApps: Set<String> = emptySet(),
    /** Always-allowed apps, on top of the built-in protected ones. */
    val allowedApps: Set<String> = emptySet(),
    val bedtime: LocalTime = LocalTime.of(23, 30),
    /** When NIGHT ends. SPEC gives no wake time; 06:00 is the default. */
    val nightEnd: LocalTime = LocalTime.of(6, 0),
    /** Meal windows = DANGER WINDOWS. Empty until Ammar adds them. */
    val mealWindows: List<TimeRange> = emptyList(),
    /** If-then plans (Level 1 text) per window; DAY is the fallback for the others. */
    val plans: Map<TimeWindow, String> = mapOf(TimeWindow.DAY to EXAMPLE_PLAN),
    /**
     * Highest ladder level allowed: 0 = logger only (Phase 0), 1 = nudge only (Phase 2),
     * 3 = full ladder (Phase 3). Starts at 0, so a fresh install only logs.
     */
    val maxLevel: Int = 0,
    /** Level 2 grayscale (needs the one-time adb grant of WRITE_SECURE_SETTINGS). */
    val grayscale: Boolean = false,
    /** Threshold values changed on the Rules screen; the file gives the rest. */
    val thresholdOverrides: Map<String, Double> = emptyMap(),
) {
    fun thresholds(base: Thresholds = Thresholds.DEFAULT): Thresholds = base.withAll(thresholdOverrides)

    fun planFor(window: TimeWindow): String =
        plans[window]?.takeIf { it.isNotBlank() } ?: plans[TimeWindow.DAY].orEmpty()

    companion object {
        const val EXAMPLE_PLAN = "After eating → plate to the sink, food in the fridge, then sit at the desk."
    }
}

/**
 * Sorts apps into SPEC §5 categories. [protected] apps (dialer, Settings, clock, launcher,
 * emergency, Qadam itself) are always ALLOWED, even if someone lists them as drift apps:
 * AGENTS.md forbids ever blocking Settings, the dialer, emergency calls or alarms.
 */
class AppCategorizer(
    private val drift: Set<String>,
    private val allowed: Set<String>,
    private val protected: Set<String> = ProtectedApps.BUILT_IN,
) {
    constructor(s: Settings, protected: Set<String> = ProtectedApps.BUILT_IN) :
        this(s.driftApps, s.allowedApps, protected)

    fun of(pkg: String?): AppCategory? = when {
        pkg == null -> null
        pkg in protected || pkg in allowed -> AppCategory.ALLOWED
        pkg in drift -> AppCategory.DRIFT
        else -> AppCategory.NEUTRAL
    }

    fun isDrift(pkg: String?) = of(pkg) == AppCategory.DRIFT
}

/** Packages that are never blocked, whatever the settings say. The app adds the phone's own
 * default dialer, SMS app, clock, launcher and Settings at runtime (they differ per maker). */
object ProtectedApps {
    val BUILT_IN: Set<String> = setOf(
        "de.ammar.qadam",
        "android",
        "com.android.settings",
        "com.android.systemui",
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.server.telecom",
        "com.android.incallui",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    )
}

/** Which part of the day a moment falls in (SPEC §6: NIGHT, DANGER WINDOW). NIGHT wins. */
class DayWindows(
    private val bedtime: LocalTime,
    private val nightEnd: LocalTime,
    private val meals: List<TimeRange>,
) {
    constructor(s: Settings) : this(s.bedtime, s.nightEnd, s.mealWindows)

    private val night = TimeRange(bedtime, nightEnd)

    fun at(t: LocalTime): TimeWindow = when {
        bedtime != nightEnd && t in night -> TimeWindow.NIGHT
        meals.any { t in it } -> TimeWindow.MEAL
        else -> TimeWindow.DAY
    }
}

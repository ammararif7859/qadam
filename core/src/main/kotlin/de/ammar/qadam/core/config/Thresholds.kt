package de.ammar.qadam.core.config

import java.io.StringReader
import java.util.Properties

/**
 * All tunable numbers (SPEC §4, §6-§8), read from `qadam/thresholds.properties`.
 *
 * The file holds the defaults. The Rules screen may override single values;
 * [Knobs] says which direction of change is a loosening (SPEC §8).
 */
class Thresholds private constructor(private val values: Map<String, Double>) {

    operator fun get(key: String): Double =
        values[key] ?: throw IllegalArgumentException("Unknown threshold '$key'")

    fun keys(): Set<String> = values.keys

    fun asMap(): Map<String, Double> = values

    /** A copy with one value changed. Only known keys are accepted. */
    fun with(key: String, value: Double): Thresholds {
        require(key in values) { "Unknown threshold '$key'" }
        require(value >= 0.0 && value.isFinite()) { "Threshold '$key' must be >= 0, got $value" }
        return Thresholds(values + (key to value))
    }

    /** A copy with several values changed (used to apply stored overrides). Unknown keys are ignored. */
    fun withAll(overrides: Map<String, Double>): Thresholds =
        overrides.entries.fold(this) { t, (k, v) -> if (k in values) t.with(k, v) else t }

    /**
     * Converts a duration key to milliseconds. The unit is the key's last word:
     * `drift_screen.min` and `chore.duration_min` are minutes, `drift_screen.break_s` seconds.
     */
    fun ms(key: String): Long {
        val v = get(key)
        val factor = when (key.split('.', '_').last()) {
            "min" -> 60_000.0
            "s" -> 1_000.0
            "h" -> 3_600_000.0
            else -> throw IllegalArgumentException("'$key' is not a duration")
        }
        return Math.round(v * factor)
    }

    fun int(key: String): Int = Math.round(get(key)).toInt()

    // ---- §6 States ----
    val choreDurationMs get() = ms("chore.duration_min")
    val choreCancelMs get() = ms("chore.cancel_min")
    val choreCancelScrollsPerMin get() = int("chore.cancel_scrolls_per_min")
    val movingSteps get() = int("moving.steps")
    val movingWindowMs get() = ms("moving.window_min")
    val driftScreenMs get() = ms("drift_screen.min")
    val driftScreenMaxSteps get() = int("drift_screen.max_steps")
    val driftScreenStepsWindowMs get() = ms("drift_screen.steps_window_min")
    val driftBreakMs get() = ms("drift_screen.break_s")
    val driftScrollPerMin get() = get("drift_scroll.per_min")
    val driftScrollMs get() = ms("drift_scroll.min")
    val driftAudioMs get() = ms("drift_audio.min")
    val driftAudioMaxSteps get() = int("drift_audio.max_steps")
    val dangerFactor get() = get("danger.factor")
    val resetSteps get() = int("reset.steps")
    val resetStepsWindowMs get() = ms("reset.steps_window_min")
    val resetScreenOffMs get() = ms("reset.screen_off_min")

    // ---- §7 Ladder ----
    val gettingUpGraceMs get() = ms("level1.getting_up_grace_min")
    val gettingUpSteps get() = int("level1.getting_up_steps")
    val moreMs get() = ms("level1.more_min")
    val moreEveryMs get() = ms("level1.more_every_min")
    val wrongSnoozeMs get() = ms("level1.wrong_snooze_min")
    val level2AfterLevel1Ms get() = ms("level2.after_level1_min")
    val secondTriggerMs get() = ms("level2.second_trigger_min")
    val level2GrantMs get() = ms("level2.grant_min")
    val level2MinWords get() = int("level2.min_words")
    val level3AfterLevel2Ms get() = ms("level3.after_level2_min")
    val level3BlockMs get() = ms("level3.block_min")
    val overrideHoldMs get() = ms("level3.override_hold_s")
    val overridesPerDay get() = int("level3.override_per_day")

    // ---- §8 Commitment ----
    val pauseMs get() = ms("pause.min")
    val pausesPerDay get() = int("pause.per_day")

    override fun equals(other: Any?) = other is Thresholds && other.values == values
    override fun hashCode() = values.hashCode()
    override fun toString() = "Thresholds($values)"

    companion object {
        const val RESOURCE = "/qadam/thresholds.properties"

        /** Parses the properties format. Every value must be a number >= 0. */
        fun parse(text: String): Thresholds {
            val p = Properties().apply { load(StringReader(text)) }
            val map = p.stringPropertyNames().associateWith { key ->
                val raw = p.getProperty(key).trim()
                val v = raw.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Threshold '$key' is not a number: '$raw'")
                require(v >= 0.0) { "Threshold '$key' must be >= 0, got $v" }
                v
            }
            val factor = map["danger.factor"]
            require(factor == null || (factor > 0.0 && factor <= 1.0)) {
                "danger.factor must be in (0, 1], got $factor"
            }
            return Thresholds(map)
        }

        /** The defaults shipped in the file. */
        val DEFAULT: Thresholds by lazy {
            val stream = Thresholds::class.java.getResourceAsStream(RESOURCE)
                ?: throw IllegalStateException("Missing resource $RESOURCE")
            parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
    }
}

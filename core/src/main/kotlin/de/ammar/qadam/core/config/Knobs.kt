package de.ammar.qadam.core.config

/** Which direction of change makes a rule easier on me (a loosening, SPEC §8). */
enum class Looser {
    /** A higher value is looser. */
    HIGHER,

    /** A lower value is looser. */
    LOWER,

    /** Technical value: not editable in the app, only in the file. */
    FIXED,
}

/** One threshold as the Rules screen shows it. */
data class Knob(val key: String, val looser: Looser, val label: String)

/**
 * Every key in `thresholds.properties`, with its loosening direction and a short label.
 * A unit test checks this list against the file, so neither can drift from the other.
 */
object Knobs {
    val all: List<Knob> = listOf(
        // §6 States
        Knob("chore.duration_min", Looser.HIGHER, "Chore mode length (min)"),
        Knob("chore.cancel_min", Looser.HIGHER, "Chore mode ends after scrolling still for (min)"),
        Knob("chore.cancel_scrolls_per_min", Looser.HIGHER, "…counting as scrolling at (scrolls/min)"),
        Knob("moving.steps", Looser.LOWER, "Moving: steps needed"),
        Knob("moving.window_min", Looser.HIGHER, "Moving: within (min)"),
        Knob("drift_screen.min", Looser.HIGHER, "Drift app in front for (min)"),
        Knob("drift_screen.max_steps", Looser.LOWER, "…with fewer steps than"),
        Knob("drift_screen.steps_window_min", Looser.HIGHER, "…in the last (min)"),
        Knob("drift_screen.break_s", Looser.LOWER, "A break from the drift app lasts at least (s)"),
        Knob("drift_scroll.per_min", Looser.HIGHER, "Heavy scrolling: scrolls per minute"),
        Knob("drift_scroll.min", Looser.HIGHER, "Heavy scrolling: for (min)"),
        Knob("drift_audio.min", Looser.HIGHER, "Audio, screen off, still for (min)"),
        Knob("drift_audio.max_steps", Looser.LOWER, "…with at most this many steps"),
        Knob("danger.factor", Looser.HIGHER, "Meal windows multiply times by"),
        Knob("reset.steps", Looser.LOWER, "Reset: steps needed"),
        Knob("reset.steps_window_min", Looser.HIGHER, "Reset: within (min)"),
        Knob("reset.screen_off_min", Looser.LOWER, "Reset: screen off, no audio, for (min)"),
        // §7 Ladder
        Knob("level1.getting_up_grace_min", Looser.HIGHER, "\"Getting up\": steps must start within (min)"),
        Knob("level1.getting_up_steps", Looser.LOWER, "\"Getting up\": steps needed"),
        Knob("level1.more_min", Looser.HIGHER, "\"5 more minutes\" length (min)"),
        Knob("level1.more_every_min", Looser.LOWER, "\"5 more minutes\" at most once per (min)"),
        Knob("level1.wrong_snooze_min", Looser.HIGHER, "\"Wrong\" snooze (min)"),
        Knob("level2.after_level1_min", Looser.HIGHER, "Level 2 after Level 1 changed nothing for (min)"),
        Knob("level2.second_trigger_min", Looser.LOWER, "Level 2 at a second trigger within (min)"),
        Knob("level2.grant_min", Looser.HIGHER, "Level 2: typing an action buys (min)"),
        Knob("level2.min_words", Looser.LOWER, "Level 2: words needed"),
        Knob("level3.after_level2_min", Looser.HIGHER, "Level 3 after Level 2 for (min)"),
        Knob("level3.block_min", Looser.LOWER, "Level 3 block length (min)"),
        Knob("level3.override_hold_s", Looser.LOWER, "Override: hold for (s)"),
        Knob("level3.override_per_day", Looser.HIGHER, "Overrides per day"),
        // §8 Commitment
        Knob("pause.min", Looser.HIGHER, "Pause length (min)"),
        Knob("pause.per_day", Looser.HIGHER, "Pauses per day"),
        // §4 Sensors and logging (technical, fixed)
        Knob("sensors.rate_hz", Looser.FIXED, "Accelerometer rate (Hz)"),
        Knob("motion.window_s", Looser.FIXED, "Motion RMS window (s)"),
        Knob("gravity.tau_s", Looser.FIXED, "Gravity filter time constant (s)"),
        Knob("summary.active_s", Looser.FIXED, "Summary row, screen on or audio (s)"),
        Knob("summary.idle_s", Looser.FIXED, "Summary row, otherwise (s)"),
        Knob("proximity.near_cm", Looser.FIXED, "Proximity near below (cm)"),
        Knob("step.min_hz", Looser.FIXED, "Step rhythm min (per s)"),
        Knob("step.max_hz", Looser.FIXED, "Step rhythm max (per s)"),
        Knob("step.min_peak", Looser.FIXED, "Step peak height (m/s²)"),
        Knob("step.min_run", Looser.FIXED, "Steps in a row before counting"),
        Knob("step.smooth_s", Looser.FIXED, "Step smoothing (s)"),
        Knob("sampling.per_day", Looser.FIXED, "Sampling prompts per day"),
        Knob("sampling.start_hour", Looser.FIXED, "Sampling starts at hour"),
        Knob("sampling.min_gap_min", Looser.FIXED, "Sampling gap (min)"),
        Knob("sampling.wait_min", Looser.FIXED, "Sampling waits for screen (min)"),
    )

    private val byKey = all.associateBy { it.key }

    fun of(key: String): Knob = byKey[key] ?: throw IllegalArgumentException("Unknown threshold '$key'")

    /** Knobs the Rules screen may edit. */
    val editable: List<Knob> get() = all.filter { it.looser != Looser.FIXED }

    /** True if moving [key] from [old] to [new] is a loosening (it must wait 24 h). */
    fun isLoosening(key: String, old: Double, new: Double): Boolean = when (of(key).looser) {
        Looser.HIGHER -> new > old
        Looser.LOWER -> new < old
        Looser.FIXED -> true
    }
}

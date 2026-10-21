package de.ammar.qadam.core.model

/** SPEC §5: what kind of app is in front. */
enum class AppCategory {
    /** My list. Only these can trigger anything. */
    DRIFT,

    /** Never blocked: phone, messengers, clock, maps, banking, study apps, Qadam, Settings. */
    ALLOWED,

    /** Everything else. Logged, never blocked. */
    NEUTRAL,
}

/** SPEC §6: the state the detector is in. One value per moment, for the CSV `state` column. */
enum class DriftState {
    /** Nothing special. */
    IDLE,

    /** Chore mode: 30 min after a tag tap. Nothing fires. */
    CHORE,

    /** Walking: enough steps lately. Nothing fires. */
    MOVING,

    /** A drift app in front for long, body still. */
    DRIFT_SCREEN,

    /** Heavy scrolling in a drift app. */
    DRIFT_SCROLL,

    /** Audio playing, screen off, body still. */
    DRIFT_AUDIO,

    /** After bedtime with a drift app in front: starts at Level 2. */
    NIGHT,
}

/** Which part of the day it is (CSV `window` column). */
enum class TimeWindow {
    DAY,

    /** A meal window — the DANGER WINDOW of SPEC §6: time thresholds halved. */
    MEAL,

    /** Bedtime until night end. */
    NIGHT,
}

/** SPEC §11: the answers to the experience-sampling question "Right now: …". */
enum class Label(val csv: String, val text: String) {
    CHORES("chores", "Chores"),
    STUDYING("studying", "Studying"),
    EATING("eating", "Eating"),
    RESTING("resting", "Resting on purpose"),
    DRIFTING("drifting", "Drifting"),
    OTHER("other", "Other"),
    ;

    companion object {
        fun fromCsv(s: String): Label? = entries.firstOrNull { it.csv == s }
    }
}

/**
 * SPEC §4: one summary row. Written every 30 s while the screen is on or audio plays,
 * every 5 min otherwise. Never raw samples, never screen content.
 * Nullable numbers are "no reading" (sensor off or missing) and become empty CSV cells.
 */
data class SummaryRow(
    val timeMs: Long,
    val screenOn: Boolean,
    val app: String?,
    val appCategory: AppCategory?,
    val sessionS: Int,
    val scrolls: Int,
    val taps: Int,
    val steps: Int,
    val stepsLast5min: Int,
    val motionRmsMean: Double?,
    val motionRmsP90: Double?,
    val gravityX: Double?,
    val gravityY: Double?,
    val gravityZ: Double?,
    val proximityNear: Double?,
    val lux: Double?,
    val audio: Boolean,
    val headphones: Boolean,
    val charging: Boolean,
    val choreMode: Boolean,
    val window: TimeWindow,
    val state: DriftState,
    val level: Int,
    val label: Label?,
)

/** SPEC §4: kinds of event rows. */
enum class EventType {
    SERVICE_START,
    SERVICE_STOP,
    /** The service found a gap since its last heartbeat (it was killed or switched off). */
    SERVICE_GAP,
    ACCESSIBILITY_ON,
    ACCESSIBILITY_OFF,
    TAG_TAP,
    TAG_WRITTEN,
    SAMPLING_PROMPT,
    LABEL,
    INTERVENTION,
    RESPONSE,
    OVERRIDE,
    PAUSE,
    SETTING_CHANGE,
    SETTING_PENDING,
    SETTING_APPLIED,
    SENSOR_CHECK,
    DATA_DELETED,
}

/** One event row: what happened, when, and a short detail (never screen content). */
data class EventRow(val timeMs: Long, val type: EventType, val detail: String = "")

package de.ammar.qadam.core.engine

/**
 * Everything the engine must remember across ticks — and across a restart of the app,
 * so killing Qadam can't lift a Level 3 block. The app stores [encode] in its database
 * after every change and passes [decode] back in when the service starts.
 *
 * Times are wall-clock milliseconds; null means "not set".
 */
data class EngineState(
    val level: Int = 0,
    val levelSinceMs: Long = 0,
    val kind: TriggerKind? = null,
    /** Level 1 card on screen, waiting for a button. */
    val cardOpen: Boolean = false,
    /** Level 1 "Getting up" pressed at this time; steps must follow. */
    val gettingUpAtMs: Long? = null,
    /** No triggers before this (after "5 more minutes" or "Wrong"). */
    val snoozeUntilMs: Long? = null,
    val lastMoreMs: Long? = null,
    /** Last time a rule fired; a second trigger within 30 min goes straight to Level 2. */
    val lastTriggerMs: Long? = null,
    /** Level 2: next action typed; overlay hidden until this time. */
    val grantUntilMs: Long? = null,
    /** Level 2: steps started, so audio may play again. */
    val audioReleased: Boolean = false,
    /** Level 3: drift apps blocked until this time. */
    val blockUntilMs: Long? = null,
    /** Drift timers count from here at the earliest (moved forward by every reset). */
    val driftClockMs: Long = 0,
    val choreUntilMs: Long? = null,
    val choreSinceMs: Long? = null,
    val pauseUntilMs: Long? = null,
    /** Local date (ISO) the daily counters belong to. */
    val day: String = "",
    val overridesToday: Int = 0,
    val pausesToday: Int = 0,
    /** Start of the current unbroken drift-app session. */
    val sessionStartMs: Long? = null,
    /** When the drift app last left the front (a break needs 60 s). */
    val awaySinceMs: Long? = null,
    /** Audio on, screen off, attributed to a drift app, since this time. */
    val audioStillSinceMs: Long? = null,
    /** Screen off and no audio since this time. */
    val quietSinceMs: Long? = null,
    /** The last app seen in front while the screen was on (audio is credited to it). */
    val lastScreenApp: String? = null,
) {
    fun encode(): String = buildList {
        add("level=$level")
        add("levelSinceMs=$levelSinceMs")
        add("kind=${kind?.name ?: ""}")
        add("cardOpen=$cardOpen")
        add("gettingUpAtMs=${gettingUpAtMs ?: ""}")
        add("snoozeUntilMs=${snoozeUntilMs ?: ""}")
        add("lastMoreMs=${lastMoreMs ?: ""}")
        add("lastTriggerMs=${lastTriggerMs ?: ""}")
        add("grantUntilMs=${grantUntilMs ?: ""}")
        add("audioReleased=$audioReleased")
        add("blockUntilMs=${blockUntilMs ?: ""}")
        add("driftClockMs=$driftClockMs")
        add("choreUntilMs=${choreUntilMs ?: ""}")
        add("choreSinceMs=${choreSinceMs ?: ""}")
        add("pauseUntilMs=${pauseUntilMs ?: ""}")
        add("day=$day")
        add("overridesToday=$overridesToday")
        add("pausesToday=$pausesToday")
        add("sessionStartMs=${sessionStartMs ?: ""}")
        add("awaySinceMs=${awaySinceMs ?: ""}")
        add("audioStillSinceMs=${audioStillSinceMs ?: ""}")
        add("quietSinceMs=${quietSinceMs ?: ""}")
        add("lastScreenApp=${lastScreenApp ?: ""}")
    }.joinToString(";")

    companion object {
        /** Reads [encode]'s format. Unknown or broken input gives a fresh state. */
        fun decode(s: String?): EngineState {
            if (s.isNullOrBlank()) return EngineState()
            return try {
                val m = s.split(";").filter { "=" in it }.associate {
                    val i = it.indexOf('='); it.substring(0, i) to it.substring(i + 1)
                }
                fun l(k: String) = m[k]?.takeIf { it.isNotEmpty() }?.toLong()
                fun i(k: String) = m[k]?.takeIf { it.isNotEmpty() }?.toInt() ?: 0
                fun b(k: String) = m[k] == "true"
                EngineState(
                    level = i("level"),
                    levelSinceMs = l("levelSinceMs") ?: 0,
                    kind = m["kind"]?.takeIf { it.isNotEmpty() }?.let { TriggerKind.valueOf(it) },
                    cardOpen = b("cardOpen"),
                    gettingUpAtMs = l("gettingUpAtMs"),
                    snoozeUntilMs = l("snoozeUntilMs"),
                    lastMoreMs = l("lastMoreMs"),
                    lastTriggerMs = l("lastTriggerMs"),
                    grantUntilMs = l("grantUntilMs"),
                    audioReleased = b("audioReleased"),
                    blockUntilMs = l("blockUntilMs"),
                    driftClockMs = l("driftClockMs") ?: 0,
                    choreUntilMs = l("choreUntilMs"),
                    choreSinceMs = l("choreSinceMs"),
                    pauseUntilMs = l("pauseUntilMs"),
                    day = m["day"].orEmpty(),
                    overridesToday = i("overridesToday"),
                    pausesToday = i("pausesToday"),
                    sessionStartMs = l("sessionStartMs"),
                    awaySinceMs = l("awaySinceMs"),
                    audioStillSinceMs = l("audioStillSinceMs"),
                    quietSinceMs = l("quietSinceMs"),
                    lastScreenApp = m["lastScreenApp"]?.takeIf { it.isNotEmpty() },
                )
            } catch (e: IllegalArgumentException) {
                EngineState()
            }
        }
    }
}

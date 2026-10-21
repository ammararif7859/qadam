package de.ammar.qadam.core.engine

import de.ammar.qadam.core.config.Thresholds
import de.ammar.qadam.core.model.DriftState
import de.ammar.qadam.core.model.EventRow
import de.ammar.qadam.core.model.EventType
import de.ammar.qadam.core.model.TimeWindow
import de.ammar.qadam.core.rules.AppCategorizer
import de.ammar.qadam.core.rules.DayWindows
import de.ammar.qadam.core.rules.ProtectedApps
import de.ammar.qadam.core.rules.Settings
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The rules of SPEC §6 (states) and §7 (the ladder), as one state machine.
 *
 * The service calls [tick] every few seconds with what the phone is doing, and [act] when
 * Ammar presses a button or taps a tag. Each call returns an [Output]: the current state
 * and level, what to draw, audio and grayscale wishes, one-off actions and event rows.
 *
 * Nothing here touches Android. Time comes in with every call, so tests can replay hours
 * of synthetic behaviour in milliseconds.
 */
class Engine(
    settings: Settings,
    private val zone: ZoneId,
    private val protectedApps: Set<String> = ProtectedApps.BUILT_IN,
    initial: EngineState = EngineState(),
    private val baseThresholds: Thresholds = Thresholds.DEFAULT,
) {
    var state: EngineState = initial
        private set

    var settings: Settings = settings
        private set

    private var t: Thresholds = settings.thresholds(baseThresholds)
    private var categorizer = AppCategorizer(settings, protectedApps)
    private var windows = DayWindows(settings)

    /** The last few minutes of ticks, for counting steps and scrolls in a window. */
    private val history = ArrayDeque<Rec>()
    private var lastTick: Tick? = null

    /** Set when the ladder changed level during the current tick. */
    private var moved = false

    private class Rec(
        val timeMs: Long,
        val screenOn: Boolean,
        val scrollsAll: Int,
        val scrollsDrift: Int,
        val steps: Int,
    )

    /** Use new settings from now on (after an immediate change or a matured pending one). */
    fun updateSettings(s: Settings) {
        settings = s
        t = s.thresholds(baseThresholds)
        categorizer = AppCategorizer(s, protectedApps)
        windows = DayWindows(s)
        if (s.maxLevel < state.level) {
            // Levels were switched off (after the 24-hour wait): leave the higher level.
            state = cleared(state)
        }
    }

    // ------------------------------------------------------------------ ticks

    fun tick(tk: Tick): Output {
        val events = mutableListOf<EventRow>()
        val shots = mutableListOf<OneShot>()
        val now = tk.timeMs
        lastTick = tk
        rollDay(now)

        val isDrift = tk.screenOn && categorizer.isDrift(tk.app)
        history.addLast(
            Rec(now, tk.screenOn, tk.scrolls, if (isDrift) tk.scrolls else 0, tk.steps),
        )
        while (history.isNotEmpty() && history.first().timeMs < now - HISTORY_MS) history.removeFirst()

        // Running "since" markers for conditions that must hold without a break.
        var s = state
        if (tk.screenOn && tk.app != null) s = s.copy(lastScreenApp = tk.app)
        s = if (isDrift) {
            s.copy(sessionStartMs = s.sessionStartMs ?: now, awaySinceMs = null)
        } else {
            val away = s.awaySinceMs ?: now
            s.copy(awaySinceMs = away, sessionStartMs = if (now - away >= t.driftBreakMs) null else s.sessionStartMs)
        }
        val audioCond = tk.audio && !tk.screenOn && categorizer.isDrift(s.lastScreenApp)
        s = s.copy(audioStillSinceMs = if (audioCond) s.audioStillSinceMs ?: now else null)
        s = s.copy(quietSinceMs = if (!tk.screenOn && !tk.audio) s.quietSinceMs ?: now else null)
        state = s

        val window = windowAt(now)
        val f = if (window == TimeWindow.MEAL) t.dangerFactor else 1.0

        // CHORE: on after a tag tap; ends early if screen-on scrolling without steps holds 5 min.
        var chore = state.choreUntilMs?.let { now < it } == true
        if (!chore && state.choreUntilMs != null) state = state.copy(choreUntilMs = null, choreSinceMs = null)
        if (chore && choreCancelHolds(now)) {
            chore = false
            state = state.copy(choreUntilMs = null, choreSinceMs = null)
            events += EventRow(now, EventType.CHORE_END, "drift signals for ${t.choreCancelMs / 60_000} min")
        }

        val moving = steps(now, t.movingWindowMs) >= t.movingSteps
        val driftFrom = state.sessionStartMs?.let { max(it, state.driftClockMs) }
        val screenMs = (t.driftScreenMs * f).toLong()
        val scrollMs = (t.driftScrollMs * f).toLong()
        val audioMs = (t.driftAudioMs * f).toLong()

        val screenCond = isDrift && driftFrom != null && now - driftFrom >= screenMs &&
            steps(now, t.driftScreenStepsWindowMs) < t.driftScreenMaxSteps
        val scrollCond = isDrift && driftFrom != null && now - driftFrom >= scrollMs &&
            scrollSustained(now, scrollMs)
        val audioFrom = state.audioStillSinceMs?.let { max(it, state.driftClockMs) }
        val audioDrift = audioFrom != null && now - audioFrom >= audioMs &&
            steps(now, audioMs) <= t.driftAudioMaxSteps
        val nightCond = window == TimeWindow.NIGHT && isDrift

        val driftState = when {
            chore -> DriftState.CHORE
            moving -> DriftState.MOVING
            nightCond -> DriftState.NIGHT
            scrollCond -> DriftState.DRIFT_SCROLL
            screenCond -> DriftState.DRIFT_SCREEN
            audioDrift -> DriftState.DRIFT_AUDIO
            else -> DriftState.IDLE
        }

        val paused = state.pauseUntilMs?.let { now < it } == true
        val snoozed = state.snoozeUntilMs?.let { now < it } == true
        // In a drift context: the drift-app session hasn't had a real break (a glance at the
        // notification shade doesn't count), or audio plays with the screen off.
        val driftContext = (tk.screenOn && state.sessionStartMs != null) || (tk.audio && !tk.screenOn)
        // Still drifting: in that context and not walking. Walking only pauses escalation;
        // the reset comes at 50 steps.
        val stillDrifting = driftContext && !moving && !chore
        moved = false

        // ---- the ladder ----
        when (state.level) {
            3 -> {
                // Only a tag, the override, or the end of the block lifts Level 3.
                if (now >= (state.blockUntilMs ?: 0)) {
                    toLevel0(now, "block ended", events, resetClock = true)
                    state = state.copy(lastTriggerMs = now)
                }
            }
            1, 2 -> {
                val reset = when {
                    steps(now, t.resetStepsWindowMs) >= t.resetSteps -> "${t.resetSteps} steps"
                    state.quietSinceMs?.let { now - it >= t.resetScreenOffMs } == true ->
                        "screen off ${t.resetScreenOffMs / 60_000} min"
                    chore -> "chore mode"
                    paused -> "paused"
                    else -> null
                }
                if (reset != null) toLevel0(now, reset, events, resetClock = true)
            }
        }

        if (!moved && state.level == 1) {
            val upAt = state.gettingUpAtMs
            when {
                upAt != null -> {
                    if (steps(now, now - upAt) >= t.gettingUpSteps) {
                        toLevel0(now, "got up", events, resetClock = true)
                    } else if (now - upAt >= t.gettingUpGraceMs) {
                        escalate(2, now, tk, events, shots, "no steps after Getting up")
                    }
                }
                state.cardOpen && !driftContext -> toLevel0(now, "drift ended", events, resetClock = false)
                state.cardOpen && stillDrifting && now - state.levelSinceMs >= t.level2AfterLevel1Ms ->
                    escalate(2, now, tk, events, shots, "Level 1 changed nothing")
            }
        }

        if (!moved && state.level == 2) {
            if (!state.audioReleased && steps(now, AUDIO_STEPS_WINDOW_MS) >= t.gettingUpSteps) {
                state = state.copy(audioReleased = true)
            }
            val deadline = max(state.levelSinceMs + t.level3AfterLevel2Ms, state.grantUntilMs ?: 0)
            if (now >= deadline && stillDrifting && !tk.inCall) {
                escalate(3, now, tk, events, shots, "Level 2 changed nothing")
            }
        }

        // At most one ladder move per tick: a reset is never followed by a trigger in the same tick.
        if (!moved && state.level == 0 && settings.maxLevel >= 1 && !paused && !snoozed && !chore && !moving && !tk.inCall) {
            val kind = when {
                nightCond -> TriggerKind.NIGHT
                scrollCond -> TriggerKind.SCROLL
                screenCond -> TriggerKind.SCREEN
                audioDrift -> TriggerKind.AUDIO
                else -> null
            }
            if (kind != null) {
                val second = state.lastTriggerMs?.let { now - it <= t.secondTriggerMs } == true
                val target = if (kind == TriggerKind.NIGHT || second) 2 else 1
                state = state.copy(lastTriggerMs = now, kind = kind)
                enter(minOf(target, settings.maxLevel), kind, now, tk, events, shots, if (second) "second trigger" else "")
            }
        }

        return render(now, driftState, window, chore, shots, events)
    }

    // ---------------------------------------------------------------- actions

    /** Whether "5 more minutes" is allowed now (at most once per hour). */
    fun canMore(now: Long): Boolean = state.lastMoreMs?.let { now - it >= t.moreEveryMs } ?: true

    fun overridesLeft(): Int = (t.overridesPerDay - state.overridesToday).coerceAtLeast(0)

    fun pausesLeft(): Int = (t.pausesPerDay - state.pausesToday).coerceAtLeast(0)

    fun act(a: Action, now: Long): ActionResult {
        rollDay(now)
        val events = mutableListOf<EventRow>()
        val shots = mutableListOf<OneShot>()
        var reason = ""
        val ok: Boolean = when (a) {
            Action.GettingUp -> if (state.level == 1 && state.cardOpen) {
                state = state.copy(cardOpen = false, gettingUpAtMs = now)
                events += EventRow(now, EventType.RESPONSE, "getting_up")
                true
            } else false.also { reason = "no Level 1 card" }

            Action.MoreMinutes -> when {
                state.level != 1 || !state.cardOpen -> false.also { reason = "no Level 1 card" }
                !canMore(now) -> false.also { reason = "already used within the hour" }
                else -> {
                    events += EventRow(now, EventType.RESPONSE, "more_minutes")
                    toLevel0(now, "5 more minutes", events, resetClock = false)
                    state = state.copy(lastMoreMs = now, snoozeUntilMs = now + t.moreMs)
                    true
                }
            }

            Action.Wrong -> if (state.level == 1 && state.cardOpen) {
                events += EventRow(now, EventType.RESPONSE, "wrong (false positive, ${state.kind?.name ?: "?"})")
                toLevel0(now, "marked wrong", events, resetClock = true)
                state = state.copy(snoozeUntilMs = now + t.wrongSnoozeMs, lastTriggerMs = null)
                true
            } else false.also { reason = "no Level 1 card" }

            is Action.NextAction -> {
                val words = a.text.trim().split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }
                when {
                    state.level != 2 -> false.also { reason = "not at Level 2" }
                    state.grantUntilMs != null -> false.also { reason = "already granted" }
                    words < t.level2MinWords -> false.also { reason = "need at least ${t.level2MinWords} words" }
                    else -> {
                        state = state.copy(grantUntilMs = now + t.level2GrantMs)
                        events += EventRow(now, EventType.RESPONSE, "next_action: ${a.text.trim()}")
                        true
                    }
                }
            }

            is Action.TagTapped -> {
                val until = max(state.choreUntilMs ?: 0, now + t.choreDurationMs)
                val unlocked = state.level
                state = state.copy(choreUntilMs = until, choreSinceMs = state.choreSinceMs ?: now)
                events += EventRow(now, EventType.TAG_TAP, a.name + if (unlocked == 3) " (unlocked Level 3)" else "")
                if (unlocked > 0) {
                    toLevel0(now, "tag ${a.name}", events, resetClock = true)
                    state = state.copy(lastTriggerMs = null)
                }
                true
            }

            is Action.Override -> when {
                state.level != 3 -> false.also { reason = "not at Level 3" }
                a.heldMs < t.overrideHoldMs -> false.also { reason = "hold for ${t.overrideHoldMs / 1000} s" }
                a.reason.isBlank() -> false.also { reason = "type a reason" }
                overridesLeft() <= 0 -> false.also { reason = "no overrides left today" }
                else -> {
                    state = state.copy(overridesToday = state.overridesToday + 1)
                    events += EventRow(now, EventType.OVERRIDE, a.reason.trim())
                    toLevel0(now, "override", events, resetClock = true)
                    state = state.copy(lastTriggerMs = now)
                    true
                }
            }

            Action.Pause -> when {
                state.level == 3 -> false.also { reason = "not during a Level 3 block" }
                pausesLeft() <= 0 -> false.also { reason = "already paused today" }
                else -> {
                    state = state.copy(pausesToday = state.pausesToday + 1, pauseUntilMs = now + t.pauseMs)
                    events += EventRow(now, EventType.PAUSE, "${t.pauseMs / 60_000} min")
                    if (state.level > 0) toLevel0(now, "paused", events, resetClock = false)
                    true
                }
            }
        }
        val tk = lastTick
        val window = windowAt(now)
        val chore = state.choreUntilMs?.let { now < it } == true
        val out = render(now, if (chore) DriftState.CHORE else DriftState.IDLE, window, chore, shots, events, tk)
        return ActionResult(ok, out, reason)
    }

    /**
     * A long pause that waited out the 24-hour rule (SPEC §8) has matured: no triggers
     * until [untilMs]. Not during a Level 3 block.
     */
    fun startLongPause(now: Long, untilMs: Long): List<EventRow> {
        if (state.level == 3) return emptyList()
        val ev = mutableListOf(EventRow(now, EventType.PAUSE, "long pause until $untilMs"))
        state = state.copy(pauseUntilMs = max(state.pauseUntilMs ?: 0, untilMs))
        if (state.level > 0) toLevel0(now, "paused", ev, resetClock = false)
        return ev
    }

    // ---------------------------------------------------------------- helpers

    private fun windowAt(now: Long): TimeWindow =
        windows.at(Instant.ofEpochMilli(now).atZone(zone).toLocalTime())

    private fun rollDay(now: Long) {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        if (day != state.day) state = state.copy(day = day, overridesToday = 0, pausesToday = 0)
    }

    /** Steps with time in (now − window, now]. */
    private fun steps(now: Long, windowMs: Long): Int =
        history.filter { it.timeMs > now - windowMs && it.timeMs <= now }.sumOf { it.steps }

    /** True if the history reaches back to [fromMs] (with one tick of slack). */
    private fun covers(fromMs: Long): Boolean =
        history.isNotEmpty() && history.first().timeMs <= fromMs + COVER_SLACK_MS

    /**
     * DRIFT_SCROLL: "≥ 6 scrolls per minute for 3 min" — every minute of the window
     * (in a drift app) has at least the rate. A shortened window (meal times) is split
     * into proportionally shorter parts.
     */
    private fun scrollSustained(now: Long, durMs: Long): Boolean {
        if (!covers(now - durMs)) return false
        val parts = max(1, (durMs / 60_000.0).roundToInt())
        val partMs = durMs / parts
        val need = t.driftScrollPerMin * partMs / 60_000.0
        return (0 until parts).all { i ->
            val end = now - i * partMs
            val start = end - partMs
            history.filter { it.timeMs > start && it.timeMs <= end }.sumOf { it.scrollsDrift } >= need
        }
    }

    /** CHORE ends early: screen on, scrolling every minute, no steps — for 5 min, inside chore mode. */
    private fun choreCancelHolds(now: Long): Boolean {
        val dur = t.choreCancelMs
        val from = now - dur
        if (from < (state.choreSinceMs ?: Long.MAX_VALUE) || !covers(from)) return false
        val recent = history.filter { it.timeMs > from }
        if (recent.isEmpty() || recent.any { !it.screenOn } || recent.sumOf { it.steps } > 0) return false
        val parts = max(1, (dur / 60_000.0).roundToInt())
        val partMs = dur / parts
        return (0 until parts).all { i ->
            val end = now - i * partMs
            recent.filter { it.timeMs > end - partMs && it.timeMs <= end }.sumOf { it.scrollsAll } >=
                t.choreCancelScrollsPerMin
        }
    }

    private fun enter(
        level: Int,
        kind: TriggerKind,
        now: Long,
        tk: Tick,
        events: MutableList<EventRow>,
        shots: MutableList<OneShot>,
        why: String,
    ) {
        if (level <= 0) return
        moved = true
        val plan = settings.planFor(windowAt(now))
        when (level) {
            1 -> {
                state = state.copy(level = 1, levelSinceMs = now, kind = kind, cardOpen = true, gettingUpAtMs = null)
                shots += OneShot.Vibrate
                if (!tk.screenOn) shots += OneShot.NudgeNotification(plan, canMore(now))
            }
            2 -> {
                state = state.copy(
                    level = 2, levelSinceMs = now, kind = kind, cardOpen = false, gettingUpAtMs = null,
                    grantUntilMs = null, audioReleased = false,
                )
                shots += OneShot.Vibrate
            }
            else -> {
                state = state.copy(
                    level = 3, levelSinceMs = now, kind = kind, cardOpen = false, gettingUpAtMs = null,
                    grantUntilMs = null, blockUntilMs = now + t.level3BlockMs,
                )
                shots += OneShot.StopAudio
                if (tk.screenOn) shots += OneShot.GoHome
            }
        }
        val detail = listOf("level=$level", "kind=${kind.name}", why).filter { it.isNotEmpty() }.joinToString(" ")
        events += EventRow(now, EventType.INTERVENTION, detail)
    }

    private fun escalate(
        level: Int,
        now: Long,
        tk: Tick,
        events: MutableList<EventRow>,
        shots: MutableList<OneShot>,
        why: String,
    ) {
        val kind = state.kind ?: TriggerKind.SCREEN
        if (level > settings.maxLevel) {
            // This level is switched off: show Level 1 again instead (Phase 2 runs Level 1 only).
            if (state.level == 1 && !state.cardOpen) {
                enter(1, kind, now, tk, events, shots, "$why (higher levels off)")
            }
            return
        }
        enter(level, kind, now, tk, events, shots, why)
    }

    private fun toLevel0(now: Long, why: String, events: MutableList<EventRow>, resetClock: Boolean) {
        if (state.level != 0) {
            events += EventRow(now, EventType.RESET, "from level ${state.level}: $why")
            moved = true
        }
        state = cleared(state).copy(driftClockMs = if (resetClock) now else state.driftClockMs)
    }

    private fun cleared(s: EngineState) = s.copy(
        level = 0, kind = null, cardOpen = false, gettingUpAtMs = null, grantUntilMs = null,
        audioReleased = false, blockUntilMs = null,
    )

    private fun render(
        now: Long,
        driftState: DriftState,
        window: TimeWindow,
        chore: Boolean,
        shots: List<OneShot>,
        events: List<EventRow>,
        tk: Tick? = lastTick,
    ): Output {
        val screenOn = tk != null && tk.screenOn
        val inCall = tk != null && tk.inCall
        val isDrift = tk != null && tk.screenOn && categorizer.isDrift(tk.app)
        val plan = settings.planFor(window)
        val overlay: Overlay = when {
            inCall || !isDrift -> Overlay.None
            state.level == 1 && state.cardOpen ->
                Overlay.Nudge(plan, canMore(now), state.kind ?: TriggerKind.SCREEN)
            state.level == 2 && (state.grantUntilMs?.let { now >= it } ?: true) ->
                Overlay.Friction(t.level2MinWords, plan)
            state.level == 3 -> Overlay.Block(state.blockUntilMs ?: now, overridesLeft(), t.overrideHoldMs)
            else -> Overlay.None
        }
        val audio = if (state.level == 2 && !state.audioReleased && !inCall) AudioControl.HOLD else AudioControl.FREE
        val shotsOut = if (inCall) shots.filter { it is OneShot.Vibrate } else shots
        return Output(
            state = driftState,
            window = window,
            level = state.level,
            chore = chore,
            overlay = overlay,
            audio = audio,
            grayscale = settings.grayscale && state.level >= 2,
            oneShots = shotsOut,
            events = events,
        )
    }

    private companion object {
        /** How far back the engine remembers ticks. */
        const val HISTORY_MS = 40 * 60_000L

        /** A window counts as covered if the history starts at most this late. */
        const val COVER_SLACK_MS = 15_000L

        /** Level 2: steps in this window release held audio. */
        const val AUDIO_STEPS_WINDOW_MS = 60_000L
    }
}

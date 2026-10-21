package de.ammar.qadam.core.engine

import de.ammar.qadam.core.model.DriftState
import de.ammar.qadam.core.model.EventRow
import de.ammar.qadam.core.model.TimeWindow

/**
 * One look at the phone, taken by the service every few seconds (10 s while the screen is
 * on or audio plays). Counts are "since the previous tick".
 */
data class Tick(
    val timeMs: Long,
    val screenOn: Boolean,
    /** Package of the app in front (null if unknown or the screen is off). */
    val app: String?,
    val scrolls: Int = 0,
    val steps: Int = 0,
    val audio: Boolean = false,
    /** A phone call is ringing or active: never interfere (SPEC §7 "never blocked: calls"). */
    val inCall: Boolean = false,
)

/** Which rule fired (SPEC §6). */
enum class TriggerKind { SCREEN, SCROLL, AUDIO, NIGHT }

/** What Ammar does on an overlay, or a tag tap. */
sealed interface Action {
    /** Level 1 "Getting up". */
    data object GettingUp : Action

    /** Level 1 "5 more minutes" (at most once an hour). */
    data object MoreMinutes : Action

    /** Level 1 "Wrong, I'm not drifting". */
    data object Wrong : Action

    /** Level 2: the next concrete action, at least 3 words. */
    data class NextAction(val text: String) : Action

    /** A Qadam NFC tag was tapped. */
    data class TagTapped(val name: String) : Action

    /** Level 3 hard override: the button was held [heldMs] and a reason typed. */
    data class Override(val reason: String, val heldMs: Long) : Action

    /** "Pause 1 hour" (at most once a day). */
    data object Pause : Action
}

/** What to draw over the app in front right now. */
sealed interface Overlay {
    data object None : Overlay

    /** Level 1: small card at the bottom with the if-then plan and three buttons. */
    data class Nudge(val plan: String, val canMore: Boolean, val kind: TriggerKind) : Overlay

    /** Level 2: full screen; type the next action to get a few minutes. */
    data class Friction(val minWords: Int, val plan: String) : Overlay

    /** Level 3: drift apps blocked until [untilMs]; tag or 30-second override unlocks. */
    data class Block(val untilMs: Long, val overridesLeft: Int, val holdMs: Long) : Overlay
}

/** Level 2 audio: held (paused) until steps start. */
enum class AudioControl { FREE, HOLD }

/** Things to do once, at the moment the ladder moves. */
sealed interface OneShot {
    data object Vibrate : OneShot

    /** Level 1 while the screen is off (audio drift): a notification with the three buttons. */
    data class NudgeNotification(val plan: String, val canMore: Boolean) : OneShot

    /** Level 3: take audio focus for good, so focus-respecting players pause. */
    data object StopAudio : OneShot

    /** Level 3: go to the home screen. */
    data object GoHome : OneShot
}

/** The engine's answer after every tick or action. */
data class Output(
    val state: DriftState,
    val window: TimeWindow,
    val level: Int,
    val chore: Boolean,
    val overlay: Overlay,
    val audio: AudioControl,
    val grayscale: Boolean,
    val oneShots: List<OneShot>,
    val events: List<EventRow>,
)

/** Result of an [Action]: whether it was accepted, and the new output. */
data class ActionResult(val accepted: Boolean, val output: Output, val reason: String = "")

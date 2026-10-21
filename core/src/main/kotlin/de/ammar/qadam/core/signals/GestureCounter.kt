package de.ammar.qadam.core.signals

/**
 * Turns Android's scroll events into scrolls a person would count.
 *
 * While a list moves, Android sends a scroll event about every 100 ms, so one flick of
 * the thumb arrives as a burst of 3–10 events. SPEC §6 thresholds ("6 scrolls per
 * minute") mean flicks, so a burst counts once: an event counts only if the previous
 * event came at least [gapMs] earlier.
 */
class GestureCounter(private val gapMs: Long = 400) {
    private var lastMs = Long.MIN_VALUE / 2

    /** Feeds one event at [timeMs]; returns true if it starts a new gesture. */
    fun onEvent(timeMs: Long): Boolean {
        val isNew = timeMs - lastMs >= gapMs
        lastMs = timeMs
        return isNew
    }
}

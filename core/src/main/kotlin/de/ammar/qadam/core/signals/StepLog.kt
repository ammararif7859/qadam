package de.ammar.qadam.core.signals

/**
 * Remembers when steps happened, so the rules can ask "how many steps in the last N minutes".
 * Steps come from the hardware step detector if the phone has one, else from
 * [AccelStepDetector]. Times are wall-clock milliseconds.
 */
class StepLog(private val keepMs: Long = 40 * 60_000L) {
    private val times = ArrayDeque<Long>()
    private val counts = ArrayDeque<Int>()

    fun add(timeMs: Long, n: Int = 1) {
        if (n <= 0) return
        if (times.isNotEmpty() && times.last() == timeMs) {
            counts[counts.lastIndex] = counts.last() + n
        } else {
            times.addLast(timeMs); counts.addLast(n)
        }
        prune(timeMs)
    }

    /** Steps with time in (fromMs, toMs]. */
    fun count(fromMs: Long, toMs: Long): Int {
        var sum = 0
        for (i in times.indices) {
            val t = times[i]
            if (t > fromMs && t <= toMs) sum += counts[i]
        }
        return sum
    }

    /** Steps in the [windowMs] before [nowMs]. */
    fun inLast(windowMs: Long, nowMs: Long): Int = count(nowMs - windowMs, nowMs)

    /** Time of the latest step, or null. */
    fun lastStepMs(): Long? = times.lastOrNull()

    fun prune(nowMs: Long) {
        while (times.isNotEmpty() && times.first() < nowMs - keepMs) {
            times.removeFirst(); counts.removeFirst()
        }
    }

    fun clear() {
        times.clear(); counts.clear()
    }
}

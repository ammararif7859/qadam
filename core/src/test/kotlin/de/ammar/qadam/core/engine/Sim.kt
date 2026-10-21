package de.ammar.qadam.core.engine

import de.ammar.qadam.core.model.EventRow
import de.ammar.qadam.core.model.EventType
import de.ammar.qadam.core.rules.Settings
import java.time.LocalDateTime
import java.time.ZoneId

const val PIPE = "org.polymorphicshade.pipepipe"
const val YT = "com.google.android.youtube"
const val WHATSAPP = "com.whatsapp"
const val GAME = "com.example.neutralgame"
const val DIALER = "com.android.dialer"
val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

/** Settings for most tests: two drift apps, full ladder on. */
fun testSettings(maxLevel: Int = 3, grayscale: Boolean = false) =
    Settings(driftApps = setOf(PIPE, YT), allowedApps = setOf(WHATSAPP), maxLevel = maxLevel, grayscale = grayscale)

/**
 * Drives the [Engine] with synthetic behaviour, one tick every [tickS] seconds.
 * Scroll and step rates are spread evenly over the ticks.
 */
class Sim(
    settings: Settings = testSettings(),
    start: LocalDateTime = LocalDateTime.of(2026, 11, 2, 15, 0), // a Monday afternoon, DAY window
    initial: EngineState = EngineState(),
    private val tickS: Int = 10,
) {
    val engine = Engine(settings, ZONE, initial = initial)
    val startMs: Long = start.atZone(ZONE).toInstant().toEpochMilli()
    var now: Long = startMs
        private set
    lateinit var last: Output
        private set
    val log = mutableListOf<EventRow>()
    val shots = mutableListOf<Pair<Long, OneShot>>()

    private var scrollCarry = 0.0
    private var stepCarry = 0.0

    val level get() = engine.state.level
    val elapsedS get() = (now - startMs) / 1000

    /**
     * Runs [seconds] of the same behaviour. Stops early (returning true) as soon as
     * [stopWhen] holds after a tick.
     */
    fun run(
        seconds: Int,
        screenOn: Boolean = true,
        app: String? = PIPE,
        scrollsPerMin: Double = 0.0,
        stepsPerMin: Double = 0.0,
        audio: Boolean = false,
        inCall: Boolean = false,
        stopWhen: (Output) -> Boolean = { false },
    ): Boolean {
        repeat(seconds / tickS) {
            now += tickS * 1000L
            scrollCarry += scrollsPerMin * tickS / 60.0
            stepCarry += stepsPerMin * tickS / 60.0
            val sc = scrollCarry.toInt(); scrollCarry -= sc
            val st = stepCarry.toInt(); stepCarry -= st
            last = engine.tick(
                Tick(now, screenOn, if (screenOn) app else null, sc, st, audio, inCall),
            )
            log += last.events
            last.oneShots.forEach { shots += now to it }
            if (stopWhen(last)) return true
        }
        return false
    }

    /** Runs until the ladder reaches [level]; returns the elapsed seconds, or null if it never did. */
    fun untilLevel(level: Int, maxSeconds: Int, screenOn: Boolean = true, app: String? = PIPE,
                   scrollsPerMin: Double = 0.0, stepsPerMin: Double = 0.0, audio: Boolean = false): Long? {
        val from = now
        val hit = run(maxSeconds, screenOn, app, scrollsPerMin, stepsPerMin, audio) { it.level == level }
        return if (hit) (now - from) / 1000 else null
    }

    fun act(a: Action): ActionResult {
        val r = engine.act(a, now)
        log += r.output.events
        last = r.output
        return r
    }

    fun events(type: EventType) = log.filter { it.type == type }
}

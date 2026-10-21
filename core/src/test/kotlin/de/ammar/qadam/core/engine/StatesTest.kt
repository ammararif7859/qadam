package de.ammar.qadam.core.engine

import de.ammar.qadam.core.model.DriftState
import de.ammar.qadam.core.model.EventType
import de.ammar.qadam.core.model.TimeWindow
import de.ammar.qadam.core.rules.Settings
import de.ammar.qadam.core.rules.TimeRange
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SPEC §6 — one test (or more) per state rule. */
class StatesTest {

    // ---- DRIFT_SCREEN ----

    @Test
    fun `DRIFT_SCREEN - drift app 10 min, still - Level 1 at 10 min, not before`() {
        val sim = Sim()
        assertTrue(!sim.run(590) { it.level > 0 }, "nothing before 10 min")
        val t = sim.untilLevel(1, 60)
        assertNotNull(t, "Level 1 shortly after 10 min")
        assertEquals(DriftState.DRIFT_SCREEN, sim.last.state)
        assertTrue(sim.elapsedS in 600..620, "at ${sim.elapsedS} s")
    }

    @Test
    fun `DRIFT_SCREEN - 10 or more steps in the last 5 min prevents it`() {
        // 4 steps/min: 20 per 5 min (≥ 10) but only 12 per 3 min (not MOVING).
        val sim = Sim()
        assertNull(sim.untilLevel(1, 20 * 60, stepsPerMin = 4.0))
        assertTrue(sim.last.state != DriftState.MOVING)
    }

    @Test
    fun `DRIFT_SCREEN - neutral and allowed apps never trigger`() {
        assertNull(Sim().untilLevel(1, 30 * 60, app = GAME))
        assertNull(Sim().untilLevel(1, 30 * 60, app = WHATSAPP))
    }

    @Test
    fun `DRIFT_SCREEN - a short break keeps the session, a real break restarts it`() {
        val sim = Sim()
        sim.run(8 * 60)
        sim.run(30, app = WHATSAPP) // 30 s away: no break
        val t1 = sim.untilLevel(1, 5 * 60)
        assertNotNull(t1)
        assertTrue(t1 <= 2 * 60, "session kept: Level 1 about 2 min later, got $t1 s")

        val sim2 = Sim()
        sim2.run(8 * 60)
        sim2.run(120, app = WHATSAPP) // 2 min away: break
        val t2 = sim2.untilLevel(1, 15 * 60)
        assertNotNull(t2)
        assertTrue(t2 >= 9 * 60, "session restarted: full 10 min again, got $t2 s")
    }

    @Test
    fun `switching between two drift apps is one session`() {
        val sim = Sim()
        sim.run(6 * 60, app = PIPE)
        val t = sim.untilLevel(1, 10 * 60, app = YT)
        assertNotNull(t)
        assertTrue(t <= 5 * 60, "got $t s")
    }

    // ---- DRIFT_SCROLL ----

    @Test
    fun `DRIFT_SCROLL - 6 or more scrolls per minute for 3 min reaches Level 1 sooner`() {
        val sim = Sim()
        val t = sim.untilLevel(1, 10 * 60, scrollsPerMin = 8.0)
        assertNotNull(t)
        assertTrue(t in 180..200, "at $t s")
        assertEquals(DriftState.DRIFT_SCROLL, sim.last.state)
        assertTrue(sim.events(EventType.INTERVENTION).single().detail.contains("SCROLL"))
    }

    @Test
    fun `DRIFT_SCROLL - 5 scrolls per minute is not heavy scrolling`() {
        val sim = Sim()
        val t = sim.untilLevel(1, 15 * 60, scrollsPerMin = 5.0)
        assertNotNull(t)
        assertTrue(t >= 600, "only the 10-minute rule fires, at $t s")
    }

    @Test
    fun `DRIFT_SCROLL - a burst followed by nothing is not sustained`() {
        val sim = Sim()
        sim.run(20, scrollsPerMin = 60.0) // 20 scrolls in 20 s
        assertNull(sim.untilLevel(1, 160), "no scroll trigger within the first 3 min")
    }

    @Test
    fun `DRIFT_SCROLL - scrolls in a neutral app don't count`() {
        val sim = Sim()
        sim.run(3 * 60, app = GAME, scrollsPerMin = 20.0)
        val t = sim.untilLevel(1, 4 * 60, scrollsPerMin = 0.0)
        assertNull(t)
    }

    // ---- DRIFT_AUDIO ----

    @Test
    fun `DRIFT_AUDIO - audio, screen off, no steps for 15 min - Level 1 as a notification`() {
        val sim = Sim()
        sim.run(60, app = PIPE) // PipePipe was in front before the screen went off
        assertTrue(!sim.run(14 * 60 + 50, screenOn = false, audio = true) { it.level > 0 })
        val t = sim.untilLevel(1, 60, screenOn = false, audio = true)
        assertNotNull(t)
        assertEquals(DriftState.DRIFT_AUDIO, sim.last.state)
        assertTrue(sim.shots.any { it.second is OneShot.NudgeNotification }, "audio version of Level 1")
        assertTrue(sim.shots.any { it.second is OneShot.Vibrate })
    }

    @Test
    fun `DRIFT_AUDIO - a step restarts the 15 minutes`() {
        val sim = Sim()
        sim.run(60)
        sim.run(10 * 60, screenOn = false, audio = true)
        sim.run(10, screenOn = false, audio = true, stepsPerMin = 6.0) // one step
        assertNull(sim.untilLevel(1, 14 * 60, screenOn = false, audio = true))
        assertNotNull(sim.untilLevel(1, 2 * 60, screenOn = false, audio = true))
    }

    @Test
    fun `DRIFT_AUDIO - audio after a neutral app is not drift`() {
        val sim = Sim()
        sim.run(60, app = GAME)
        assertNull(sim.untilLevel(1, 30 * 60, screenOn = false, audio = true))
    }

    // ---- MOVING ----

    @Test
    fun `MOVING - 20 steps in 3 min - nothing fires, even in a drift app`() {
        val sim = Sim()
        assertNull(sim.untilLevel(1, 30 * 60, stepsPerMin = 10.0, scrollsPerMin = 10.0))
        assertEquals(DriftState.MOVING, sim.last.state)
    }

    // ---- CHORE ----

    @Test
    fun `CHORE - 30 min after a tag tap, nothing fires`() {
        val sim = Sim()
        sim.act(Action.TagTapped("kitchen"))
        assertNull(sim.untilLevel(1, 29 * 60))
        assertEquals(DriftState.CHORE, sim.last.state)
        assertTrue(sim.last.chore)
        sim.run(60)
        assertEquals(DriftState.DRIFT_SCREEN, sim.last.state, "chore mode over: drift visible again")
    }

    @Test
    fun `CHORE - another tap extends it`() {
        val sim = Sim()
        sim.act(Action.TagTapped("kitchen"))
        sim.run(20 * 60)
        sim.act(Action.TagTapped("laundry"))
        assertNull(sim.untilLevel(1, 29 * 60))
        assertTrue(sim.last.chore)
    }

    @Test
    fun `CHORE - ends early when screen-on scrolling without steps holds 5 min`() {
        val sim = Sim()
        sim.act(Action.TagTapped("kitchen"))
        sim.run(3 * 60, stepsPerMin = 30.0) // chores
        sim.run(5 * 60 + 10, scrollsPerMin = 4.0) // lying down and scrolling
        assertEquals(1, sim.events(EventType.CHORE_END).size)
        assertTrue(!sim.last.chore)
    }

    @Test
    fun `CHORE - watching a video at the sink without scrolling keeps it`() {
        val sim = Sim()
        sim.act(Action.TagTapped("kitchen"))
        sim.run(25 * 60, stepsPerMin = 3.0)
        assertTrue(sim.last.chore)
        assertTrue(sim.events(EventType.CHORE_END).isEmpty())
    }

    // ---- NIGHT ----

    @Test
    fun `NIGHT - after bedtime a drift app starts at Level 2`() {
        val sim = Sim(start = LocalDateTime.of(2026, 11, 2, 23, 40))
        sim.run(10, app = PIPE)
        assertEquals(2, sim.level)
        assertEquals(DriftState.NIGHT, sim.last.state)
        assertEquals(TimeWindow.NIGHT, sim.last.window)
        assertTrue(sim.last.overlay is Overlay.Friction)
    }

    @Test
    fun `NIGHT - neutral apps at night do nothing`() {
        val sim = Sim(start = LocalDateTime.of(2026, 11, 2, 23, 40))
        assertNull(sim.untilLevel(1, 30 * 60, app = GAME))
    }

    @Test
    fun `NIGHT - with only Level 1 switched on, night gives Level 1`() {
        val sim = Sim(settings = testSettings(maxLevel = 1), start = LocalDateTime.of(2026, 11, 3, 0, 30))
        sim.run(10)
        assertEquals(1, sim.level)
    }

    @Test
    fun `NIGHT - before bedtime the normal rules apply`() {
        val sim = Sim(start = LocalDateTime.of(2026, 11, 2, 23, 0))
        sim.run(10)
        assertEquals(0, sim.level)
    }

    // ---- DANGER WINDOW ----

    @Test
    fun `DANGER WINDOW - meal windows halve the time thresholds`() {
        val meal = Settings(driftApps = setOf(PIPE), maxLevel = 3, mealWindows = listOf(TimeRange.parse("14:30-16:00")))
        val screen = Sim(settings = meal)
        val ts = screen.untilLevel(1, 10 * 60)
        assertNotNull(ts); assertTrue(ts in 300..320, "screen rule at $ts s")
        assertEquals(TimeWindow.MEAL, screen.last.window)

        val scroll = Sim(settings = meal)
        val tc = scroll.untilLevel(1, 5 * 60, scrollsPerMin = 8.0)
        assertNotNull(tc); assertTrue(tc in 90..110, "scroll rule at $tc s")

        val audio = Sim(settings = meal)
        audio.run(60)
        val ta = audio.untilLevel(1, 15 * 60, screenOn = false, audio = true)
        assertNotNull(ta); assertTrue(ta in 450..470, "audio rule at $ta s")
    }

    // ---- Reset to Level 0 ----

    @Test
    fun `Reset - 50 steps take Level 1 and Level 2 back to 0`() {
        val sim = Sim()
        sim.untilLevel(1, 11 * 60)
        sim.run(60, stepsPerMin = 60.0)
        assertEquals(0, sim.level)
        assertTrue(sim.events(EventType.RESET).any { it.detail.contains("50 steps") || it.detail.contains("got up") })

        val sim2 = Sim()
        sim2.untilLevel(2, 15 * 60)
        sim2.run(60, stepsPerMin = 60.0)
        assertEquals(0, sim2.level)
    }

    @Test
    fun `Reset - screen off 10 min without audio, but not with audio`() {
        val sim = Sim()
        sim.untilLevel(2, 15 * 60)
        sim.run(9 * 60, screenOn = false)
        assertEquals(2, sim.level)
        sim.run(70, screenOn = false)
        assertEquals(0, sim.level)

        val sim2 = Sim()
        sim2.untilLevel(2, 15 * 60)
        sim2.run(11 * 60, screenOn = false, audio = true)
        assertTrue(sim2.level >= 2, "audio still playing: no reset")
    }

    @Test
    fun `logger mode - with all levels off nothing fires, but states are still computed`() {
        val sim = Sim(settings = testSettings(maxLevel = 0))
        assertNull(sim.untilLevel(1, 20 * 60))
        assertEquals(DriftState.DRIFT_SCREEN, sim.last.state)
        assertTrue(sim.shots.isEmpty())
    }
}

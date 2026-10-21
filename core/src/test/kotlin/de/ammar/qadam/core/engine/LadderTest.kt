package de.ammar.qadam.core.engine

import de.ammar.qadam.core.model.EventType
import de.ammar.qadam.core.rules.Settings
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SPEC §7 — the ladder, one test (or more) per rule. */
class LadderTest {

    private fun atLevel1(settings: Settings = testSettings()): Sim =
        Sim(settings).also { assertNotNull(it.untilLevel(1, 11 * 60)) }

    // ---- Level 1 ----

    @Test
    fun `Level 1 - one vibration and a card with the if-then plan over the drift app`() {
        val sim = atLevel1()
        assertEquals(1, sim.shots.count { it.second is OneShot.Vibrate })
        val card = assertIs<Overlay.Nudge>(sim.last.overlay)
        assertEquals(Settings.EXAMPLE_PLAN, card.plan)
        assertTrue(card.canMore)
        // Over a neutral app the card is not drawn.
        sim.run(10, app = GAME)
        assertEquals(Overlay.None, sim.last.overlay)
    }

    @Test
    fun `Getting up - steps within 2 min close Level 1`() {
        val sim = atLevel1()
        assertTrue(sim.act(Action.GettingUp).accepted)
        assertEquals(Overlay.None, sim.last.overlay)
        sim.run(60, stepsPerMin = 30.0)
        assertEquals(0, sim.level)
        assertTrue(sim.events(EventType.RESET).any { it.detail.contains("got up") })
    }

    @Test
    fun `Getting up - no steps within 2 min gives Level 2`() {
        val sim = atLevel1()
        sim.act(Action.GettingUp)
        sim.run(110)
        assertEquals(1, sim.level)
        sim.run(20)
        assertEquals(2, sim.level)
        assertTrue(sim.events(EventType.INTERVENTION).last().detail.contains("no steps after Getting up"))
    }

    @Test
    fun `5 more minutes - snoozes 5 min, then Level 2 if still drifting`() {
        val sim = atLevel1()
        assertTrue(sim.act(Action.MoreMinutes).accepted)
        assertEquals(0, sim.level)
        assertTrue(!sim.run(4 * 60 + 50) { it.level > 0 }, "quiet during the 5 minutes")
        sim.run(20)
        assertEquals(2, sim.level, "the next trigger is a second trigger")
    }

    @Test
    fun `5 more minutes - at most once an hour`() {
        val sim = atLevel1()
        sim.act(Action.MoreMinutes)
        sim.untilLevel(2, 6 * 60)
        sim.run(60, stepsPerMin = 60.0) // reset
        sim.run(31 * 60, app = GAME) // past the 30-minute second-trigger window
        assertNotNull(sim.untilLevel(1, 11 * 60))
        assertFalse(assertIs<Overlay.Nudge>(sim.last.overlay).canMore, "used 47 min ago")
        val r = sim.act(Action.MoreMinutes)
        assertFalse(r.accepted)
        assertEquals(1, sim.level)
    }

    @Test
    fun `Wrong - logs a false positive and snoozes 15 min`() {
        val sim = atLevel1()
        assertTrue(sim.act(Action.Wrong).accepted)
        assertEquals(0, sim.level)
        assertTrue(sim.events(EventType.RESPONSE).single().detail.startsWith("wrong"))
        assertTrue(!sim.run(14 * 60 + 50) { it.level > 0 }, "snoozed")
        // A false positive doesn't count as a first trigger: Level 1 again, not Level 2.
        sim.run(30)
        assertEquals(1, sim.level)
    }

    // ---- Level 2 ----

    @Test
    fun `Level 2 - after 3 min if Level 1 changed nothing`() {
        val sim = atLevel1()
        sim.run(170)
        assertEquals(1, sim.level)
        sim.run(20)
        assertEquals(2, sim.level)
        assertIs<Overlay.Friction>(sim.last.overlay)
    }

    @Test
    fun `Level 2 - at a second trigger within 30 min`() {
        val sim = atLevel1()
        sim.act(Action.GettingUp)
        sim.run(60, stepsPerMin = 30.0) // got up: back to 0, drift timers restart
        assertEquals(0, sim.level)
        val t = sim.untilLevel(2, 12 * 60)
        assertNotNull(t, "second trigger within 30 min goes straight to Level 2")
        assertTrue(sim.events(EventType.INTERVENTION).last().detail.contains("second trigger"))
    }

    @Test
    fun `Level 2 - a trigger more than 30 min later is a first trigger again`() {
        val sim = atLevel1()
        sim.act(Action.GettingUp)
        sim.run(60, stepsPerMin = 30.0)
        sim.run(31 * 60, app = GAME)
        assertNotNull(sim.untilLevel(1, 11 * 60))
    }

    @Test
    fun `Level 2 - typing a next action of 3 words buys 5 min, fewer words don't`() {
        val sim = atLevel1()
        sim.untilLevel(2, 4 * 60)
        assertFalse(sim.act(Action.NextAction("dishes now")).accepted)
        assertTrue(sim.act(Action.NextAction("wash the dishes")).accepted)
        sim.run(10)
        assertEquals(Overlay.None, sim.last.overlay, "granted")
        assertEquals(2, sim.level)
        assertTrue(sim.events(EventType.RESPONSE).last().detail.contains("wash the dishes"))
    }

    @Test
    fun `Level 2 - audio held until steps start`() {
        val sim = Sim()
        sim.run(60)
        sim.untilLevel(1, 16 * 60, screenOn = false, audio = true)
        sim.untilLevel(2, 4 * 60, screenOn = false, audio = true)
        assertEquals(AudioControl.HOLD, sim.last.audio)
        sim.run(20, screenOn = false, audio = false, stepsPerMin = 15.0)
        assertEquals(AudioControl.FREE, sim.last.audio, "steps started: audio may play")
    }

    @Test
    fun `Level 2 - grayscale only when switched on`() {
        val off = atLevel1()
        off.untilLevel(2, 4 * 60)
        assertFalse(off.last.grayscale)
        val on = atLevel1(testSettings(grayscale = true))
        on.untilLevel(2, 4 * 60)
        assertTrue(on.last.grayscale)
        on.run(60, stepsPerMin = 60.0)
        assertFalse(on.last.grayscale, "off again after the reset")
    }

    // ---- Level 3 ----

    private fun atLevel3(settings: Settings = testSettings()): Sim =
        atLevel1(settings).also {
            assertNotNull(it.untilLevel(2, 4 * 60))
            assertNotNull(it.untilLevel(3, 6 * 60))
        }

    @Test
    fun `Level 3 - 5 min after Level 2 - audio paused, home screen, drift apps blocked 20 min`() {
        val sim = atLevel1()
        sim.untilLevel(2, 4 * 60)
        val t = sim.untilLevel(3, 6 * 60)
        assertNotNull(t); assertTrue(t in 290..310, "after $t s")
        assertTrue(sim.shots.any { it.second == OneShot.StopAudio })
        assertTrue(sim.shots.any { it.second == OneShot.GoHome })
        val block = assertIs<Overlay.Block>(sim.last.overlay)
        assertEquals(sim.now + 20 * 60_000L, block.untilMs)
        assertEquals(2, block.overridesLeft)
    }

    @Test
    fun `Level 3 - after a granted action, Level 3 comes when the grant runs out`() {
        val sim = atLevel1()
        sim.untilLevel(2, 4 * 60)
        sim.run(120)
        sim.act(Action.NextAction("fold the laundry"))
        val t = sim.untilLevel(3, 10 * 60)
        assertNotNull(t); assertTrue(t in 290..310, "5 granted minutes, got $t s")
    }

    @Test
    fun `Level 3 - covers only drift apps, never neutral, allowed or protected apps`() {
        val sim = atLevel3()
        sim.run(10, app = GAME); assertEquals(Overlay.None, sim.last.overlay)
        sim.run(10, app = WHATSAPP); assertEquals(Overlay.None, sim.last.overlay)
        sim.run(10, app = DIALER); assertEquals(Overlay.None, sim.last.overlay)
        sim.run(10, app = "com.android.settings"); assertEquals(Overlay.None, sim.last.overlay)
        sim.run(10, app = YT); assertIs<Overlay.Block>(sim.last.overlay)
    }

    @Test
    fun `Level 3 - a tag tap unlocks and starts chore mode`() {
        val sim = atLevel3()
        assertTrue(sim.act(Action.TagTapped("kitchen")).accepted)
        assertEquals(0, sim.level)
        assertTrue(sim.last.chore)
        assertTrue(sim.events(EventType.TAG_TAP).single().detail.contains("unlocked"))
    }

    @Test
    fun `Level 3 - override needs a 30-second hold and a reason, at most twice a day`() {
        val sim = atLevel3()
        assertFalse(sim.act(Action.Override("tired", heldMs = 29_000)).accepted)
        assertFalse(sim.act(Action.Override("  ", heldMs = 30_000)).accepted)
        assertTrue(sim.act(Action.Override("call from mum on video", heldMs = 30_000)).accepted)
        assertEquals(0, sim.level)
        assertEquals("call from mum on video", sim.events(EventType.OVERRIDE).single().detail)

        // Second Level 3 the same day: second override works.
        sim.untilLevel(3, 30 * 60)
        assertTrue(sim.act(Action.Override("second", heldMs = 31_000)).accepted)
        // Third: refused.
        sim.untilLevel(3, 30 * 60)
        val r = sim.act(Action.Override("third", heldMs = 60_000))
        assertFalse(r.accepted)
        assertEquals(3, sim.level)
        assertEquals(0, assertIs<Overlay.Block>(sim.last.overlay).overridesLeft)
    }

    @Test
    fun `Level 3 - the override count starts again the next day`() {
        val sim = Sim(start = LocalDateTime.of(2026, 11, 2, 21, 0))
        repeat(2) {
            sim.untilLevel(3, 40 * 60)
            assertTrue(sim.act(Action.Override("r", 30_000)).accepted)
        }
        sim.untilLevel(3, 40 * 60)
        assertFalse(sim.act(Action.Override("r", 30_000)).accepted)
        // Wait until the block ends and past midnight.
        sim.run(4 * 3600, app = GAME)
        sim.untilLevel(3, 40 * 60)
        assertTrue(sim.act(Action.Override("new day", 30_000)).accepted)
    }

    @Test
    fun `Level 3 - steps, screen off and a pause don't unlock it`() {
        val sim = atLevel3()
        sim.run(2 * 60, stepsPerMin = 100.0)
        assertEquals(3, sim.level)
        sim.run(12 * 60, screenOn = false)
        assertEquals(3, sim.level)
        assertFalse(sim.act(Action.Pause).accepted)
        assertEquals(3, sim.level)
    }

    @Test
    fun `Level 3 - the block ends after 20 min, and drifting again soon gives Level 2`() {
        val sim = atLevel3()
        sim.run(19 * 60 + 50, app = GAME)
        assertEquals(3, sim.level)
        sim.run(20, app = GAME)
        assertEquals(0, sim.level)
        assertNotNull(sim.untilLevel(2, 11 * 60))
    }

    // ---- Levels switched off (Phase 2: Level 1 only) ----

    @Test
    fun `Level 1 only - no Level 2 or 3, the card stays`() {
        val sim = atLevel1(testSettings(maxLevel = 1))
        sim.run(20 * 60)
        assertEquals(1, sim.level)
        assertIs<Overlay.Nudge>(sim.last.overlay)
    }

    @Test
    fun `Level 1 only - Getting up without steps shows the card again`() {
        val sim = atLevel1(testSettings(maxLevel = 1))
        sim.act(Action.GettingUp)
        sim.run(130)
        assertEquals(1, sim.level)
        assertIs<Overlay.Nudge>(sim.last.overlay)
        assertEquals(2, sim.shots.count { it.second == OneShot.Vibrate })
    }

    // ---- Never blocked ----

    @Test
    fun `calls - nothing fires and nothing is drawn during a call`() {
        val sim = Sim()
        sim.run(9 * 60)
        assertTrue(!sim.run(10 * 60, inCall = true) { it.level > 0 })
        val sim3 = atLevel3()
        sim3.run(10, app = YT, inCall = true)
        assertEquals(Overlay.None, sim3.last.overlay)
    }

    @Test
    fun `Settings and the dialer stay free even if put on the drift list`() {
        val s = Settings(driftApps = setOf(PIPE, "com.android.settings", DIALER), maxLevel = 3)
        assertNull(Sim(s).untilLevel(1, 30 * 60, app = "com.android.settings"))
        assertNull(Sim(s).untilLevel(1, 30 * 60, app = DIALER))
    }

    // ---- Pause ----

    @Test
    fun `Pause - 1 hour, once a day`() {
        val sim = Sim()
        assertTrue(sim.act(Action.Pause).accepted)
        assertTrue(!sim.run(59 * 60) { it.level > 0 }, "no triggers while paused")
        sim.run(2 * 60)
        assertEquals(1, sim.level, "after the hour the rules apply again")
        assertFalse(sim.act(Action.Pause).accepted, "only once a day")
        assertEquals(1, sim.events(EventType.PAUSE).size)
    }

    @Test
    fun `Pause - takes Level 1 or 2 back to 0`() {
        val sim = atLevel1()
        sim.untilLevel(2, 4 * 60)
        assertTrue(sim.act(Action.Pause).accepted)
        assertEquals(0, sim.level)
    }

    @Test
    fun `a long pause that waited 24 h stops triggers until it ends`() {
        val sim = Sim()
        sim.engine.startLongPause(sim.now, sim.now + 3 * 3_600_000L)
        assertNull(sim.untilLevel(1, 3 * 3600 - 60))
        assertNotNull(sim.untilLevel(1, 11 * 60))
    }
}

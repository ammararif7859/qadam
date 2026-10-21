package de.ammar.qadam.core.engine

import de.ammar.qadam.core.model.DriftState
import de.ammar.qadam.core.rules.Commitment
import de.ammar.qadam.core.rules.Decision
import de.ammar.qadam.core.rules.SettingEdit
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §12 — the eight phone scenarios, replayed on synthetic signals.
 * Passing here means the rules are right; the phone checks still test the sensors,
 * the overlays and Android itself.
 */
class ScenariosTest {

    @Test
    fun `S1 - lie on the bed and scroll PipePipe - Level 1 by about 10 min, sooner with heavy scrolling`() {
        val light = Sim()
        val t = light.untilLevel(1, 15 * 60, app = PIPE, scrollsPerMin = 3.0)
        assertNotNull(t); assertTrue(t in 600..620, "light scrolling: $t s")

        val heavy = Sim()
        val th = heavy.untilLevel(1, 15 * 60, app = PIPE, scrollsPerMin = 12.0)
        assertNotNull(th); assertTrue(th < t, "heavy scrolling sooner: $th s")
    }

    @Test
    fun `S2 - tag in the kitchen, phone propped up, dishes 20 min with a video - nothing fires`() {
        val sim = Sim()
        sim.run(60, app = GAME, stepsPerMin = 80.0) // walk to the kitchen
        sim.act(Action.TagTapped("kitchen"))
        // Video playing in a drift app, screen on, few steps at the sink, no scrolling.
        assertFalse(sim.run(20 * 60, app = YT, stepsPerMin = 2.0, audio = true) { it.level > 0 })
        assertEquals(DriftState.CHORE, sim.last.state)
        assertTrue(sim.shots.isEmpty())
    }

    @Test
    fun `S3 - lie down with audio and the screen off for 15 min - Level 1, audio version`() {
        val sim = Sim()
        sim.run(30, app = PIPE)
        val t = sim.untilLevel(1, 16 * 60, screenOn = false, audio = true)
        assertNotNull(t)
        assertTrue(t in 890..920, "at $t s")
        assertIs<OneShot.NudgeNotification>(sim.shots.map { it.second }.first { it is OneShot.NudgeNotification })
    }

    @Test
    fun `S4 - phone in pocket, walking with audio - nothing fires`() {
        val sim = Sim()
        sim.run(30, app = PIPE)
        assertNull(sim.untilLevel(1, 40 * 60, screenOn = false, audio = true, stepsPerMin = 100.0))
        assertEquals(DriftState.MOVING, sim.last.state)
    }

    @Test
    fun `S5 - at Level 3 only a tag or the 30-second override unlocks`() {
        fun level3(): Sim = Sim().also {
            it.untilLevel(1, 11 * 60); it.untilLevel(2, 4 * 60); it.untilLevel(3, 6 * 60)
            assertEquals(3, it.level)
        }
        // Things that must NOT unlock:
        val sim = level3()
        sim.run(3 * 60, stepsPerMin = 120.0)
        sim.run(11 * 60, screenOn = false)
        assertFalse(sim.act(Action.Pause).accepted)
        assertFalse(sim.act(Action.GettingUp).accepted)
        assertFalse(sim.act(Action.NextAction("let me watch more")).accepted)
        assertFalse(sim.act(Action.Override("short hold", 10_000)).accepted)
        assertEquals(3, sim.level)
        // A tag unlocks:
        assertTrue(sim.act(Action.TagTapped("fridge")).accepted)
        assertEquals(0, sim.level)
        // The override unlocks:
        val sim2 = level3()
        assertTrue(sim2.act(Action.Override("need the video for homework", 30_000)).accepted)
        assertEquals(0, sim2.level)
    }

    @Test
    fun `S6 - a call during Level 3 works`() {
        val sim = Sim()
        sim.untilLevel(1, 11 * 60); sim.untilLevel(2, 4 * 60); sim.untilLevel(3, 6 * 60)
        // The dialer / in-call screen is protected: never covered.
        sim.run(60, app = DIALER, inCall = true)
        assertEquals(Overlay.None, sim.last.overlay)
        assertTrue(sim.last.oneShots.none { it is OneShot.StopAudio || it is OneShot.GoHome })
        sim.run(60, app = "com.android.incallui", inCall = true)
        assertEquals(Overlay.None, sim.last.overlay)
    }

    @Test
    fun `S7 - after a restart the engine picks up where it was (a Level 3 block survives)`() {
        val sim = Sim()
        sim.untilLevel(1, 11 * 60); sim.untilLevel(2, 4 * 60); sim.untilLevel(3, 6 * 60)
        val saved = sim.engine.state.encode()
        // "Reboot": a new engine from the saved text, two minutes later.
        val restarted = Engine(testSettings(), ZONE, initial = EngineState.decode(saved))
        val out = restarted.tick(Tick(sim.now + 120_000, screenOn = true, app = PIPE))
        assertEquals(3, out.level)
        assertIs<Overlay.Block>(out.overlay)
    }

    @Test
    fun `S8 - loosen a threshold - it shows active tomorrow at hh-mm`() {
        val start = LocalDateTime.of(2026, 11, 2, 18, 7)
        val now = start.atZone(ZONE).toInstant().toEpochMilli()
        val d = assertIs<Decision.Pending>(
            Commitment.request(testSettings(), emptyList(), SettingEdit.SetThreshold("drift_screen.min", 20.0), now),
        )
        assertEquals("active tomorrow at 18:07", Commitment.label(d.change.effectiveMs, now, ZONE))
    }

    @Test
    fun `engine state survives the trip through text`() {
        val s = EngineState(
            level = 2, levelSinceMs = 5, kind = TriggerKind.SCROLL, cardOpen = true, gettingUpAtMs = 7,
            snoozeUntilMs = 8, lastMoreMs = 9, lastTriggerMs = 10, grantUntilMs = 11, audioReleased = true,
            blockUntilMs = 12, driftClockMs = 13, choreUntilMs = 14, choreSinceMs = 15, pauseUntilMs = 16,
            day = "2026-11-02", overridesToday = 1, pausesToday = 1, sessionStartMs = 17, awaySinceMs = 18,
            audioStillSinceMs = 19, quietSinceMs = 20, lastScreenApp = "org.x",
        )
        assertEquals(s, EngineState.decode(s.encode()))
        assertEquals(EngineState(), EngineState.decode("garbage=;level=x"))
        assertEquals(EngineState(), EngineState.decode(null))
    }
}

package de.ammar.qadam.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThresholdsTest {

    private val t = Thresholds.DEFAULT

    @Test
    fun `file and knob list name exactly the same keys`() {
        assertEquals(Knobs.all.map { it.key }.toSet(), t.keys())
        assertEquals(Knobs.all.size, Knobs.all.map { it.key }.toSet().size, "duplicate knob")
    }

    @Test
    fun `section 6 placeholders match SPEC`() {
        assertEquals(30 * 60_000L, t.choreDurationMs)
        assertEquals(5 * 60_000L, t.choreCancelMs)
        assertEquals(20, t.movingSteps)
        assertEquals(3 * 60_000L, t.movingWindowMs)
        assertEquals(10 * 60_000L, t.driftScreenMs)
        assertEquals(10, t.driftScreenMaxSteps)
        assertEquals(5 * 60_000L, t.driftScreenStepsWindowMs)
        assertEquals(6.0, t.driftScrollPerMin)
        assertEquals(3 * 60_000L, t.driftScrollMs)
        assertEquals(15 * 60_000L, t.driftAudioMs)
        assertEquals(0, t.driftAudioMaxSteps)
        assertEquals(0.5, t.dangerFactor)
        assertEquals(50, t.resetSteps)
        assertEquals(10 * 60_000L, t.resetScreenOffMs)
    }

    @Test
    fun `section 7 and 8 numbers match SPEC`() {
        assertEquals(2 * 60_000L, t.gettingUpGraceMs)
        assertEquals(5 * 60_000L, t.moreMs)
        assertEquals(60 * 60_000L, t.moreEveryMs)
        assertEquals(15 * 60_000L, t.wrongSnoozeMs)
        assertEquals(3 * 60_000L, t.level2AfterLevel1Ms)
        assertEquals(30 * 60_000L, t.secondTriggerMs)
        assertEquals(5 * 60_000L, t.level2GrantMs)
        assertEquals(3, t.level2MinWords)
        assertEquals(5 * 60_000L, t.level3AfterLevel2Ms)
        assertEquals(20 * 60_000L, t.level3BlockMs)
        assertEquals(30_000L, t.overrideHoldMs)
        assertEquals(2, t.overridesPerDay)
        assertEquals(60 * 60_000L, t.pauseMs)
        assertEquals(1, t.pausesPerDay)
    }

    @Test
    fun `unit suffix decides the conversion`() {
        assertEquals(60_000L, t.ms("drift_screen.break_s"))
        assertFailsWith<IllegalArgumentException> { t.ms("moving.steps") }
    }

    @Test
    fun `parse rejects bad values`() {
        assertFailsWith<IllegalArgumentException> { Thresholds.parse("a.b_min=ten") }
        assertFailsWith<IllegalArgumentException> { Thresholds.parse("a.b_min=-1") }
        assertFailsWith<IllegalArgumentException> { Thresholds.parse("danger.factor=1.5") }
        assertFailsWith<IllegalArgumentException> { t.with("no.such_min", 1.0) }
    }

    @Test
    fun `with and withAll change only known keys`() {
        val t2 = t.with("drift_screen.min", 8.0)
        assertEquals(8 * 60_000L, t2.driftScreenMs)
        assertEquals(10 * 60_000L, t.driftScreenMs, "original unchanged")
        val t3 = t.withAll(mapOf("drift_screen.min" to 12.0, "unknown.key" to 1.0))
        assertEquals(12 * 60_000L, t3.driftScreenMs)
    }

    @Test
    fun `loosening direction follows the knob`() {
        assertTrue(Knobs.isLoosening("drift_screen.min", 10.0, 15.0))
        assertFalse(Knobs.isLoosening("drift_screen.min", 10.0, 8.0))
        assertTrue(Knobs.isLoosening("reset.steps", 50.0, 30.0))
        assertFalse(Knobs.isLoosening("reset.steps", 50.0, 80.0))
        assertTrue(Knobs.isLoosening("level3.block_min", 20.0, 10.0))
        assertTrue(Knobs.isLoosening("sensors.rate_hz", 20.0, 20.0), "fixed knobs are never editable")
    }
}

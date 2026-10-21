package de.ammar.qadam.core.signals

import de.ammar.qadam.core.config.Thresholds
import de.ammar.qadam.core.signals.SyntheticMotion.countSteps
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StepDetectorTest {

    private fun detector() = AccelStepDetector(Thresholds.DEFAULT)

    private fun assertNear(expected: Int, actual: Int, tolerance: Double, what: String) {
        assertTrue(
            abs(actual - expected) <= expected * tolerance,
            "$what: expected about $expected steps, got $actual",
        )
    }

    @Test
    fun `normal walking at 20 Hz counts about one step per bounce`() {
        val steps = SyntheticMotion.walking(60.0, rateHz = 20.0, stepHz = 1.8).countSteps(detector())
        assertNear(108, steps, 0.08, "60 s at 1.8 steps/s")
    }

    @Test
    fun `normal walking at 10 Hz still counts`() {
        val steps = SyntheticMotion.walking(60.0, rateHz = 10.0, stepHz = 1.8).countSteps(detector())
        assertNear(108, steps, 0.10, "10 Hz sampling")
    }

    @Test
    fun `slow and fast walking count`() {
        val slow = SyntheticMotion.walking(60.0, stepHz = 1.2, amp = 1.8, seed = 7).countSteps(detector())
        assertNear(72, slow, 0.10, "slow walk")
        val fast = SyntheticMotion.walking(60.0, stepHz = 2.2, amp = 3.0, seed = 8).countSteps(detector())
        assertNear(132, fast, 0.10, "fast walk")
    }

    @Test
    fun `phone carried gently in hand still counts`() {
        val steps = SyntheticMotion.walking(60.0, stepHz = 1.7, amp = 1.6, noise = 0.2, seed = 9).countSteps(detector())
        assertNear(102, steps, 0.12, "gentle walk in hand")
    }

    @Test
    fun `phone on a table counts nothing`() {
        assertEquals(0, SyntheticMotion.table(600.0).countSteps(detector()))
    }

    @Test
    fun `lying in bed scrolling counts nothing`() {
        for (seed in 1..5) {
            val steps = SyntheticMotion.lyingInHand(300.0, seed = seed).countSteps(detector())
            assertEquals(0, steps, "seed $seed")
        }
    }

    @Test
    fun `lying with strong fidgeting counts almost nothing`() {
        // Stronger, random jolts: allow at most a few false steps in 5 minutes.
        for (seed in 1..5) {
            val steps = SyntheticMotion.lyingInHand(300.0, joltMax = 2.5, seed = seed).countSteps(detector())
            assertTrue(steps <= 8, "seed $seed: $steps false steps in 5 min")
        }
    }

    @Test
    fun `turning over in bed counts nothing`() {
        assertEquals(0, SyntheticMotion.turnOver(20.0).countSteps(detector()))
    }

    @Test
    fun `three steps are not a walk, ten are`() {
        assertEquals(0, SyntheticMotion.fewSteps(3).countSteps(detector()))
        assertNear(10, SyntheticMotion.fewSteps(10).countSteps(detector()), 0.2, "ten steps")
    }

    @Test
    fun `a pause in the data starts a fresh run`() {
        val d = detector()
        val first = SyntheticMotion.walking(3.0, stepHz = 1.8, seed = 11)
        val second = SyntheticMotion.walking(3.0, stepHz = 1.8, seed = 12, startNs = 30_000_000_000L)
        val total = first.countSteps(d) + second.countSteps(d)
        assertTrue(total in 6..12, "two 3-second walks separated by a gap: $total")
    }
}

class GravityAndMotionTest {

    @Test
    fun `gravity filter finds gravity under walking bounce`() {
        val f = GravityFilter(0.8)
        for (s in SyntheticMotion.walking(10.0, tiltDeg = 60.0)) f.update(s.tNs, s.x, s.y, s.z)
        val g = sqrt(f.gx * f.gx + f.gy * f.gy + f.gz * f.gz)
        assertTrue(abs(g - 9.81) < 0.6, "gravity size $g")
        // Phone tilted 60°: most of gravity along y.
        assertTrue(f.gy > f.gz, "gy=${f.gy} gz=${f.gz}")
    }

    @Test
    fun `motion level separates table, hand and walking`() {
        fun level(samples: List<SyntheticMotion.Sample>): Double {
            val g = GravityFilter(0.8)
            val m = MotionMeter(10.0)
            for (s in samples) {
                g.update(s.tNs, s.x, s.y, s.z)
                m.onLinear(s.tNs, g.lx, g.ly, g.lz)
            }
            return m.flush().first!!
        }
        val table = level(SyntheticMotion.table(60.0))
        val hand = level(SyntheticMotion.lyingInHand(60.0))
        val walk = level(SyntheticMotion.walking(60.0))
        assertTrue(table < 0.1, "table $table")
        assertTrue(hand in table..walk, "hand $hand")
        assertTrue(walk > 1.0, "walk $walk")
    }

    @Test
    fun `flush gives mean and p90, then clears`() {
        val m = MotionMeter(10.0)
        assertEquals(null to null, m.flush())
        for (i in 0 until 200) m.onLinear(i * 50_000_000L, 1.0, 0.0, 0.0) // 10 s of |a| = 1
        val (mean, p90) = m.flush()
        assertEquals(1.0, mean!!, 1e-9)
        assertEquals(1.0, p90!!, 1e-9)
        assertNull(m.flush().first)
    }

    @Test
    fun `percentile uses nearest rank`() {
        assertEquals(9.0, MotionMeter.percentile((1..10).map { it.toDouble() }, 0.9))
        assertEquals(1.0, MotionMeter.percentile(listOf(1.0), 0.9))
    }
}

class StepLogTest {
    @Test
    fun `counts steps inside a window and forgets old ones`() {
        val log = StepLog(keepMs = 10 * 60_000L)
        log.add(1_000, 5)
        log.add(61_000, 3)
        log.add(61_000, 2)
        assertEquals(10, log.inLast(120_000, 120_000))
        assertEquals(5, log.inLast(60_000, 120_000))
        assertEquals(61_000, log.lastStepMs())
        log.add(20 * 60_000L, 1) // prunes everything older than 10 min
        assertEquals(1, log.inLast(60 * 60_000L, 20 * 60_000L))
    }
}

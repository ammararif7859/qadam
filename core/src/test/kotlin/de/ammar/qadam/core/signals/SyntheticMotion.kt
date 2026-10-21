package de.ammar.qadam.core.signals

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic accelerometer streams for tests (m/s², gravity included, timestamps in ns).
 * Each generator is deterministic for a given seed.
 */
object SyntheticMotion {
    data class Sample(val tNs: Long, val x: Double, val y: Double, val z: Double)

    private const val G = 9.81

    /** Gravity for a phone tilted [tiltDeg] from flat (screen up). */
    private fun gravity(tiltDeg: Double): Triple<Double, Double, Double> {
        val th = tiltDeg * PI / 180
        return Triple(0.0, G * sin(th), G * cos(th))
    }

    private fun Random.gauss(): Double {
        // Box–Muller transform: two uniform numbers → one normally distributed number.
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * PI * u2)
    }

    private fun stream(
        seconds: Double,
        rateHz: Double,
        startNs: Long,
        seed: Int,
        noise: Double,
        tiltDeg: Double,
        extra: (t: Double, rnd: Random) -> Double,
    ): List<Sample> {
        val rnd = Random(seed)
        val (gx, gy, gz) = gravity(tiltDeg)
        val n = (seconds * rateHz).toInt()
        val ux = gx / G; val uy = gy / G; val uz = gz / G // unit vector along gravity
        return List(n) { i ->
            val t = i / rateHz
            val a = extra(t, rnd)
            Sample(
                startNs + (t * 1e9).toLong(),
                gx + a * ux + noise * rnd.gauss(),
                gy + a * uy + noise * rnd.gauss(),
                gz + a * uz + noise * rnd.gauss(),
            )
        }
    }

    /** Walking: a bounce at [stepHz] per second with a heel-strike harmonic. */
    fun walking(
        seconds: Double,
        rateHz: Double = 20.0,
        stepHz: Double = 1.8,
        amp: Double = 2.5,
        noise: Double = 0.3,
        tiltDeg: Double = 60.0,
        startNs: Long = 0,
        seed: Int = 1,
    ) = stream(seconds, rateHz, startNs, seed, noise, tiltDeg) { t, rnd ->
        val ph = 2 * PI * stepHz * t
        amp * sin(ph) + 0.35 * amp * sin(2 * ph + 1.0) + 0.1 * amp * rnd.gauss()
    }

    /** Phone lying on a table: only sensor noise. */
    fun table(seconds: Double, rateHz: Double = 20.0, startNs: Long = 0, seed: Int = 2) =
        stream(seconds, rateHz, startNs, seed, noise = 0.02, tiltDeg = 0.0) { _, _ -> 0.0 }

    /**
     * Lying in bed, phone in hand, scrolling: hand tremor, a slow sway, and small thumb jolts
     * at random moments ([joltMax] m/s² at most).
     */
    fun lyingInHand(
        seconds: Double,
        rateHz: Double = 20.0,
        joltMax: Double = 0.8,
        startNs: Long = 0,
        seed: Int = 3,
    ): List<Sample> {
        val jolts = Random(seed + 100).let { r ->
            val list = mutableListOf<Pair<Double, Double>>() // (time, amplitude)
            var t = r.nextDouble(0.3, 3.0)
            while (t < seconds) {
                list += t to r.nextDouble(0.3 * joltMax, joltMax)
                t += r.nextDouble(0.3, 3.0)
            }
            list
        }
        return stream(seconds, rateHz, startNs, seed, noise = 0.06, tiltDeg = 30.0) { t, _ ->
            var a = 0.4 * sin(2 * PI * 0.25 * t)
            for ((tj, aj) in jolts) {
                val dt = t - tj
                if (dt in 0.0..0.15) a += aj * sin(PI * dt / 0.15)
            }
            a
        }
    }

    /** Turning over in bed: one large, slow movement lasting [durS] seconds at [atS]. */
    fun turnOver(seconds: Double, atS: Double = 5.0, durS: Double = 1.5, rateHz: Double = 20.0, seed: Int = 4) =
        stream(seconds, rateHz, 0, seed, noise = 0.05, tiltDeg = 80.0) { t, _ ->
            val dt = t - atS
            if (dt in 0.0..durS) 4.0 * sin(PI * dt / durS) else 0.0
        }

    /** A few steps only ([steps] bounces), then standing still. */
    fun fewSteps(steps: Int, stepHz: Double = 1.8, rateHz: Double = 20.0, seed: Int = 5): List<Sample> {
        val walkS = steps / stepHz
        return stream(walkS + 5.0, rateHz, 0, seed, noise = 0.2, tiltDeg = 60.0) { t, _ ->
            if (t < walkS) 2.5 * sin(2 * PI * stepHz * t) else 0.0
        }
    }

    fun Iterable<Sample>.countSteps(d: AccelStepDetector): Int = sumOf { d.onSample(it.tNs, it.x, it.y, it.z) }
}

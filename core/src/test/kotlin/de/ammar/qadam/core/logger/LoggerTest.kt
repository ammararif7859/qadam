package de.ammar.qadam.core.logger

import de.ammar.qadam.core.config.Thresholds
import de.ammar.qadam.core.csv.Csv
import de.ammar.qadam.core.model.AppCategory
import de.ammar.qadam.core.model.DriftState
import de.ammar.qadam.core.model.EventRow
import de.ammar.qadam.core.model.EventType
import de.ammar.qadam.core.model.Label
import de.ammar.qadam.core.model.TimeWindow
import de.ammar.qadam.core.rules.AppCategorizer
import de.ammar.qadam.core.rules.DayWindows
import de.ammar.qadam.core.rules.Settings
import de.ammar.qadam.core.rules.TimeRange
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

private fun ctx(timeMs: Long = 0L) = RowContext(
    timeMs = timeMs, screenOn = true, app = "org.schabi.newpipe", appCategory = AppCategory.DRIFT,
    sessionS = 120, stepsLast5min = 3, audio = true, headphones = false, charging = false,
    choreMode = false, window = TimeWindow.DAY, state = DriftState.IDLE, level = 0,
)

class CsvTest {
    @Test
    fun `header is exactly SPEC section 4`() {
        val spec = "time, screen_on, app, app_category, session_s, scrolls, taps, steps, steps_last_5min, " +
            "motion_rms_mean, motion_rms_p90, gravity_x, gravity_y, gravity_z, proximity_near, lux, audio, " +
            "headphones, charging, chore_mode, window, state, level, label"
        assertEquals(spec.split(", "), Csv.SUMMARY_HEADER)
    }

    @Test
    fun `a row becomes one line with the same number of cells`() {
        val acc = RowAccumulator()
        acc.addScrolls(7); acc.addTaps(2); acc.setLabel(Label.DRIFTING)
        val row = acc.flush(1_000_000_000L, ctx(timeMs = 1_761_082_890_000L))
        val line = Csv.summaryLine(row, ZONE)
        val cells = Csv.parseLine(line)
        assertEquals(Csv.SUMMARY_HEADER.size, cells.size)
        val byName = Csv.SUMMARY_HEADER.zip(cells).toMap()
        assertEquals("1", byName["screen_on"])
        assertEquals("DRIFT", byName["app_category"])
        assertEquals("7", byName["scrolls"])
        assertEquals("", byName["motion_rms_mean"], "no motion samples → empty cell")
        assertEquals("drifting", byName["label"])
        assertTrue(byName["time"]!!.matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\+0[12]:00""")), byName["time"])
    }

    @Test
    fun `cells with commas or quotes are quoted and read back`() {
        val e = EventRow(0L, EventType.OVERRIDE, "tired, \"really\" tired")
        val cells = Csv.parseLine(Csv.eventLine(e, ZONE))
        assertEquals(listOf("OVERRIDE", "tired, \"really\" tired"), cells.drop(1))
    }

    @Test
    fun `export writes a header and one line per row`() {
        val sb = StringBuilder()
        val acc = RowAccumulator()
        Csv.writeSummary(sequenceOf(acc.flush(0, ctx()), acc.flush(1, ctx())), ZONE, sb)
        val lines = sb.lines().filter { it.isNotEmpty() }
        assertEquals(3, lines.size)
        assertEquals(Csv.SUMMARY_HEADER.joinToString(","), lines[0])
    }
}

class RowAccumulatorTest {
    @Test
    fun `counts reset after each row`() {
        val acc = RowAccumulator()
        acc.addScrolls(3); acc.addTaps(1); acc.addSteps(12)
        val r1 = acc.flush(30_000_000_000L, ctx())
        assertEquals(3, r1.scrolls); assertEquals(1, r1.taps); assertEquals(12, r1.steps)
        val r2 = acc.flush(60_000_000_000L, ctx())
        assertEquals(0, r2.scrolls); assertEquals(0, r2.steps); assertNull(r2.label)
    }

    @Test
    fun `proximity is the share of time near, light is time-weighted`() {
        val acc = RowAccumulator()
        val s = 1_000_000_000L
        acc.onProximity(0, isNear = false)
        acc.onLight(0, 10.0)
        acc.onProximity(10 * s, isNear = true) // near for the last 20 of 30 s
        acc.onLight(20 * s, 100.0) // 10 lx for 20 s, 100 lx for 10 s → 40 lx
        val r = acc.flush(30 * s, ctx())
        assertEquals(2.0 / 3.0, r.proximityNear!!, 1e-9)
        assertEquals(40.0, r.lux!!, 1e-9)
        // Next row: still near, still 100 lx.
        val r2 = acc.flush(60 * s, ctx())
        assertEquals(1.0, r2.proximityNear!!, 1e-9)
        assertEquals(100.0, r2.lux!!, 1e-9)
    }

    @Test
    fun `after the sensors stop, readings are empty`() {
        val acc = RowAccumulator()
        acc.onProximity(0, true); acc.onLight(0, 5.0)
        acc.onGravity(0, 0.0, 9.8, 0.0)
        acc.flush(10, ctx())
        acc.sensorsStopped(10)
        val r = acc.flush(20, ctx())
        assertNull(r.proximityNear); assertNull(r.lux); assertNull(r.gravityY)
    }

    @Test
    fun `gravity is the mean of the row`() {
        val acc = RowAccumulator()
        acc.onGravity(0, 0.0, 9.0, 1.0)
        acc.onGravity(1, 0.0, 7.0, 3.0)
        val r = acc.flush(2, ctx())
        assertEquals(8.0, r.gravityY!!, 1e-9)
        assertEquals(2.0, r.gravityZ!!, 1e-9)
    }

    @Test
    fun `row timing follows SPEC section 4`() {
        val t = Thresholds.DEFAULT
        assertEquals(30_000L, RowTiming.intervalMs(t, screenOn = true, audio = false))
        assertEquals(30_000L, RowTiming.intervalMs(t, screenOn = false, audio = true))
        assertEquals(300_000L, RowTiming.intervalMs(t, screenOn = false, audio = false))
        assertTrue(RowTiming.sensorsWanted(false, true))
        assertTrue(!RowTiming.sensorsWanted(false, false))
    }
}

class CategoriesAndWindowsTest {
    @Test
    fun `drift, allowed, neutral and protected apps`() {
        val c = AppCategorizer(
            drift = setOf("org.schabi.newpipe", "com.google.android.youtube", "com.android.settings"),
            allowed = setOf("com.whatsapp"),
        )
        assertEquals(AppCategory.DRIFT, c.of("com.google.android.youtube"))
        assertEquals(AppCategory.ALLOWED, c.of("com.whatsapp"))
        assertEquals(AppCategory.NEUTRAL, c.of("com.example.game"))
        assertEquals(AppCategory.ALLOWED, c.of("com.android.settings"), "Settings can never be a drift app")
        assertEquals(AppCategory.ALLOWED, c.of("de.ammar.qadam"))
        assertNull(c.of(null))
    }

    @Test
    fun `time ranges may wrap midnight`() {
        val night = TimeRange(LocalTime.of(23, 30), LocalTime.of(6, 0))
        assertTrue(LocalTime.of(23, 45) in night)
        assertTrue(LocalTime.of(2, 0) in night)
        assertTrue(LocalTime.of(6, 0) !in night)
        assertEquals(390, night.minutes().size)
        assertEquals(TimeRange(LocalTime.of(13, 0), LocalTime.of(14, 0)), TimeRange.parse("13:00-14:00"))
    }

    @Test
    fun `night wins over a meal window`() {
        val s = Settings(mealWindows = listOf(TimeRange.parse("13:00-14:00"), TimeRange.parse("23:00-23:59")))
        val w = DayWindows(s)
        assertEquals(TimeWindow.MEAL, w.at(LocalTime.of(13, 30)))
        assertEquals(TimeWindow.DAY, w.at(LocalTime.of(15, 0)))
        assertEquals(TimeWindow.MEAL, w.at(LocalTime.of(23, 10)))
        assertEquals(TimeWindow.NIGHT, w.at(LocalTime.of(23, 40)), "default bedtime 23:30")
        assertEquals(TimeWindow.NIGHT, w.at(LocalTime.of(5, 59)))
    }

    @Test
    fun `defaults of the 21 Oct run`() {
        val s = Settings()
        assertEquals(LocalTime.of(23, 30), s.bedtime)
        assertTrue(s.mealWindows.isEmpty())
        assertTrue(s.driftApps.isEmpty())
        assertEquals(0, s.maxLevel, "a fresh install only logs (Phase 0)")
        assertEquals(Settings.EXAMPLE_PLAN, s.planFor(TimeWindow.MEAL), "falls back to the one example plan")
    }
}

class SamplingPlannerTest {
    private val planner = SamplingPlanner(Thresholds.DEFAULT)
    private val bedtime = LocalTime.of(23, 30)

    @Test
    fun `five prompts a day, inside waking hours, an hour apart`() {
        for (d in 0L until 60L) {
            val date = LocalDate.of(2026, 11, 1).plusDays(d)
            val plan = planner.plan(date, bedtime, installSeed = 42)
            assertEquals(5, plan.size, "$date")
            assertTrue(plan.all { it.toLocalDate() == date && it.toLocalTime() >= LocalTime.of(8, 0) && it.toLocalTime() < bedtime })
            assertTrue(plan.zipWithNext().all { (a, b) -> java.time.Duration.between(a, b).toMinutes() >= 60 })
        }
    }

    @Test
    fun `plans differ by day and repeat for the same day`() {
        val a = planner.plan(LocalDate.of(2026, 11, 1), bedtime, 42)
        val b = planner.plan(LocalDate.of(2026, 11, 2), bedtime, 42)
        assertEquals(a, planner.plan(LocalDate.of(2026, 11, 1), bedtime, 42))
        assertTrue(a.map { it.toLocalTime() } != b.map { it.toLocalTime() })
    }

    @Test
    fun `a short day gets fewer prompts`() {
        val plan = SamplingPlanner(5, 8, 60, 60).plan(LocalDate.of(2026, 11, 1), LocalTime.of(10, 0), 1)
        assertEquals(2, plan.size)
    }

    @Test
    fun `a due prompt waits for the screen, then expires`() {
        val date = LocalDate.of(2026, 11, 3)
        val plan = planner.plan(date, bedtime, 7)
        val first = plan.first().atZone(ZONE).toInstant().toEpochMilli()
        assertNull(planner.due(first - 1, true, plan, emptySet(), ZONE), "not yet")
        assertNull(planner.due(first + 1, false, plan, emptySet(), ZONE), "screen off: wait")
        assertEquals(first, planner.due(first + 30 * 60_000L, true, plan, emptySet(), ZONE))
        assertNull(planner.due(first + 30 * 60_000L, true, plan, setOf(first), ZONE), "already shown")
        assertNull(planner.due(first + 61 * 60_000L, true, plan, emptySet(), ZONE), "waited too long")
        assertEquals(listOf(first), planner.expired(first + 61 * 60_000L, plan, emptySet(), ZONE))
    }
}

class SettingsCodecTest {
    @Test
    fun `settings survive the trip through text`() {
        val s = Settings(
            driftApps = setOf("org.polymorphicshade.pipepipe", "com.google.android.youtube"),
            allowedApps = setOf("com.whatsapp"),
            bedtime = LocalTime.of(23, 15),
            nightEnd = LocalTime.of(6, 30),
            mealWindows = listOf(TimeRange.parse("13:00-14:00"), TimeRange.parse("19:30-20:15")),
            plans = mapOf(
                TimeWindow.DAY to Settings.EXAMPLE_PLAN,
                TimeWindow.NIGHT to "Phone on the desk = charger,\nthen brush teeth \\ floss.",
            ),
            maxLevel = 3,
            grayscale = true,
            thresholdOverrides = mapOf("drift_screen.min" to 8.0, "reset.steps" to 60.0),
        )
        assertEquals(s, Settings.decode(s.encode()))
        assertEquals(Settings(), Settings.decode(Settings().encode()))
    }

    @Test
    fun `broken or missing text gives defaults`() {
        assertEquals(Settings(), Settings.decode(null))
        assertEquals(Settings(), Settings.decode(""))
        val partial = Settings.decode("bedtime=notatime\nmaxLevel=1")
        assertEquals(LocalTime.of(23, 30), partial.bedtime)
        assertEquals(1, partial.maxLevel)
    }
}

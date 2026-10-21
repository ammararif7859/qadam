package de.ammar.qadam.core.logger

import de.ammar.qadam.core.model.AppCategory
import de.ammar.qadam.core.model.DriftState
import de.ammar.qadam.core.model.EventRow
import de.ammar.qadam.core.model.EventType
import de.ammar.qadam.core.model.Label
import de.ammar.qadam.core.model.SummaryRow
import de.ammar.qadam.core.model.TimeWindow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.of(2026, 11, 2)
    private fun ms(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(day, LocalTime.of(h, m, s)).atZone(zone).toInstant().toEpochMilli()

    private fun row(t: Long, state: DriftState = DriftState.IDLE, chore: Boolean = false, label: Label? = null,
                    cat: AppCategory? = AppCategory.DRIFT) = SummaryRow(
        t, true, "pipe", cat, 0, 0, 0, 0, 0, null, null, null, null, null, null, null,
        false, false, false, chore, TimeWindow.DAY, state, 0, label,
    )

    @Test
    fun `drift and chore minutes come from the rows`() {
        // 20 rows of 30 s in DRIFT_SCREEN = 10 min; then 10 chore rows = 5 min.
        val rows = (0 until 20).map { row(ms(15, 0) + it * 30_000L, DriftState.DRIFT_SCREEN) } +
            (20 until 30).map { row(ms(15, 0) + it * 30_000L, chore = true, cat = AppCategory.NEUTRAL) }
        val events = listOf(
            EventRow(ms(15, 1), EventType.INTERVENTION, "level=1"),
            EventRow(ms(15, 2), EventType.RESPONSE, "wrong (false positive, SCREEN)"),
            EventRow(ms(15, 3), EventType.OVERRIDE, "x"),
        )
        val t = Reports.totals(day, rows, events, zone)
        assertEquals(10.0, t.driftMin, 0.51)
        assertEquals(5.0, t.choreMin, 0.01)
        assertEquals(10.0, t.driftAppMin, 0.51)
        assertEquals(1, t.triggers); assertEquals(1, t.falsePositives); assertEquals(1, t.overrides)
    }

    @Test
    fun `a long silence between rows counts as at most 5 minutes`() {
        val mins = Reports.rowMinutes(listOf(row(ms(10, 0)), row(ms(12, 0))))
        assertEquals(5.0, mins[1])
    }

    @Test
    fun `service gaps and accessibility off-times are measured`() {
        val ev = listOf(
            EventRow(ms(3, 0), EventType.SERVICE_GAP, "from=${ms(1, 0)} to=${ms(3, 0)}"),
            EventRow(ms(10, 0), EventType.ACCESSIBILITY_OFF),
            EventRow(ms(10, 35), EventType.ACCESSIBILITY_ON),
        )
        val (svc, acc) = Reports.offTimes(ev, ms(0, 0), ms(23, 59))
        assertEquals(120 * 60_000L, svc)
        assertEquals(35 * 60_000L, acc)
        val t = Reports.totals(day, emptyList(), ev, zone)
        val text = Reports.morningText(t)
        assertTrue("Qadam was off 2 h 0 min" in text, text)
        assertTrue("scroll counter was off 35 min" in text, text)
    }

    @Test
    fun `a gap event appears only after more than 6 minutes of silence`() {
        assertNull(Reports.gapEvent(null, 1000))
        assertNull(Reports.gapEvent(0, 6 * 60_000L))
        assertEquals(EventType.SERVICE_GAP, Reports.gapEvent(0, 7 * 60_000L)?.type)
    }

    @Test
    fun `morning summary once per day, after night end, with the screen on`() {
        val nightEnd = LocalTime.of(6, 0)
        assertFalse(Reports.morningDue(ms(5, 30), zone, nightEnd, null, true))
        assertTrue(Reports.morningDue(ms(7, 0), zone, nightEnd, null, true))
        assertFalse(Reports.morningDue(ms(7, 0), zone, nightEnd, day, true))
        assertFalse(Reports.morningDue(ms(7, 0), zone, nightEnd, null, false))
    }

    @Test
    fun `labels are counted and retention keeps 30 days`() {
        val rows = listOf(row(ms(9, 0), label = Label.DRIFTING), row(ms(9, 1), label = Label.DRIFTING), row(ms(9, 2), label = Label.CHORES))
        assertEquals(mapOf(Label.DRIFTING to 2, Label.CHORES to 1), Reports.totals(day, rows, emptyList(), zone).labels)
        val cutoff = Reports.retentionCutoff(ms(12, 0), zone)
        assertEquals(LocalDate.of(2026, 10, 3).atStartOfDay(zone).toInstant().toEpochMilli(), cutoff)
    }
}

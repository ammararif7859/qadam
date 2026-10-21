package de.ammar.qadam.core.rules

import de.ammar.qadam.core.model.EventType
import de.ammar.qadam.core.model.TimeWindow
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** SPEC §8 — tightening at once, loosening after 24 hours. */
class CommitmentTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = LocalDateTime.of(2026, 11, 2, 21, 45).atZone(zone).toInstant().toEpochMilli()
    private val base = Settings(
        driftApps = setOf("pipe", "yt"),
        maxLevel = 3,
        grayscale = true,
        mealWindows = listOf(TimeRange.parse("13:00-14:00")),
    )

    private fun immediate(edit: SettingEdit, s: Settings = base) =
        assertIs<Decision.Immediate>(Commitment.request(s, emptyList(), edit, now), "$edit should apply at once")

    private fun pending(edit: SettingEdit, s: Settings = base) =
        assertIs<Decision.Pending>(Commitment.request(s, emptyList(), edit, now), "$edit should wait 24 h")

    @Test
    fun `tightenings apply at once`() {
        assertEquals(8 * 60_000L, immediate(SettingEdit.SetThreshold("drift_screen.min", 8.0)).settings.thresholds().driftScreenMs)
        assertTrue("tiktok" in immediate(SettingEdit.AddDriftApp("tiktok")).settings.driftApps)
        assertEquals(3, immediate(SettingEdit.SetMaxLevel(3), base.copy(maxLevel = 1)).settings.maxLevel)
        immediate(SettingEdit.SetThreshold("reset.steps", 80.0))
        immediate(SettingEdit.SetThreshold("level3.block_min", 30.0))
        immediate(SettingEdit.SetBedtime(LocalTime.of(23, 0)))
        immediate(SettingEdit.SetMealWindows(listOf(TimeRange.parse("12:30-14:30"))))
        immediate(SettingEdit.SetGrayscale(true), base.copy(grayscale = false))
        immediate(SettingEdit.RemoveAllowedApp("whatsapp"))
        immediate(SettingEdit.SetPlan(TimeWindow.NIGHT, "Phone on the desk, then brush teeth."))
    }

    @Test
    fun `loosenings wait 24 hours`() {
        val p = pending(SettingEdit.SetThreshold("drift_screen.min", 15.0))
        assertEquals(now + 24 * 3_600_000L, p.change.effectiveMs)
        assertEquals(EventType.SETTING_PENDING, p.event.type)
        pending(SettingEdit.RemoveDriftApp("yt"))
        pending(SettingEdit.SetMaxLevel(1))
        pending(SettingEdit.SetGrayscale(false))
        pending(SettingEdit.SetBedtime(LocalTime.of(0, 30)))
        pending(SettingEdit.SetNightEnd(LocalTime.of(5, 0)))
        pending(SettingEdit.SetMealWindows(emptyList()))
        pending(SettingEdit.SetThreshold("reset.steps", 30.0))
        pending(SettingEdit.SetThreshold("level3.override_per_day", 3.0))
        pending(SettingEdit.AddAllowedApp("yt")) // would stop yt being a drift app
        pending(SettingEdit.LongPause(4))
    }

    @Test
    fun `allowing a non-drift app is not a loosening`() {
        immediate(SettingEdit.AddAllowedApp("banking"))
    }

    @Test
    fun `the label says when a loosening becomes active`() {
        val p = pending(SettingEdit.SetThreshold("drift_screen.min", 15.0))
        assertEquals("active tomorrow at 21:45", Commitment.label(p.change.effectiveMs, now, zone))
        assertEquals("active today at 23:00", Commitment.label(now + 75 * 60_000L, now, zone))
        assertEquals("active on 5 Nov at 21:45", Commitment.label(now + 3 * 86_400_000L, now, zone))
    }

    @Test
    fun `pending changes mature after 24 hours, not before`() {
        val p = pending(SettingEdit.SetThreshold("drift_screen.min", 15.0)).change
        val early = Commitment.mature(base, listOf(p), now + 23 * 3_600_000L)
        assertEquals(base, early.settings)
        assertEquals(listOf(p), early.remaining)
        val late = Commitment.mature(base, listOf(p), now + 24 * 3_600_000L)
        assertEquals(15 * 60_000L, late.settings.thresholds().driftScreenMs)
        assertTrue(late.remaining.isEmpty())
        assertEquals(EventType.SETTING_APPLIED, late.events.single().type)
    }

    @Test
    fun `a matured long pause reports when the pause ends`() {
        val p = pending(SettingEdit.LongPause(4)).change
        val m = Commitment.mature(base, listOf(p), p.effectiveMs)
        assertEquals(listOf(p.effectiveMs + 4 * 3_600_000L), m.pauseUntil)
    }

    @Test
    fun `a tightening of the same thing voids the pending loosening`() {
        val p = pending(SettingEdit.SetThreshold("drift_screen.min", 15.0)).change
        val d = assertIs<Decision.Immediate>(
            Commitment.request(base, listOf(p), SettingEdit.SetThreshold("drift_screen.min", 9.0), now),
        )
        assertEquals(listOf(p), d.cancelled)
        val other = assertIs<Decision.Immediate>(
            Commitment.request(base, listOf(p), SettingEdit.AddDriftApp("x"), now),
        )
        assertTrue(other.cancelled.isEmpty())
    }

    @Test
    fun `fixed values, protected apps and bad numbers are rejected`() {
        assertIs<Decision.Rejected>(Commitment.request(base, emptyList(), SettingEdit.SetThreshold("sensors.rate_hz", 50.0), now))
        assertIs<Decision.Rejected>(Commitment.request(base, emptyList(), SettingEdit.SetThreshold("nope.min", 1.0), now))
        assertIs<Decision.Rejected>(Commitment.request(base, emptyList(), SettingEdit.SetThreshold("drift_screen.min", -1.0), now))
        assertIs<Decision.Rejected>(Commitment.request(base, emptyList(), SettingEdit.SetThreshold("danger.factor", 2.0), now))
        assertIs<Decision.Rejected>(Commitment.request(base, emptyList(), SettingEdit.AddDriftApp("com.android.settings"), now))
        assertIs<Decision.Rejected>(Commitment.request(base, emptyList(), SettingEdit.SetMaxLevel(4), now))
    }

    @Test
    fun `setting the same value again is not a loosening`() {
        assertFalse(Commitment.isLoosening(SettingEdit.SetThreshold("drift_screen.min", 10.0), base))
    }

    @Test
    fun `edits survive the trip through text`() {
        val edits = listOf(
            SettingEdit.SetThreshold("drift_screen.min", 12.5),
            SettingEdit.AddDriftApp("a.b"), SettingEdit.RemoveDriftApp("a.b"),
            SettingEdit.AddAllowedApp("c.d"), SettingEdit.RemoveAllowedApp("c.d"),
            SettingEdit.SetBedtime(LocalTime.of(23, 15)), SettingEdit.SetNightEnd(LocalTime.of(6, 30)),
            SettingEdit.SetMealWindows(listOf(TimeRange.parse("13:00-14:00"), TimeRange.parse("19:30-20:30"))),
            SettingEdit.SetMealWindows(emptyList()),
            SettingEdit.SetPlan(TimeWindow.MEAL, "After eating → plate to the sink"),
            SettingEdit.SetMaxLevel(1), SettingEdit.SetGrayscale(false), SettingEdit.LongPause(6),
        )
        for (e in edits) assertEquals(e, SettingEdit.decode(e.encode()))
    }
}

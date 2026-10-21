package de.ammar.qadam.core.rules

import de.ammar.qadam.core.config.Knobs
import de.ammar.qadam.core.config.Looser
import de.ammar.qadam.core.model.EventRow
import de.ammar.qadam.core.model.EventType
import de.ammar.qadam.core.model.TimeWindow
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One change on the Rules screen. [target] names what it changes, to match pending changes. */
sealed interface SettingEdit {
    val target: String

    data class SetThreshold(val key: String, val value: Double) : SettingEdit {
        override val target get() = "threshold:$key"
    }

    data class AddDriftApp(val pkg: String) : SettingEdit {
        override val target get() = "drift:$pkg"
    }

    data class RemoveDriftApp(val pkg: String) : SettingEdit {
        override val target get() = "drift:$pkg"
    }

    data class AddAllowedApp(val pkg: String) : SettingEdit {
        override val target get() = "allowed:$pkg"
    }

    data class RemoveAllowedApp(val pkg: String) : SettingEdit {
        override val target get() = "allowed:$pkg"
    }

    data class SetBedtime(val time: LocalTime) : SettingEdit {
        override val target get() = "night"
    }

    data class SetNightEnd(val time: LocalTime) : SettingEdit {
        override val target get() = "night"
    }

    data class SetMealWindows(val windows: List<TimeRange>) : SettingEdit {
        override val target get() = "meals"
    }

    data class SetPlan(val window: TimeWindow, val text: String) : SettingEdit {
        override val target get() = "plan:${window.name}"
    }

    data class SetMaxLevel(val level: Int) : SettingEdit {
        override val target get() = "maxLevel"
    }

    data class SetGrayscale(val on: Boolean) : SettingEdit {
        override val target get() = "grayscale"
    }

    /** A pause longer than the daily hour: always waits 24 h, then pauses for [hours]. */
    data class LongPause(val hours: Int) : SettingEdit {
        override val target get() = "longPause"
    }

    /** Text form for the database and the event log. Never contains a ';'. */
    fun encode(): String = when (this) {
        is SetThreshold -> "threshold|$key|$value"
        is AddDriftApp -> "addDrift|$pkg"
        is RemoveDriftApp -> "removeDrift|$pkg"
        is AddAllowedApp -> "addAllowed|$pkg"
        is RemoveAllowedApp -> "removeAllowed|$pkg"
        is SetBedtime -> "bedtime|$time"
        is SetNightEnd -> "nightEnd|$time"
        is SetMealWindows -> "meals|" + windows.joinToString(",")
        is SetPlan -> "plan|${window.name}|" + text.replace("|", "/").replace(";", ",").replace("\n", " ")
        is SetMaxLevel -> "maxLevel|$level"
        is SetGrayscale -> "grayscale|$on"
        is LongPause -> "longPause|$hours"
    }

    companion object {
        fun decode(s: String): SettingEdit {
            val p = s.split("|", limit = 3)
            return when (p[0]) {
                "threshold" -> SetThreshold(p[1], p[2].toDouble())
                "addDrift" -> AddDriftApp(p[1])
                "removeDrift" -> RemoveDriftApp(p[1])
                "addAllowed" -> AddAllowedApp(p[1])
                "removeAllowed" -> RemoveAllowedApp(p[1])
                "bedtime" -> SetBedtime(LocalTime.parse(p[1]))
                "nightEnd" -> SetNightEnd(LocalTime.parse(p[1]))
                "meals" -> SetMealWindows(
                    if (p.size < 2 || p[1].isBlank()) emptyList() else p[1].split(",").map { TimeRange.parse(it) },
                )
                "plan" -> SetPlan(TimeWindow.valueOf(p[1]), p.getOrElse(2) { "" })
                "maxLevel" -> SetMaxLevel(p[1].toInt())
                "grayscale" -> SetGrayscale(p[1].toBoolean())
                "longPause" -> LongPause(p[1].toInt())
                else -> throw IllegalArgumentException("Unknown setting edit '$s'")
            }
        }
    }
}

/** A loosening waiting out its 24 hours. */
data class PendingChange(val edit: SettingEdit, val requestedMs: Long, val effectiveMs: Long)

/** What happens to a requested change. */
sealed interface Decision {
    /** Takes effect now. [cancelled] are pending loosenings of the same thing, now void. */
    data class Immediate(val settings: Settings, val event: EventRow, val cancelled: List<PendingChange>) : Decision

    /** A loosening: waits until [PendingChange.effectiveMs]. */
    data class Pending(val change: PendingChange, val event: EventRow) : Decision

    data class Rejected(val reason: String) : Decision
}

/** Result of [Commitment.mature]. */
data class Matured(
    val settings: Settings,
    val applied: List<PendingChange>,
    val remaining: List<PendingChange>,
    val events: List<EventRow>,
    /** Long pauses that start now: pause until these times. */
    val pauseUntil: List<Long>,
)

/**
 * SPEC §8: rules set when calm can be tightened at once, but loosened only after 24 hours.
 * "Loosening" means anything that makes the app fire less or block less.
 */
object Commitment {
    const val DELAY_MS = 24 * 60 * 60_000L

    fun isLoosening(edit: SettingEdit, s: Settings): Boolean = when (edit) {
        is SettingEdit.SetThreshold -> {
            val now = s.thresholds()[edit.key]
            edit.value != now && Knobs.isLoosening(edit.key, now, edit.value)
        }
        is SettingEdit.AddDriftApp -> false
        is SettingEdit.RemoveDriftApp -> edit.pkg in s.driftApps
        is SettingEdit.AddAllowedApp -> edit.pkg in s.driftApps // it would stop being a drift app
        is SettingEdit.RemoveAllowedApp -> false
        is SettingEdit.SetBedtime -> !nightMinutes(s).let { old -> old.all { it in nightMinutes(apply(edit, s)) } }
        is SettingEdit.SetNightEnd -> !nightMinutes(s).let { old -> old.all { it in nightMinutes(apply(edit, s)) } }
        is SettingEdit.SetMealWindows -> !mealMinutes(s).let { old -> old.all { it in mealMinutes(apply(edit, s)) } }
        is SettingEdit.SetPlan -> false
        is SettingEdit.SetMaxLevel -> edit.level < s.maxLevel
        is SettingEdit.SetGrayscale -> s.grayscale && !edit.on
        is SettingEdit.LongPause -> true
    }

    fun apply(edit: SettingEdit, s: Settings): Settings = when (edit) {
        is SettingEdit.SetThreshold -> s.copy(thresholdOverrides = s.thresholdOverrides + (edit.key to edit.value))
        is SettingEdit.AddDriftApp -> s.copy(driftApps = s.driftApps + edit.pkg)
        is SettingEdit.RemoveDriftApp -> s.copy(driftApps = s.driftApps - edit.pkg)
        is SettingEdit.AddAllowedApp -> s.copy(allowedApps = s.allowedApps + edit.pkg)
        is SettingEdit.RemoveAllowedApp -> s.copy(allowedApps = s.allowedApps - edit.pkg)
        is SettingEdit.SetBedtime -> s.copy(bedtime = edit.time)
        is SettingEdit.SetNightEnd -> s.copy(nightEnd = edit.time)
        is SettingEdit.SetMealWindows -> s.copy(mealWindows = edit.windows)
        is SettingEdit.SetPlan -> s.copy(plans = s.plans + (edit.window to edit.text))
        is SettingEdit.SetMaxLevel -> s.copy(maxLevel = edit.level)
        is SettingEdit.SetGrayscale -> s.copy(grayscale = edit.on)
        is SettingEdit.LongPause -> s
    }

    private fun nightMinutes(s: Settings): Set<Int> =
        if (s.bedtime == s.nightEnd) emptySet() else TimeRange(s.bedtime, s.nightEnd).minutes()

    private fun mealMinutes(s: Settings): Set<Int> = s.mealWindows.flatMap { it.minutes() }.toSet()

    /** Decides what happens to [edit] requested at [now]. */
    fun request(s: Settings, pending: List<PendingChange>, edit: SettingEdit, now: Long): Decision {
        validate(edit)?.let { return Decision.Rejected(it) }
        return if (isLoosening(edit, s)) {
            val change = PendingChange(edit, now, now + DELAY_MS)
            Decision.Pending(change, EventRow(now, EventType.SETTING_PENDING, "${edit.encode()} until ${change.effectiveMs}"))
        } else {
            // A tightening (or neutral change) applies now and voids pending loosenings of the same thing.
            val cancelled = pending.filter { it.edit.target == edit.target }
            Decision.Immediate(apply(edit, s), EventRow(now, EventType.SETTING_CHANGE, edit.encode()), cancelled)
        }
    }

    /** Cancelling a pending loosening is a tightening, so it always works at once. */
    fun cancel(change: PendingChange, now: Long): EventRow =
        EventRow(now, EventType.SETTING_CHANGE, "cancelled pending ${change.edit.encode()}")

    /** Applies every pending change whose 24 hours are over, oldest first. */
    fun mature(s: Settings, pending: List<PendingChange>, now: Long): Matured {
        val (due, later) = pending.sortedBy { it.requestedMs }.partition { it.effectiveMs <= now }
        var cur = s
        val events = mutableListOf<EventRow>()
        val pauses = mutableListOf<Long>()
        for (c in due) {
            cur = apply(c.edit, cur)
            if (c.edit is SettingEdit.LongPause) pauses += c.effectiveMs + c.edit.hours * 3_600_000L
            events += EventRow(now, EventType.SETTING_APPLIED, c.edit.encode())
        }
        return Matured(cur, due, later, events, pauses)
    }

    private fun validate(edit: SettingEdit): String? = when (edit) {
        is SettingEdit.SetThreshold -> when {
            runCatching { Knobs.of(edit.key) }.isFailure -> "unknown threshold"
            Knobs.of(edit.key).looser == Looser.FIXED -> "this value can only be changed in the file"
            !(edit.value >= 0.0 && edit.value.isFinite()) -> "must be a number ≥ 0"
            edit.key == "danger.factor" && (edit.value <= 0.0 || edit.value > 1.0) -> "must be between 0 and 1"
            else -> null
        }
        is SettingEdit.SetMaxLevel -> if (edit.level !in 0..3) "level must be 0–3" else null
        is SettingEdit.LongPause -> if (edit.hours !in 2..72) "2 to 72 hours" else null
        is SettingEdit.AddDriftApp -> if (edit.pkg in ProtectedApps.BUILT_IN) "this app can never be blocked" else null
        else -> null
    }

    /** "active today at 21:45", "active tomorrow at 21:45" or "active on 24 Oct at 21:45". */
    fun label(effectiveMs: Long, now: Long, zone: ZoneId): String {
        val eff = Instant.ofEpochMilli(effectiveMs).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val hhmm = eff.format(DateTimeFormatter.ofPattern("HH:mm"))
        return when (eff.toLocalDate()) {
            today -> "active today at $hhmm"
            today.plusDays(1) -> "active tomorrow at $hhmm"
            else -> "active on ${eff.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))} at $hhmm"
        }
    }
}

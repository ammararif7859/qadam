package de.ammar.qadam.core.csv

import de.ammar.qadam.core.model.EventRow
import de.ammar.qadam.core.model.SummaryRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes rows as CSV for the export (SPEC §10 Data screen).
 * Columns are exactly SPEC §4's summary row; the Phase 1 script reads this format.
 * Times are local ISO-8601 with offset, e.g. `2026-10-21T23:41:30+02:00`.
 * Booleans are 1/0. A missing reading is an empty cell.
 */
object Csv {
    val SUMMARY_HEADER: List<String> = listOf(
        "time", "screen_on", "app", "app_category", "session_s", "scrolls", "taps", "steps",
        "steps_last_5min", "motion_rms_mean", "motion_rms_p90", "gravity_x", "gravity_y",
        "gravity_z", "proximity_near", "lux", "audio", "headphones", "charging", "chore_mode",
        "window", "state", "level", "label",
    )

    val EVENT_HEADER: List<String> = listOf("time", "type", "detail")

    private val TIME: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun time(ms: Long, zone: ZoneId): String =
        TIME.format(Instant.ofEpochMilli(ms).atZone(zone).toOffsetDateTime().withNano(0))

    private fun b(v: Boolean) = if (v) "1" else "0"

    private fun d(v: Double?, decimals: Int = 3): String =
        if (v == null || v.isNaN()) "" else String.format(Locale.ROOT, "%.${decimals}f", v)

    /** Quotes a cell if it contains a comma, quote or line break (RFC 4180). */
    fun escape(cell: String): String =
        if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + cell.replace("\"", "\"\"") + "\""
        } else {
            cell
        }

    fun summaryLine(r: SummaryRow, zone: ZoneId): String = listOf(
        time(r.timeMs, zone),
        b(r.screenOn),
        r.app ?: "",
        r.appCategory?.name ?: "",
        r.sessionS.toString(),
        r.scrolls.toString(),
        r.taps.toString(),
        r.steps.toString(),
        r.stepsLast5min.toString(),
        d(r.motionRmsMean),
        d(r.motionRmsP90),
        d(r.gravityX),
        d(r.gravityY),
        d(r.gravityZ),
        d(r.proximityNear, 2),
        d(r.lux, 1),
        b(r.audio),
        b(r.headphones),
        b(r.charging),
        b(r.choreMode),
        r.window.name,
        r.state.name,
        r.level.toString(),
        r.label?.csv ?: "",
    ).joinToString(",") { escape(it) }

    fun eventLine(e: EventRow, zone: ZoneId): String =
        listOf(time(e.timeMs, zone), e.type.name, e.detail).joinToString(",") { escape(it) }

    /** Streams a whole summary export: header, then one line per row. */
    fun writeSummary(rows: Sequence<SummaryRow>, zone: ZoneId, out: Appendable) {
        out.append(SUMMARY_HEADER.joinToString(",")).append('\n')
        for (r in rows) out.append(summaryLine(r, zone)).append('\n')
    }

    fun writeEvents(rows: Sequence<EventRow>, zone: ZoneId, out: Appendable) {
        out.append(EVENT_HEADER.joinToString(",")).append('\n')
        for (e in rows) out.append(eventLine(e, zone)).append('\n')
    }

    /** Splits one CSV line into cells, honouring quotes. Used by tests and the import check. */
    fun parseLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { cells += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        cells += cur.toString()
        return cells
    }
}

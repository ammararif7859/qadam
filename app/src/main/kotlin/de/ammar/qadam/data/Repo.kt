package de.ammar.qadam.data

import de.ammar.qadam.core.csv.Csv
import de.ammar.qadam.core.engine.EngineState
import de.ammar.qadam.core.logger.DayTotals
import de.ammar.qadam.core.logger.Reports
import de.ammar.qadam.core.model.AppCategory
import de.ammar.qadam.core.model.DriftState
import de.ammar.qadam.core.model.EventRow
import de.ammar.qadam.core.model.EventType
import de.ammar.qadam.core.model.Label
import de.ammar.qadam.core.model.SummaryRow
import de.ammar.qadam.core.model.TimeWindow
import de.ammar.qadam.core.rules.Commitment
import de.ammar.qadam.core.rules.Decision
import de.ammar.qadam.core.rules.PendingChange
import de.ammar.qadam.core.rules.SettingEdit
import de.ammar.qadam.core.rules.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.Writer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * The only way the rest of the app touches the database. It converts between `core`'s
 * types and the stored rows, and applies the §8 commitment rules to settings changes.
 */
class Repo(private val db: QadamDb) {
    private val dao = db.dao()

    // ---------------------------------------------------------------- settings (§8)

    suspend fun settings(): Settings = Settings.decode(dao.kv(K_SETTINGS)?.value)

    val settingsFlow: Flow<Settings> = dao.kvFlow(K_SETTINGS).map { Settings.decode(it?.value) }

    private suspend fun saveSettings(s: Settings) = dao.putKv(KvEntity(K_SETTINGS, s.encode()))

    suspend fun pending(): List<Pair<Long, PendingChange>> = dao.pending().mapNotNull { it.toCore() }

    val pendingFlow: Flow<List<Pair<Long, PendingChange>>> =
        dao.pendingFlow().map { list -> list.mapNotNull { it.toCore() } }

    private fun PendingEntity.toCore(): Pair<Long, PendingChange>? =
        runCatching { id to PendingChange(SettingEdit.decode(edit), requestedMs, effectiveMs) }.getOrNull()

    /** A change from the Rules screen: applied now (tightening) or stored as pending (loosening). */
    suspend fun request(edit: SettingEdit, now: Long): Decision {
        val s = settings()
        val pend = pending()
        val d = Commitment.request(s, pend.map { it.second }, edit, now)
        when (d) {
            is Decision.Immediate -> {
                saveSettings(d.settings)
                val voidIds = pend.filter { (_, p) -> p in d.cancelled }.map { it.first }
                if (voidIds.isNotEmpty()) dao.deletePending(voidIds)
                addEvents(listOf(d.event))
            }
            is Decision.Pending -> {
                dao.insertPending(PendingEntity(0, d.change.edit.encode(), d.change.requestedMs, d.change.effectiveMs))
                addEvents(listOf(d.event))
            }
            is Decision.Rejected -> {}
        }
        return d
    }

    suspend fun cancelPending(id: Long, now: Long) {
        val p = pending().firstOrNull { it.first == id } ?: return
        dao.deletePending(listOf(id))
        addEvents(listOf(Commitment.cancel(p.second, now)))
    }

    /** Applies pending changes whose 24 hours are over. Returns the new settings and long pauses. */
    suspend fun matureSettings(now: Long): Pair<Settings, List<Long>>? {
        val pend = pending()
        if (pend.none { it.second.effectiveMs <= now }) return null
        val m = Commitment.mature(settings(), pend.map { it.second }, now)
        saveSettings(m.settings)
        dao.deletePending(pend.filter { it.second in m.applied }.map { it.first })
        addEvents(m.events)
        return m.settings to m.pauseUntil
    }

    // ---------------------------------------------------------------- engine state

    suspend fun engineState(): EngineState = EngineState.decode(dao.kv(K_ENGINE)?.value)

    suspend fun saveEngineState(encoded: String) = dao.putKv(KvEntity(K_ENGINE, encoded))

    // ---------------------------------------------------------------- small values

    suspend fun getLong(name: String): Long? = dao.kv(name)?.value?.toLongOrNull()

    suspend fun putLong(name: String, v: Long) = dao.putKv(KvEntity(name, v.toString()))

    suspend fun getString(name: String): String? = dao.kv(name)?.value

    suspend fun putString(name: String, v: String) = dao.putKv(KvEntity(name, v))

    /** A random number made once per install; the sampling plan of each day depends on it. */
    suspend fun installSeed(): Long = getLong(K_SEED) ?: Random.nextLong().also { putLong(K_SEED, it) }

    // ---------------------------------------------------------------- rows and events

    suspend fun addRow(r: SummaryRow) = dao.insertSummary(r.toEntity())

    suspend fun addEvents(events: List<EventRow>) {
        if (events.isNotEmpty()) dao.insertEvents(events.map { EventEntity(0, it.timeMs, it.type.name, it.detail) })
    }

    suspend fun lastHeartbeat(): Long? = getLong(K_HEARTBEAT)

    suspend fun heartbeat(now: Long) = putLong(K_HEARTBEAT, now)

    /** Totals for one day, from the rows if they still exist, else from the kept daily totals. */
    suspend fun totals(date: LocalDate, zone: ZoneId): DayTotals {
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = dao.summaryBetween(from, to).map { it.toCore() }
        // Events from the day before too: an accessibility-off period may start before midnight.
        val events = dao.eventsBetween(from - DAY_MS, to).map { it.toCore() }
        return Reports.totals(date, rows, events, zone)
    }

    suspend fun eventsSince(type: EventType, from: Long): List<EventRow> =
        dao.eventsOfTypeSince(type.name, from).map { it.toCore() }

    /** SPEC §10 retention: rows older than 30 days become daily totals, then are deleted. */
    suspend fun retention(now: Long, zone: ZoneId) {
        val cutoff = Reports.retentionCutoff(now, zone)
        val first = dao.firstSummaryTime() ?: return
        if (first >= cutoff) return
        var day = Instant.ofEpochMilli(first).atZone(zone).toLocalDate()
        val lastDay = Instant.ofEpochMilli(cutoff).atZone(zone).toLocalDate()
        while (day < lastDay) {
            val t = totals(day, zone)
            dao.putDaily(
                DailyEntity(
                    day.toString(), t.driftMin, t.driftAppMin, t.choreMin, t.triggers, t.falsePositives,
                    t.overrides, t.pauses, t.serviceOffMin, t.accessibilityOffMin,
                    t.labels.entries.joinToString(",") { "${it.key.csv}:${it.value}" },
                ),
            )
            day = day.plusDays(1)
        }
        dao.deleteSummaryBefore(cutoff)
    }

    suspend fun dailyTotals(): List<DailyEntity> = dao.daily()

    suspend fun summaryCount(): Int = dao.summaryCount()

    // ---------------------------------------------------------------- export (§10)

    /** Writes the summary rows as CSV, page by page. */
    suspend fun exportSummary(out: Writer, zone: ZoneId): Int {
        out.append(Csv.SUMMARY_HEADER.joinToString(",")).append('\n')
        var after = 0L
        var n = 0
        while (true) {
            val page = dao.summaryPage(after, PAGE)
            if (page.isEmpty()) break
            for (e in page) out.append(Csv.summaryLine(e.toCore(), zone)).append('\n')
            n += page.size
            after = page.last().id
        }
        out.flush()
        return n
    }

    suspend fun exportEvents(out: Writer, zone: ZoneId): Int {
        out.append(Csv.EVENT_HEADER.joinToString(",")).append('\n')
        var after = 0L
        var n = 0
        while (true) {
            val page = dao.eventPage(after, PAGE)
            if (page.isEmpty()) break
            for (e in page) out.append(Csv.eventLine(e.toCore(), zone)).append('\n')
            n += page.size
            after = page.last().id
        }
        out.flush()
        return n
    }

    /** SPEC §10 "delete all data": rows, events and daily totals. Settings stay. */
    suspend fun deleteAllData(now: Long) {
        dao.deleteAllSummary()
        dao.deleteAllEvents()
        dao.deleteAllDaily()
        addEvents(listOf(EventRow(now, EventType.DATA_DELETED)))
    }

    companion object {
        const val K_SETTINGS = "settings"
        const val K_ENGINE = "engine"
        const val K_HEARTBEAT = "heartbeat"
        const val K_SEED = "installSeed"
        const val K_MORNING = "morningShownDay"
        const val K_RETENTION = "retentionDay"
        const val K_SAMPLED = "sampledPrompts" // "date|ms,ms,…" — planned prompt times already shown or dropped
        const val K_TAG_NAMES = "tagNames"
        private const val PAGE = 2000
        private const val DAY_MS = 86_400_000L
    }
}

// ---------------------------------------------------------------- conversions

fun SummaryRow.toEntity() = SummaryEntity(
    0, timeMs, screenOn, app, appCategory?.name, sessionS, scrolls, taps, steps, stepsLast5min,
    motionRmsMean, motionRmsP90, gravityX, gravityY, gravityZ, proximityNear, lux, audio, headphones,
    charging, choreMode, window.name, state.name, level, label?.csv,
)

fun SummaryEntity.toCore() = SummaryRow(
    timeMs = timeMs,
    screenOn = screenOn,
    app = app,
    appCategory = appCategory?.let { runCatching { AppCategory.valueOf(it) }.getOrNull() },
    sessionS = sessionS,
    scrolls = scrolls,
    taps = taps,
    steps = steps,
    stepsLast5min = stepsLast5min,
    motionRmsMean = motionRmsMean,
    motionRmsP90 = motionRmsP90,
    gravityX = gravityX,
    gravityY = gravityY,
    gravityZ = gravityZ,
    proximityNear = proximityNear,
    lux = lux,
    audio = audio,
    headphones = headphones,
    charging = charging,
    choreMode = choreMode,
    window = runCatching { TimeWindow.valueOf(window) }.getOrDefault(TimeWindow.DAY),
    state = runCatching { DriftState.valueOf(state) }.getOrDefault(DriftState.IDLE),
    level = level,
    label = label?.let { Label.fromCsv(it) },
)

fun EventEntity.toCore() = EventRow(
    timeMs, runCatching { EventType.valueOf(type) }.getOrDefault(EventType.SETTING_CHANGE), detail,
)

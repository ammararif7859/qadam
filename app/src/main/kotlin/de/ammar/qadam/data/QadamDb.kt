package de.ammar.qadam.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One SPEC §4 summary row, as stored. Mirrors `core`'s SummaryRow. */
@Entity(tableName = "summary", indices = [Index("timeMs")])
data class SummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val screenOn: Boolean,
    val app: String?,
    val appCategory: String?,
    val sessionS: Int,
    val scrolls: Int,
    val taps: Int,
    val steps: Int,
    val stepsLast5min: Int,
    val motionRmsMean: Double?,
    val motionRmsP90: Double?,
    val gravityX: Double?,
    val gravityY: Double?,
    val gravityZ: Double?,
    val proximityNear: Double?,
    val lux: Double?,
    val audio: Boolean,
    val headphones: Boolean,
    val charging: Boolean,
    val choreMode: Boolean,
    val window: String,
    val state: String,
    val level: Int,
    val label: String?,
)

/** One event row (SPEC §4): what happened, when, a short detail. */
@Entity(tableName = "event", indices = [Index("timeMs")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val type: String,
    val detail: String,
)

/** Small named values: the settings text, the engine state, the last heartbeat … */
@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey val name: String,
    val value: String,
)

/** A loosening waiting out its 24 hours (SPEC §8). */
@Entity(tableName = "pending")
data class PendingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val edit: String,
    val requestedMs: Long,
    val effectiveMs: Long,
)

/** What is kept of a day after its summary rows are deleted (SPEC §10: 30 days). */
@Entity(tableName = "daily")
data class DailyEntity(
    @PrimaryKey val date: String,
    val driftMin: Double,
    val driftAppMin: Double,
    val choreMin: Double,
    val triggers: Int,
    val falsePositives: Int,
    val overrides: Int,
    val pauses: Int,
    val serviceOffMin: Double,
    val accessibilityOffMin: Double,
    val labels: String,
)

/** DAO — "data access object": the list of database queries the app uses. */
@Dao
interface QadamDao {
    @Insert
    suspend fun insertSummary(row: SummaryEntity)

    @Insert
    suspend fun insertEvents(rows: List<EventEntity>)

    @Query("SELECT * FROM summary WHERE timeMs >= :from AND timeMs < :to ORDER BY timeMs")
    suspend fun summaryBetween(from: Long, to: Long): List<SummaryEntity>

    @Query("SELECT * FROM event WHERE timeMs >= :from AND timeMs < :to ORDER BY timeMs")
    suspend fun eventsBetween(from: Long, to: Long): List<EventEntity>

    @Query("SELECT * FROM event WHERE type = :type AND timeMs >= :from ORDER BY timeMs")
    suspend fun eventsOfTypeSince(type: String, from: Long): List<EventEntity>

    /** Export in pages, ordered by id, so a big table never sits in memory at once. */
    @Query("SELECT * FROM summary WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun summaryPage(afterId: Long, limit: Int): List<SummaryEntity>

    @Query("SELECT * FROM event WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun eventPage(afterId: Long, limit: Int): List<EventEntity>

    @Query("SELECT MIN(timeMs) FROM summary")
    suspend fun firstSummaryTime(): Long?

    @Query("SELECT COUNT(*) FROM summary")
    suspend fun summaryCount(): Int

    @Query("SELECT * FROM kv WHERE name = :name")
    suspend fun kv(name: String): KvEntity?

    @Query("SELECT * FROM kv WHERE name = :name")
    fun kvFlow(name: String): Flow<KvEntity?>

    @Upsert
    suspend fun putKv(kv: KvEntity)

    @Query("SELECT * FROM pending ORDER BY requestedMs")
    suspend fun pending(): List<PendingEntity>

    @Query("SELECT * FROM pending ORDER BY requestedMs")
    fun pendingFlow(): Flow<List<PendingEntity>>

    @Insert
    suspend fun insertPending(p: PendingEntity): Long

    @Query("DELETE FROM pending WHERE id IN (:ids)")
    suspend fun deletePending(ids: List<Long>)

    @Upsert
    suspend fun putDaily(d: DailyEntity)

    @Query("SELECT * FROM daily ORDER BY date DESC")
    suspend fun daily(): List<DailyEntity>

    @Query("DELETE FROM summary WHERE timeMs < :cutoff")
    suspend fun deleteSummaryBefore(cutoff: Long)

    @Query("DELETE FROM summary")
    suspend fun deleteAllSummary()

    @Query("DELETE FROM event")
    suspend fun deleteAllEvents()

    @Query("DELETE FROM daily")
    suspend fun deleteAllDaily()
}

/** The database itself: one file in the app's private storage. */
@Database(
    entities = [SummaryEntity::class, EventEntity::class, KvEntity::class, PendingEntity::class, DailyEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class QadamDb : RoomDatabase() {
    abstract fun dao(): QadamDao

    companion object {
        fun open(context: Context): QadamDb =
            Room.databaseBuilder(context.applicationContext, QadamDb::class.java, "qadam.db").build()
    }
}

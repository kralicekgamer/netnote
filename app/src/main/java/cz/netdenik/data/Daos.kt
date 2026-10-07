package cz.netdenik.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface NetDao {
    @Insert suspend fun insert(row: StateSegment): Long
    @Insert suspend fun insert(row: Event): Long
    @Insert suspend fun insert(row: WifiSample): Long
    @Insert suspend fun insert(row: CellSample): Long
    @Insert suspend fun insert(row: PingSample): Long
    @Insert suspend fun insert(row: SpeedSample): Long
    @Insert suspend fun insert(row: TrafficSample): Long
    @Insert suspend fun insert(row: Tick): Long

    @Query("UPDATE state_segment SET endMs = :endMs WHERE id = :id")
    suspend fun setSegmentEnd(id: Long, endMs: Long)

    @Query("SELECT * FROM state_segment ORDER BY id DESC LIMIT 1")
    suspend fun lastSegment(): StateSegment?

    @Query("SELECT * FROM state_segment ORDER BY id DESC LIMIT 1")
    fun lastSegmentFlow(): Flow<StateSegment?>

    @Query("SELECT * FROM state_segment WHERE endMs > :from AND startMs < :to ORDER BY startMs, id")
    suspend fun segmentsBetween(from: Long, to: Long): List<StateSegment>

    @Query("SELECT * FROM event ORDER BY id DESC LIMIT :limit")
    fun recentEvents(limit: Int): Flow<List<Event>>

    @Query("SELECT * FROM event WHERE ts >= :from AND ts < :to ORDER BY id")
    suspend fun eventsBetween(from: Long, to: Long): List<Event>

    @Query("SELECT * FROM wifi_sample ORDER BY id DESC LIMIT 1")
    fun latestWifi(): Flow<WifiSample?>

    @Query("SELECT * FROM cell_sample ORDER BY id DESC LIMIT 1")
    fun latestCell(): Flow<CellSample?>

    @Query("SELECT * FROM ping_sample ORDER BY id DESC LIMIT 1")
    fun latestPing(): Flow<PingSample?>

    @Query("SELECT * FROM speed_sample ORDER BY id DESC LIMIT 1")
    fun latestSpeed(): Flow<SpeedSample?>

    @Query("SELECT * FROM traffic_sample ORDER BY id DESC LIMIT 1")
    fun latestTraffic(): Flow<TrafficSample?>

    @Query("SELECT * FROM tick ORDER BY id DESC LIMIT 1")
    fun latestTick(): Flow<Tick?>

    @Query("SELECT * FROM ping_sample WHERE ts >= :from AND ts < :to ORDER BY id")
    suspend fun pingsBetween(from: Long, to: Long): List<PingSample>

    @Query("SELECT * FROM speed_sample WHERE ts >= :from AND ts < :to ORDER BY id")
    suspend fun speedsBetween(from: Long, to: Long): List<SpeedSample>

    @Query("SELECT * FROM traffic_sample WHERE ts >= :from AND ts < :to ORDER BY id")
    suspend fun trafficBetween(from: Long, to: Long): List<TrafficSample>

    // ---- čtení pro obrazovky ----

    @Query("SELECT * FROM wifi_sample WHERE ts >= :from AND ts < :to ORDER BY ts, id")
    suspend fun wifiBetween(from: Long, to: Long): List<WifiSample>

    @Query("SELECT * FROM cell_sample WHERE ts >= :from AND ts < :to ORDER BY ts, id")
    suspend fun cellsBetween(from: Long, to: Long): List<CellSample>

    @Query("SELECT * FROM state_segment WHERE id = :id")
    suspend fun segmentById(id: Long): StateSegment?

    @Query("SELECT * FROM state_segment WHERE id < :id ORDER BY id DESC LIMIT 1")
    suspend fun segmentBefore(id: Long): StateSegment?

    @Query("SELECT MIN(startMs) FROM state_segment")
    suspend fun firstSegmentStart(): Long?

    @Query("SELECT MAX(ts) FROM event WHERE type = 'SERVICE_START'")
    suspend fun lastServiceStart(): Long?

    /** Každý přístupový bod, který appka kdy viděla, s názvem sítě z posledního vzorku. */
    @Query("SELECT bssid, ssid, MAX(ts) AS lastSeen FROM wifi_sample WHERE bssid IS NOT NULL GROUP BY bssid ORDER BY lastSeen DESC")
    suspend fun knownAccessPoints(): List<KnownAp>

    @Query("SELECT * FROM tick WHERE ts >= :from AND ts < :to ORDER BY id")
    suspend fun ticksBetween(from: Long, to: Long): List<Tick>

    /** Kolik bajtů přenesly speedtesty na mobilních datech od daného času (pro denní limit). */
    @Query("SELECT COALESCE(SUM(downBytes + upBytes), 0) FROM speed_sample WHERE transport = 'MOBILE' AND ts >= :from")
    suspend fun mobileTestBytesSince(from: Long): Long

    // ---- uchovávání: mazání starých záznamů ----

    /** Úsek se maže až celý; ten, který hranici překračuje, zůstává. */
    @Query("DELETE FROM state_segment WHERE endMs < :before")
    suspend fun deleteSegmentsBefore(before: Long): Int

    @Query("DELETE FROM event WHERE ts < :before")
    suspend fun deleteEventsBefore(before: Long): Int

    @Query("DELETE FROM wifi_sample WHERE ts < :before")
    suspend fun deleteWifiBefore(before: Long): Int

    @Query("DELETE FROM cell_sample WHERE ts < :before")
    suspend fun deleteCellsBefore(before: Long): Int

    @Query("DELETE FROM ping_sample WHERE ts < :before")
    suspend fun deletePingsBefore(before: Long): Int

    @Query("DELETE FROM speed_sample WHERE ts < :before")
    suspend fun deleteSpeedsBefore(before: Long): Int

    @Query("DELETE FROM traffic_sample WHERE ts < :before")
    suspend fun deleteTrafficBefore(before: Long): Int

    @Query("DELETE FROM tick WHERE ts < :before")
    suspend fun deleteTicksBefore(before: Long): Int

    /** @return počet smazaných řádků ve všech tabulkách */
    @Transaction
    suspend fun deleteBefore(before: Long): Int =
        deleteSegmentsBefore(before) + deleteEventsBefore(before) + deleteWifiBefore(before) +
            deleteCellsBefore(before) + deletePingsBefore(before) + deleteSpeedsBefore(before) +
            deleteTrafficBefore(before) + deleteTicksBefore(before)
}

data class KnownAp(val bssid: String, val ssid: String?, val lastSeen: Long)

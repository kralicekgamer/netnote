package cz.kralicekgamer.netnote.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        StateSegment::class,
        Event::class,
        WifiSample::class,
        CellSample::class,
        PingSample::class,
        SpeedSample::class,
        TrafficSample::class,
        Tick::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): NetDao

    companion object {
        /** Pořadí odpovídá pořadí souborů v exportu. */
        val TABLES = listOf(
            "state_segment", "event", "wifi_sample", "cell_sample",
            "ping_sample", "speed_sample", "traffic_sample", "tick",
        )

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "netnote.db")
                // Testovací verze: při změně schématu data zahodit, migrace se zatím nepíšou.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}

package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.RoomDatabase
import com.example.data.local.dao.DataUsageSnapshotDao
import com.example.data.local.dao.NetworkDao
import com.example.data.local.entity.DataUsageSnapshotEntity
import com.example.data.local.entity.NetworkEntity

@Database(
    entities = [
        NetworkEntity::class,
        DataUsageSnapshotEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun networkDao(): NetworkDao
    abstract fun snapshotDao(): DataUsageSnapshotDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Preserve old session records while consolidating duplicate SSID rows.
                db.execSQL("DROP INDEX IF EXISTS index_wifi_networks_ssid")
                db.execSQL("""
                    UPDATE data_usage_snapshots
                    SET networkInternalId = (
                        SELECT MIN(canonical.internalId)
                        FROM wifi_networks AS duplicate
                        JOIN wifi_networks AS canonical ON canonical.ssid = duplicate.ssid
                        WHERE duplicate.internalId = data_usage_snapshots.networkInternalId
                    )
                    WHERE networkInternalId IS NOT NULL
                      AND networkInternalId NOT IN (
                          SELECT MIN(internalId) FROM wifi_networks GROUP BY ssid
                      )
                """.trimIndent())
                db.execSQL("""
                    DELETE FROM wifi_networks
                    WHERE internalId NOT IN (
                        SELECT MIN(internalId) FROM wifi_networks GROUP BY ssid
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_wifi_networks_ssid ON wifi_networks(ssid)"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "wifi_family_tracker.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

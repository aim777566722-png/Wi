package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
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
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun networkDao(): NetworkDao
    abstract fun snapshotDao(): DataUsageSnapshotDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "wifi_family_tracker.db"
                ).fallbackToDestructiveMigration(false)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

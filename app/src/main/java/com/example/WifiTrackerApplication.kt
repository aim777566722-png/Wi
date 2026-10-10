package com.example

import android.app.Application
import com.example.data.local.AppDatabase
import com.example.data.network.WifiNetworkDetector
import com.example.data.repository.WifiTrackerRepository

class WifiTrackerApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var repository: WifiTrackerRepository
        private set

    lateinit var networkDetector: WifiNetworkDetector
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        networkDetector = WifiNetworkDetector(this)
        repository = WifiTrackerRepository(
            context = this,
            networkDao = database.networkDao(),
            snapshotDao = database.snapshotDao(),
            networkDetector = networkDetector
        )
    }
}

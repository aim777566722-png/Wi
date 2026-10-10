package com.example.data.repository

import android.content.Context
import com.example.data.engine.TrafficDeltaCalculator
import com.example.data.local.dao.DataUsageSnapshotDao
import com.example.data.local.dao.NetworkDao
import com.example.data.local.dao.NetworkUsageTuple
import com.example.data.local.dao.TotalUsageTuple
import com.example.data.local.entity.DataUsageSnapshotEntity
import com.example.data.local.entity.NetworkEntity
import com.example.data.model.ComputedDelta
import com.example.data.model.NetworkSummary
import com.example.data.model.RawTrafficReading
import com.example.data.model.ReadingStatus
import com.example.data.network.WifiNetworkDetector
import com.example.data.preferences.MonitoringPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.util.Calendar

class WifiTrackerRepository(
    private val context: Context,
    private val networkDao: NetworkDao,
    private val snapshotDao: DataUsageSnapshotDao,
    private val networkDetector: WifiNetworkDetector
) {

    val allNetworks: Flow<List<NetworkEntity>> = networkDao.getAllNetworks()

    val recentSnapshots: Flow<List<DataUsageSnapshotEntity>> = snapshotDao.getRecentSnapshots(200)

    val latestSnapshot: Flow<DataUsageSnapshotEntity?> = snapshotDao.getLatestSnapshotFlow()

    fun getTodayStartTime(): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    fun getWeekStartTime(): Long {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -7)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    fun getMonthStartTime(): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    fun getTodayUsage(): Flow<TotalUsageTuple?> {
        return snapshotDao.getTotalUsageSince(getTodayStartTime())
    }

    fun getAllTimeUsage(): Flow<TotalUsageTuple?> {
        return snapshotDao.getAllTimeUsage()
    }

    fun getTodayNetworkUsage(): Flow<List<NetworkUsageTuple>> {
        return snapshotDao.getUsageByNetworkSince(getTodayStartTime())
    }

    fun getMonthNetworkUsage(): Flow<List<NetworkUsageTuple>> {
        return snapshotDao.getUsageByNetworkSince(getMonthStartTime())
    }

    fun getAllTimeNetworkUsage(): Flow<List<NetworkUsageTuple>> {
        return snapshotDao.getAllTimeUsageByNetwork()
    }

    fun getSnapshotsForNetwork(ssid: String): Flow<List<DataUsageSnapshotEntity>> {
        return snapshotDao.getSnapshotsForNetwork(ssid)
    }

    fun getSnapshotsForNetworkSince(ssid: String, startTime: Long): Flow<List<DataUsageSnapshotEntity>> {
        return snapshotDao.getSnapshotsForNetworkSince(ssid, startTime)
    }

    fun getSnapshotsForNetworkBetween(ssid: String, startTime: Long, endTime: Long): Flow<List<DataUsageSnapshotEntity>> {
        return snapshotDao.getSnapshotsForNetworkBetween(ssid, startTime, endTime)
    }

    /**
     * تسجيل قراءة جديدة من عدادات النظام واحتساب الزيادة بدقة
     * يمنع تكرار إدخال قراءات متطابقة (No Change) لتفادي تضخيم قاعدة البيانات دون فائدة
     */
    suspend fun recordSnapshot(): Pair<DataUsageSnapshotEntity, ComputedDelta> = withContext(Dispatchers.IO) {
        val rawReading = networkDetector.readCurrentRawTraffic()
        val latest = snapshotDao.getLatestSnapshot()

        val delta = TrafficDeltaCalculator.computeDelta(rawReading, latest)

        val targetSsid = when {
            !rawReading.isWifiConnected -> "غير متصل بـ Wi-Fi"
            rawReading.ssid != null -> rawReading.ssid
            else -> TrafficDeltaCalculator.GENERAL_WIFI_FALLBACK_SSID
        }

        // إذا لم تطرأ أي زيادة وكان السجل السابق لنفس الحالة والشبكة، نتفادى حفظ سجل مكرر فارغ
        if (delta.status == ReadingStatus.NO_CHANGE && latest != null && latest.ssid == targetSsid) {
            MonitoringPreferences.setLastReadingTimestamp(context, rawReading.timestamp)
            return@withContext Pair(latest, delta)
        }

        var networkInternalId: Long? = null

        if (rawReading.isWifiConnected) {
            val existing = networkDao.findBySsid(targetSsid)
            if (existing != null) {
                networkInternalId = existing.internalId
                networkDao.update(existing.copy(lastSeenTimestamp = rawReading.timestamp))
            } else {
                val newNetwork = NetworkEntity(
                    ssid = targetSsid,
                    bssid = rawReading.bssid,
                    firstSeenTimestamp = rawReading.timestamp,
                    lastSeenTimestamp = rawReading.timestamp
                )
                networkInternalId = networkDao.insert(newNetwork)
            }
        }

        val snapshot = DataUsageSnapshotEntity(
            networkInternalId = networkInternalId,
            ssid = targetSsid,
            timestamp = rawReading.timestamp,
            rawRxBytes = rawReading.rawRxBytes,
            rawTxBytes = rawReading.rawTxBytes,
            deltaRxBytes = delta.deltaRxBytes,
            deltaTxBytes = delta.deltaTxBytes,
            readingStatus = delta.status.name,
            bootElapsedTimeMs = rawReading.bootElapsedRealtimeMs,
            deviceId = "local_device",
            notes = delta.notes
        )

        val insertedId = snapshotDao.insert(snapshot)
        val finalSnapshot = snapshot.copy(id = insertedId)

        MonitoringPreferences.setLastReadingTimestamp(context, rawReading.timestamp)

        Pair(finalSnapshot, delta)
    }

    /**
     * تجميع ملخصات الشبكات مع استهلاك اليوم والشهر
     */
    fun getNetworkSummariesFlow(): Flow<List<NetworkSummary>> {
        val todayStart = getTodayStartTime()
        val monthStart = getMonthStartTime()

        return combine(
            allNetworks,
            snapshotDao.getAllTimeUsageByNetwork(),
            snapshotDao.getUsageByNetworkSince(todayStart),
            snapshotDao.getUsageByNetworkSince(monthStart)
        ) { networks, allTimeUsages, todayUsages, monthUsages ->
            val allMap = allTimeUsages.associateBy { it.ssid }
            val todayMap = todayUsages.associateBy { it.ssid }
            val monthMap = monthUsages.associateBy { it.ssid }

            networks.map { network ->
                val allTuple = allMap[network.ssid]
                val todayTuple = todayMap[network.ssid]
                val monthTuple = monthMap[network.ssid]

                NetworkSummary(
                    internalId = network.internalId,
                    ssid = network.ssid,
                    firstSeenTimestamp = network.firstSeenTimestamp,
                    lastSeenTimestamp = network.lastSeenTimestamp,
                    totalRxBytes = allTuple?.rxBytes ?: 0L,
                    totalTxBytes = allTuple?.txBytes ?: 0L,
                    todayRxBytes = todayTuple?.rxBytes ?: 0L,
                    todayTxBytes = todayTuple?.txBytes ?: 0L,
                    monthRxBytes = monthTuple?.rxBytes ?: 0L,
                    monthTxBytes = monthTuple?.txBytes ?: 0L,
                    snapshotsCount = allTuple?.snapshotCount ?: 0
                )
            }
        }
    }
}

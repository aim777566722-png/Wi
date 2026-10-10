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
import com.example.data.model.ReadingStatus
import com.example.data.network.WifiNetworkDetector
import com.example.data.preferences.MonitoringPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class WifiTrackerRepository(
    private val context: Context,
    private val networkDao: NetworkDao,
    private val snapshotDao: DataUsageSnapshotDao,
    private val networkDetector: WifiNetworkDetector
) {
    val allNetworks: Flow<List<NetworkEntity>> = networkDao.getAllNetworks()
    val recentSnapshots: Flow<List<DataUsageSnapshotEntity>> = snapshotDao.getRecentSnapshots(200)
    val latestSnapshot: Flow<DataUsageSnapshotEntity?> = snapshotDao.getLatestSnapshotFlow()

    private val sessionPrefs by lazy {
        context.getSharedPreferences("wifi_session_state", Context.MODE_PRIVATE)
    }

    fun getTodayStartTime(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun getWeekStartTime(): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -7); set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun getMonthStartTime(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun getTodayUsage(): Flow<TotalUsageTuple?> = snapshotDao.getTotalUsageSince(getTodayStartTime())
    fun getAllTimeUsage(): Flow<TotalUsageTuple?> = snapshotDao.getAllTimeUsage()
    fun getTodayNetworkUsage(): Flow<List<NetworkUsageTuple>> = snapshotDao.getUsageByNetworkSince(getTodayStartTime())
    fun getMonthNetworkUsage(): Flow<List<NetworkUsageTuple>> = snapshotDao.getUsageByNetworkSince(getMonthStartTime())
    fun getAllTimeNetworkUsage(): Flow<List<NetworkUsageTuple>> = snapshotDao.getAllTimeUsageByNetwork()
    fun getSnapshotsForNetwork(ssid: String): Flow<List<DataUsageSnapshotEntity>> = snapshotDao.getSnapshotsForNetwork(ssid)
    fun getSnapshotsForNetworkSince(ssid: String, startTime: Long): Flow<List<DataUsageSnapshotEntity>> =
        snapshotDao.getSnapshotsForNetworkSince(ssid, startTime)
    fun getSnapshotsForNetworkBetween(ssid: String, startTime: Long, endTime: Long): Flow<List<DataUsageSnapshotEntity>> =
        snapshotDao.getSnapshotsForNetworkBetween(ssid, startTime, endTime)

    /**
     * Samples counters while connected, but writes exactly one database row when the Wi-Fi
     * session ends. The in-progress session state is persisted in SharedPreferences so that
     * ordinary service restarts do not create a new history row every 45 seconds.
     */
    suspend fun recordSnapshot(): Pair<DataUsageSnapshotEntity, ComputedDelta> = withContext(Dispatchers.IO) {
        val reading = networkDetector.readCurrentRawTraffic()
        val now = reading.timestamp
        val active = sessionPrefs.getBoolean(K_ACTIVE, false)
        val savedSsid = sessionPrefs.getString(K_SSID, null)
        val savedRx = sessionPrefs.getLong(K_LAST_RX, -1L)
        val savedTx = sessionPrefs.getLong(K_LAST_TX, -1L)
        val savedBoot = sessionPrefs.getLong(K_BOOT, -1L)
        var deltaRx = 0L
        var deltaTx = 0L

        if (active) {
            val sameBoot = savedBoot >= 0 && reading.bootElapsedRealtimeMs >= savedBoot
            val sameNetwork = reading.isWifiConnected &&
                (reading.ssid ?: TrafficDeltaCalculator.GENERAL_WIFI_FALLBACK_SSID) == savedSsid

            if (sameBoot && sameNetwork && savedRx >= 0 && savedTx >= 0 &&
                reading.rawRxBytes >= savedRx && reading.rawTxBytes >= savedTx) {
                deltaRx = reading.rawRxBytes - savedRx
                deltaTx = reading.rawTxBytes - savedTx
                sessionPrefs.edit()
                    .putLong(K_TOTAL_RX, sessionPrefs.getLong(K_TOTAL_RX, 0L) + deltaRx)
                    .putLong(K_TOTAL_TX, sessionPrefs.getLong(K_TOTAL_TX, 0L) + deltaTx)
                    .apply()
            }

            if (!reading.isWifiConnected || !sameNetwork || !sameBoot) {
                val ended = finalizeSession(now, reading.rawRxBytes, reading.rawTxBytes)
                if (reading.isWifiConnected) startSession(reading.ssid ?: TrafficDeltaCalculator.GENERAL_WIFI_FALLBACK_SSID, reading.rawRxBytes, reading.rawTxBytes, reading.bootElapsedRealtimeMs, now)
                MonitoringPreferences.setLastReadingTimestamp(context, now)
                val returned = ended ?: (snapshotDao.getLatestSnapshot() ?: emptySnapshot(now))
                return@withContext Pair(returned, ComputedDelta(deltaRx, deltaTx, ReadingStatus.VALID, "تم حفظ جلسة Wi-Fi عند انتهائها"))
            }

            sessionPrefs.edit()
                .putLong(K_LAST_RX, reading.rawRxBytes)
                .putLong(K_LAST_TX, reading.rawTxBytes)
                .putLong(K_BOOT, reading.bootElapsedRealtimeMs)
                .apply()
        } else if (reading.isWifiConnected) {
            val ssid = reading.ssid ?: TrafficDeltaCalculator.GENERAL_WIFI_FALLBACK_SSID
            startSession(ssid, reading.rawRxBytes, reading.rawTxBytes, reading.bootElapsedRealtimeMs, now)
        }

        MonitoringPreferences.setLastReadingTimestamp(context, now)
        val current = snapshotDao.getLatestSnapshot() ?: emptySnapshot(now)
        Pair(current, ComputedDelta(deltaRx, deltaTx, if (reading.isWifiConnected) ReadingStatus.VALID else ReadingStatus.DISCONNECTED_FALLBACK,
            if (reading.isWifiConnected) "الجلسة الحالية قيد التسجيل؛ سيظهر سجلها بعد قطع اتصال Wi-Fi" else null))
    }

    private suspend fun startSession(ssid: String, rx: Long, tx: Long, boot: Long, now: Long) {
        sessionPrefs.edit()
            .putBoolean(K_ACTIVE, true)
            .putString(K_SSID, ssid)
            .putLong(K_START, now)
            .putLong(K_LAST_RX, rx)
            .putLong(K_LAST_TX, tx)
            .putLong(K_BOOT, boot)
            .putLong(K_TOTAL_RX, 0L)
            .putLong(K_TOTAL_TX, 0L)
            .apply()
        val existing = networkDao.findBySsid(ssid)
        if (existing == null) {
            networkDao.insert(NetworkEntity(ssid = ssid, firstSeenTimestamp = now, lastSeenTimestamp = now))
        } else {
            networkDao.update(existing.copy(lastSeenTimestamp = now))
        }
    }

    private suspend fun finalizeSession(endTime: Long, endRx: Long, endTx: Long): DataUsageSnapshotEntity? {
        if (!sessionPrefs.getBoolean(K_ACTIVE, false)) return null
        val ssid = sessionPrefs.getString(K_SSID, null) ?: TrafficDeltaCalculator.GENERAL_WIFI_FALLBACK_SSID
        val start = sessionPrefs.getLong(K_START, endTime)
        val totalRx = sessionPrefs.getLong(K_TOTAL_RX, 0L)
        val totalTx = sessionPrefs.getLong(K_TOTAL_TX, 0L)
        val network = networkDao.findBySsid(ssid)
        val startText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(start))
        val endText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(endTime))
        val entity = DataUsageSnapshotEntity(
            networkInternalId = network?.internalId,
            ssid = ssid,
            timestamp = endTime,
            rawRxBytes = endRx.coerceAtLeast(0L),
            rawTxBytes = endTx.coerceAtLeast(0L),
            deltaRxBytes = totalRx,
            deltaTxBytes = totalTx,
            readingStatus = ReadingStatus.VALID.name,
            bootElapsedTimeMs = sessionPrefs.getLong(K_BOOT, 0L),
            notes = "جلسة Wi-Fi من $startText إلى $endText"
        )
        val id = snapshotDao.insert(entity)
        sessionPrefs.edit().clear().apply()
        return entity.copy(id = id)
    }

    private fun emptySnapshot(now: Long) = DataUsageSnapshotEntity(
        ssid = "لا توجد جلسة مكتملة بعد", timestamp = now, rawRxBytes = 0L, rawTxBytes = 0L,
        deltaRxBytes = 0L, deltaTxBytes = 0L, readingStatus = ReadingStatus.NO_CHANGE.name,
        bootElapsedTimeMs = 0L, notes = "سيظهر السجل بعد انتهاء أول جلسة Wi-Fi"
    )

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
                    internalId = network.internalId, ssid = network.ssid,
                    firstSeenTimestamp = network.firstSeenTimestamp, lastSeenTimestamp = network.lastSeenTimestamp,
                    totalRxBytes = allTuple?.rxBytes ?: 0L, totalTxBytes = allTuple?.txBytes ?: 0L,
                    todayRxBytes = todayTuple?.rxBytes ?: 0L, todayTxBytes = todayTuple?.txBytes ?: 0L,
                    monthRxBytes = monthTuple?.rxBytes ?: 0L, monthTxBytes = monthTuple?.txBytes ?: 0L,
                    snapshotsCount = allTuple?.snapshotCount ?: 0
                )
            }
        }
    }

    companion object {
        private const val K_ACTIVE = "active"
        private const val K_SSID = "ssid"
        private const val K_START = "start"
        private const val K_LAST_RX = "last_rx"
        private const val K_LAST_TX = "last_tx"
        private const val K_BOOT = "boot"
        private const val K_TOTAL_RX = "total_rx"
        private const val K_TOTAL_TX = "total_tx"
    }
}

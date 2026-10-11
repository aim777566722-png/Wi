package com.example.data.repository

import android.content.Context
import com.example.data.engine.TrafficDeltaCalculator
import com.example.data.local.dao.DataUsageSnapshotDao
import com.example.data.local.dao.NetworkDao
import com.example.data.local.dao.NetworkUsageTuple
import com.example.data.local.dao.TotalUsageTuple
import com.example.data.local.entity.DataUsageSnapshotEntity
import com.example.data.local.entity.NetworkEntity
import com.example.data.model.ActiveWifiSessionUsage
import com.example.data.model.ComputedDelta
import com.example.data.model.NetworkSummary
import com.example.data.model.ReadingStatus
import com.example.data.network.WifiNetworkDetector
import com.example.data.preferences.MonitoringPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Persist session totals across service/process restarts and publish them for live UI totals.
    private val recordMutex = Mutex()
    private val _activeSessionUsage = MutableStateFlow(loadActiveSessionUsage())
    val activeSessionUsage: StateFlow<ActiveWifiSessionUsage> = _activeSessionUsage.asStateFlow()

    private fun loadActiveSessionUsage(): ActiveWifiSessionUsage {
        if (!sessionPrefs.getBoolean(K_ACTIVE, false)) return ActiveWifiSessionUsage()
        val now = System.currentTimeMillis()
        val start = sessionPrefs.getLong(K_START, now)
        val totalRx = sessionPrefs.getLong(K_TOTAL_RX, 0L)
        val totalTx = sessionPrefs.getLong(K_TOTAL_TX, 0L)
        val todayRx = if (sessionPrefs.getString(K_DAY_KEY, null) == dayKey(now)) {
            sessionPrefs.getLong(K_DAY_RX, 0L)
        } else if (start >= getTodayStartTime()) totalRx else 0L
        val todayTx = if (sessionPrefs.getString(K_DAY_KEY, null) == dayKey(now)) {
            sessionPrefs.getLong(K_DAY_TX, 0L)
        } else if (start >= getTodayStartTime()) totalTx else 0L
        val monthRx = if (sessionPrefs.getString(K_MONTH_KEY, null) == monthKey(now)) {
            sessionPrefs.getLong(K_MONTH_RX, 0L)
        } else if (start >= getMonthStartTime()) totalRx else 0L
        val monthTx = if (sessionPrefs.getString(K_MONTH_KEY, null) == monthKey(now)) {
            sessionPrefs.getLong(K_MONTH_TX, 0L)
        } else if (start >= getMonthStartTime()) totalTx else 0L
        return ActiveWifiSessionUsage(
            ssid = sessionPrefs.getString(K_SSID, null),
            startTimestamp = start,
            totalRxBytes = totalRx,
            totalTxBytes = totalTx,
            todayRxBytes = todayRx,
            todayTxBytes = todayTx,
            monthRxBytes = monthRx,
            monthTxBytes = monthTx
        )
    }

    private fun syncActiveSessionUsage() {
        _activeSessionUsage.value = loadActiveSessionUsage()
    }

    /**
     * Returns a name chosen by the user for the currently active session when Android
     * cannot reveal the SSID. This label is session-scoped, not a permanent network
     * identity, so it is cleared on a Wi-Fi network-loss event.
     */
    fun getActiveManualNetworkName(): String? {
        if (!sessionPrefs.getBoolean(K_ACTIVE, false) ||
            !sessionPrefs.getBoolean(K_MANUAL_SSID, false)
        ) return null
        return sessionPrefs.getString(K_SSID, null)
    }

    /**
     * Assign a user-selected label to the active unknown Wi-Fi session, preserving the
     * counters already collected for that session. The network name is also saved in
     * Room through the existing network table so it appears as a reusable choice later.
     */
    suspend fun assignActiveSessionNetworkName(name: String): Boolean = recordMutex.withLock {
        withContext(Dispatchers.IO) {
            val cleanName = name.trim()
            if (cleanName.isBlank() ||
                cleanName == TrafficDeltaCalculator.GENERAL_WIFI_FALLBACK_SSID ||
                !sessionPrefs.getBoolean(K_ACTIVE, false)
            ) {
                return@withContext false
            }

            val now = System.currentTimeMillis()
            val existing = networkDao.findBySsid(cleanName)
            if (existing == null) {
                networkDao.insert(
                    NetworkEntity(ssid = cleanName, firstSeenTimestamp = now, lastSeenTimestamp = now)
                )
            } else {
                networkDao.update(existing.copy(lastSeenTimestamp = now))
            }

            sessionPrefs.edit()
                .putString(K_SSID, cleanName)
                .putBoolean(K_MANUAL_SSID, true)
                .apply()
            syncActiveSessionUsage()
            true
        }
    }

    private fun dayKey(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(time))

    private fun monthKey(time: Long): String =
        SimpleDateFormat("yyyy-MM", Locale.ROOT).format(Date(time))

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

    fun getTodayUsage(): Flow<TotalUsageTuple?> =
        combine(snapshotDao.getTotalUsageSince(getTodayStartTime()), activeSessionUsage) { stored, active ->
            TotalUsageTuple(
                totalRx = (stored?.totalRx ?: 0L) + active.todayRxBytes,
                totalTx = (stored?.totalTx ?: 0L) + active.todayTxBytes
            )
        }

    fun getAllTimeUsage(): Flow<TotalUsageTuple?> =
        combine(snapshotDao.getAllTimeUsage(), activeSessionUsage) { stored, active ->
            TotalUsageTuple(
                totalRx = (stored?.totalRx ?: 0L) + active.totalRxBytes,
                totalTx = (stored?.totalTx ?: 0L) + active.totalTxBytes
            )
        }
    fun getTodayNetworkUsage(): Flow<List<NetworkUsageTuple>> = snapshotDao.getUsageByNetworkSince(getTodayStartTime())
    fun getMonthNetworkUsage(): Flow<List<NetworkUsageTuple>> = snapshotDao.getUsageByNetworkSince(getMonthStartTime())
    fun getAllTimeNetworkUsage(): Flow<List<NetworkUsageTuple>> = snapshotDao.getAllTimeUsageByNetwork()
    fun getSnapshotsForNetwork(ssid: String): Flow<List<DataUsageSnapshotEntity>> =
        combine(snapshotDao.getSnapshotsForNetwork(ssid), activeSessionUsage) { stored, active ->
            val live = activeSnapshot(ssid, active, 0L)
            if (live == null) stored else (stored + live).sortedByDescending { it.timestamp }
        }

    fun getSnapshotsForNetworkSince(ssid: String, startTime: Long): Flow<List<DataUsageSnapshotEntity>> =
        combine(snapshotDao.getSnapshotsForNetworkSince(ssid, startTime), activeSessionUsage) { stored, active ->
            val live = activeSnapshot(ssid, active, startTime)
            if (live == null) stored else (stored + live).sortedByDescending { it.timestamp }
        }

    fun getSnapshotsForNetworkBetween(ssid: String, startTime: Long, endTime: Long): Flow<List<DataUsageSnapshotEntity>> =
        combine(snapshotDao.getSnapshotsForNetworkBetween(ssid, startTime, endTime), activeSessionUsage) { stored, active ->
            val live = activeSnapshot(ssid, active, startTime)
                ?.takeIf { it.timestamp in startTime..endTime }
            if (live == null) stored else (stored + live).sortedByDescending { it.timestamp }
        }

    private fun activeSnapshot(
        ssid: String,
        active: ActiveWifiSessionUsage,
        startTime: Long
    ): DataUsageSnapshotEntity? {
        if (!active.isActive || active.ssid != ssid) return null
        val liveRx: Long
        val liveTx: Long
        when {
            startTime <= 0L -> {
                liveRx = active.totalRxBytes
                liveTx = active.totalTxBytes
            }
            startTime == getTodayStartTime() -> {
                liveRx = active.todayRxBytes
                liveTx = active.todayTxBytes
            }
            startTime == getMonthStartTime() -> {
                liveRx = active.monthRxBytes
                liveTx = active.monthTxBytes
            }
            active.startTimestamp >= startTime -> {
                liveRx = active.totalRxBytes
                liveTx = active.totalTxBytes
            }
            else -> return null
        }
        return DataUsageSnapshotEntity(
            id = -1L,
            ssid = ssid,
            timestamp = System.currentTimeMillis(),
            rawRxBytes = 0L,
            rawTxBytes = 0L,
            deltaRxBytes = liveRx,
            deltaTxBytes = liveTx,
            readingStatus = ReadingStatus.VALID.name,
            bootElapsedTimeMs = 0L,
            notes = "جلسة Wi-Fi جارية؛ الاستهلاك مؤقت ويتحدث أثناء الاتصال، ويُحفظ نهائيًا عند انتهاء الجلسة."
        )
    }

    /**
     * Samples counters while connected, but writes exactly one database row when the Wi-Fi
     * session ends. The in-progress session state is persisted in SharedPreferences so that
     * ordinary service restarts do not create a new history row every 45 seconds.
     */
    suspend fun recordSnapshot(
        networkIdentityLost: Boolean = false
    ): Pair<DataUsageSnapshotEntity, ComputedDelta> = recordMutex.withLock {
        withContext(Dispatchers.IO) {
        // If Android reports a Wi-Fi network transition but cannot expose an SSID,
        // expire any manual label from the previous connection before sampling again.
        if (networkIdentityLost) {
            sessionPrefs.edit().putBoolean(K_MANUAL_SSID, false).apply()
        }

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
            val currentSsid = reading.ssid
            val sameNetwork = reading.isWifiConnected && when {
                currentSsid != null -> currentSsid == savedSsid
                sessionPrefs.getBoolean(K_MANUAL_SSID, false) -> true
                else -> TrafficDeltaCalculator.GENERAL_WIFI_FALLBACK_SSID == savedSsid
            }

            if (sameBoot && sameNetwork && savedRx >= 0 && savedTx >= 0 &&
                reading.rawRxBytes >= savedRx && reading.rawTxBytes >= savedTx) {
                deltaRx = reading.rawRxBytes - savedRx
                deltaTx = reading.rawTxBytes - savedTx
                val oldTotalRx = sessionPrefs.getLong(K_TOTAL_RX, 0L)
                val oldTotalTx = sessionPrefs.getLong(K_TOTAL_TX, 0L)
                val todayKey = dayKey(now)
                val monthKey = monthKey(now)
                val priorDayRx = if (sessionPrefs.getString(K_DAY_KEY, null) == todayKey) {
                    sessionPrefs.getLong(K_DAY_RX, 0L)
                } else if (sessionPrefs.getLong(K_START, now) >= getTodayStartTime()) oldTotalRx else 0L
                val priorDayTx = if (sessionPrefs.getString(K_DAY_KEY, null) == todayKey) {
                    sessionPrefs.getLong(K_DAY_TX, 0L)
                } else if (sessionPrefs.getLong(K_START, now) >= getTodayStartTime()) oldTotalTx else 0L
                val priorMonthRx = if (sessionPrefs.getString(K_MONTH_KEY, null) == monthKey) {
                    sessionPrefs.getLong(K_MONTH_RX, 0L)
                } else if (sessionPrefs.getLong(K_START, now) >= getMonthStartTime()) oldTotalRx else 0L
                val priorMonthTx = if (sessionPrefs.getString(K_MONTH_KEY, null) == monthKey) {
                    sessionPrefs.getLong(K_MONTH_TX, 0L)
                } else if (sessionPrefs.getLong(K_START, now) >= getMonthStartTime()) oldTotalTx else 0L
                val editor = sessionPrefs.edit()
                    .putLong(K_TOTAL_RX, oldTotalRx + deltaRx)
                    .putLong(K_TOTAL_TX, oldTotalTx + deltaTx)
                    .putString(K_DAY_KEY, todayKey)
                    .putLong(K_DAY_RX, priorDayRx + deltaRx)
                    .putLong(K_DAY_TX, priorDayTx + deltaTx)
                    .putString(K_MONTH_KEY, monthKey)
                    .putLong(K_MONTH_RX, priorMonthRx + deltaRx)
                    .putLong(K_MONTH_TX, priorMonthTx + deltaTx)
                reading.measurementNote?.let { editor.putString(K_MEASUREMENT_NOTE, it) }
                editor.apply()
                syncActiveSessionUsage()
            }

            if (!reading.isWifiConnected || !sameNetwork || !sameBoot) {
                val ended = finalizeSession(now, reading.rawRxBytes, reading.rawTxBytes)
                if (reading.isWifiConnected) startSession(
                    reading.ssid ?: TrafficDeltaCalculator.GENERAL_WIFI_FALLBACK_SSID,
                    reading.rawRxBytes, reading.rawTxBytes, reading.bootElapsedRealtimeMs, now,
                    reading.measurementNote
                )
                MonitoringPreferences.setLastReadingTimestamp(context, now)
                val returned = ended ?: (snapshotDao.getLatestSnapshot() ?: emptySnapshot(now))
                return@withContext Pair(returned, ComputedDelta(deltaRx, deltaTx, ReadingStatus.VALID, "تم حفظ جلسة Wi-Fi عند انتهائها"))
            }

            val lastEditor = sessionPrefs.edit()
                .putLong(K_LAST_RX, reading.rawRxBytes)
                .putLong(K_LAST_TX, reading.rawTxBytes)
                .putLong(K_BOOT, reading.bootElapsedRealtimeMs)
            reading.measurementNote?.let { lastEditor.putString(K_MEASUREMENT_NOTE, it) }
            lastEditor.apply()
            syncActiveSessionUsage()
        } else if (reading.isWifiConnected) {
            val ssid = reading.ssid ?: TrafficDeltaCalculator.GENERAL_WIFI_FALLBACK_SSID
            startSession(ssid, reading.rawRxBytes, reading.rawTxBytes, reading.bootElapsedRealtimeMs, now, reading.measurementNote)
        }

        MonitoringPreferences.setLastReadingTimestamp(context, now)
        val activeState = _activeSessionUsage.value
        val current = if (activeState.isActive) {
            DataUsageSnapshotEntity(
                id = -1L,
                ssid = activeState.ssid!!,
                timestamp = now,
                rawRxBytes = reading.rawRxBytes.coerceAtLeast(0L),
                rawTxBytes = reading.rawTxBytes.coerceAtLeast(0L),
                deltaRxBytes = activeState.totalRxBytes,
                deltaTxBytes = activeState.totalTxBytes,
                readingStatus = ReadingStatus.VALID.name,
                bootElapsedTimeMs = reading.bootElapsedRealtimeMs,
                notes = "جلسة Wi-Fi جارية؛ الاستهلاك مؤقت ويتحدث أثناء الاتصال."
            )
        } else {
            snapshotDao.getLatestSnapshot() ?: emptySnapshot(now)
        }
        Pair(current, ComputedDelta(deltaRx, deltaTx, if (reading.isWifiConnected) ReadingStatus.VALID else ReadingStatus.DISCONNECTED_FALLBACK,
            if (reading.isWifiConnected) "الجلسة الحالية قيد التسجيل؛ تُحدّث الأرقام مباشرة وتحفظ كسجل عند انتهاء الاتصال" else null))
        }
    }

    private suspend fun startSession(
        ssid: String,
        rx: Long,
        tx: Long,
        boot: Long,
        now: Long,
        measurementNote: String? = null
    ) {
        sessionPrefs.edit()
            .putBoolean(K_ACTIVE, true)
            .putString(K_SSID, ssid)
            .putLong(K_START, now)
            .putLong(K_LAST_RX, rx)
            .putLong(K_LAST_TX, tx)
            .putLong(K_BOOT, boot)
            .putLong(K_TOTAL_RX, 0L)
            .putLong(K_TOTAL_TX, 0L)
            .putBoolean(K_MANUAL_SSID, false)
            .putString(K_DAY_KEY, dayKey(now))
            .putLong(K_DAY_RX, 0L)
            .putLong(K_DAY_TX, 0L)
            .putString(K_MONTH_KEY, monthKey(now))
            .putLong(K_MONTH_RX, 0L)
            .putLong(K_MONTH_TX, 0L)
            .putString(K_MEASUREMENT_NOTE, measurementNote)
            .apply()
        val existing = networkDao.findBySsid(ssid)
        if (existing == null) {
            networkDao.insert(NetworkEntity(ssid = ssid, firstSeenTimestamp = now, lastSeenTimestamp = now))
        } else {
            networkDao.update(existing.copy(lastSeenTimestamp = now))
        }
        syncActiveSessionUsage()
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
            notes = listOfNotNull(
                "جلسة Wi-Fi من $startText إلى $endText",
                sessionPrefs.getString(K_MEASUREMENT_NOTE, null)
            ).joinToString(separator = "\n")
        )
        val id = snapshotDao.insert(entity)
        sessionPrefs.edit().clear().apply()
        _activeSessionUsage.value = ActiveWifiSessionUsage()
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
            snapshotDao.getUsageByNetworkSince(monthStart),
            activeSessionUsage
        ) { networks, allTimeUsages, todayUsages, monthUsages, active ->
            val allMap = allTimeUsages.associateBy { it.ssid }
            val todayMap = todayUsages.associateBy { it.ssid }
            val monthMap = monthUsages.associateBy { it.ssid }
            networks.map { network ->
                val allTuple = allMap[network.ssid]
                val todayTuple = todayMap[network.ssid]
                val monthTuple = monthMap[network.ssid]
                val live = active.takeIf { it.isActive && it.ssid == network.ssid }
                NetworkSummary(
                    internalId = network.internalId, ssid = network.ssid,
                    firstSeenTimestamp = network.firstSeenTimestamp, lastSeenTimestamp = network.lastSeenTimestamp,
                    totalRxBytes = (allTuple?.rxBytes ?: 0L) + (live?.totalRxBytes ?: 0L),
                    totalTxBytes = (allTuple?.txBytes ?: 0L) + (live?.totalTxBytes ?: 0L),
                    todayRxBytes = (todayTuple?.rxBytes ?: 0L) + (live?.todayRxBytes ?: 0L),
                    todayTxBytes = (todayTuple?.txBytes ?: 0L) + (live?.todayTxBytes ?: 0L),
                    monthRxBytes = (monthTuple?.rxBytes ?: 0L) + (live?.monthRxBytes ?: 0L),
                    monthTxBytes = (monthTuple?.txBytes ?: 0L) + (live?.monthTxBytes ?: 0L),
                    snapshotsCount = (allTuple?.snapshotCount ?: 0) + if (live != null) 1 else 0
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
        private const val K_DAY_KEY = "day_key"
        private const val K_DAY_RX = "day_rx"
        private const val K_DAY_TX = "day_tx"
        private const val K_MONTH_KEY = "month_key"
        private const val K_MONTH_RX = "month_rx"
        private const val K_MONTH_TX = "month_tx"
        private const val K_MEASUREMENT_NOTE = "measurement_note"
        private const val K_MANUAL_SSID = "manual_ssid"
    }
}

package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.DataUsageSnapshotEntity
import com.example.data.model.ComputedDelta
import com.example.data.model.FilterPeriod
import com.example.data.model.NetworkSummary
import com.example.data.model.ReadingStatus
import com.example.data.model.ServiceMonitoringState
import com.example.data.model.TechnicalConstraintInfo
import com.example.data.network.WifiNetworkDetector
import com.example.data.preferences.MonitoringPreferences
import com.example.data.repository.WifiTrackerRepository
import com.example.service.WifiMonitoringService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class Screen {
    data object Home : Screen()
    data object Networks : Screen()
    data class NetworkDetail(val ssid: String) : Screen()
    data object History : Screen()
    data object Settings : Screen()
}

data class MainUiState(
    val currentScreen: Screen = Screen.Home,
    val isWifiConnected: Boolean = false,
    val currentWifiSsid: String? = null,
    val isSsidConfirmed: Boolean = false,
    val connectionNotes: String? = null,
    val isMonitoringActive: Boolean = true,
    val isServiceRunning: Boolean = false,
    val serviceMonitoringState: ServiceMonitoringState = ServiceMonitoringState.STOPPED,
    val hasLocationPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val lastReadingTime: Long? = null,
    val todayRxBytes: Long = 0L,
    val todayTxBytes: Long = 0L,
    val allTimeRxBytes: Long = 0L,
    val allTimeTxBytes: Long = 0L,
    val networkSummaries: List<NetworkSummary> = emptyList(),
    val recentSnapshots: List<DataUsageSnapshotEntity> = emptyList(),
    val selectedNetworkSsid: String? = null,
    val selectedPeriod: FilterPeriod = FilterPeriod.TODAY,
    val selectedNetworkSnapshots: List<DataUsageSnapshotEntity> = emptyList(),
    val selectedPeriodRxBytes: Long = 0L,
    val selectedPeriodTxBytes: Long = 0L,
    val lastRecordedDelta: ComputedDelta? = null,
    val isRecording: Boolean = false,
    val feedbackMessage: String? = null,
    val technicalConstraints: List<TechnicalConstraintInfo> = emptyList()
) {
    val todayTotalBytes: Long get() = todayRxBytes + todayTxBytes
    val allTimeTotalBytes: Long get() = allTimeRxBytes + allTimeTxBytes
    val selectedPeriodTotalBytes: Long get() = selectedPeriodRxBytes + selectedPeriodTxBytes
}

class MainViewModel(
    private val repository: WifiTrackerRepository,
    private val networkDetector: WifiNetworkDetector,
    private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private var selectedNetworkJob: Job? = null

    init {
        loadTechnicalConstraints()
        checkWifiStatus()
        observeData()
        observeServiceState()

        // استعادة حالة الخدمة وفق تفضيلات المستخدم المحفوظة
        val shouldMonitor = MonitoringPreferences.isMonitoringEnabled(appContext)
        _uiState.update {
            it.copy(
                isMonitoringActive = shouldMonitor,
                lastReadingTime = MonitoringPreferences.getLastReadingTimestamp(appContext)
            )
        }

        if (shouldMonitor && !WifiMonitoringService.startService(appContext)) {
            MonitoringPreferences.setMonitoringEnabled(appContext, false)
            _uiState.update {
                it.copy(
                    isMonitoringActive = false,
                    feedbackMessage = "تعذر تشغيل خدمة المراقبة. افتح التطبيق وتحقق من قيود النظام ثم حاول مجددًا."
                )
            }
        }

        // تسجيل قراءة مرجعية أولية تلقائياً عند بدء التشغيل
        recordCurrentSnapshot(isAutomatic = true)
    }

    private fun loadTechnicalConstraints() {
        val constraints = listOf(
            TechnicalConstraintInfo(
                title = "دقة قياس استهلاك Wi-Fi وحدود واجهات Android",
                description = "يستخدم التطبيق عدادات واجهة Wi-Fi النشطة على Android 12 فأحدث عند توفرها. هذه عدادات على مستوى الواجهة وقد لا تشمل بعض حركة VPN أو تعديلات النظام، كما أنها لا تفصل كل شبكة SSID تاريخيًا بشكل مضمون. في الإصدارات الأقدم يستخدم التطبيق تقديرًا من إجمالي الحركة بعد طرح عدادات الجوال.",
                recommendation = "تظهر ملاحظة القياس مع السجلات؛ القراءة الأولى نقطة أساس ولا تحتسب تراكمًا سابقًا، وعند انقطاع Wi-Fi أو التبديل تُفتح جلسة قياس جديدة لتقليل نسبة حركة شبكة إلى أخرى."
            ),
            TechnicalConstraintInfo(
                title = "إعادة ضبط العدادات بعد إعادة تشغيل الهاتف",
                description = "يقوم نظام أندرويد بتصفير عدادات TrafficStats تلقائياً عند كل إعادة تشغيل للجهاز. يتتبع التطبيق زمن إقلاع النظام (SystemClock.elapsedRealtime) لرصد إعادة التشغيل فوراً.",
                recommendation = "عند رصد إعادة التشغيل، ينشئ التطبيق نقطة مرجعية جديدة بصفر بايت بدلاً من احتساب العداد التراكمي كاستهلاك جديد."
            ),
            TechnicalConstraintInfo(
                title = "اشتراط صلاحية الموقع لقراءة اسم الشبكة (SSID)",
                description = "بدءاً من Android 8.1 وAndroid 10 فما فوق، يعتبر نظام أندرويد اسم شبكة Wi-Fi معلومة حساسة تتطلب منح صلاحية الموقع الدقيق (ACCESS_FINE_LOCATION).",
                recommendation = "بدون هذه الصلاحية، يُظهر النظام الاسم كـ <unknown ssid>، وتُسجل القراءات بوضوح كـ 'Wi-Fi عام' دون تخمين مضلل."
            ),
            TechnicalConstraintInfo(
                title = "تسجيل فجوة بيانات عند الانقطاع والعودة",
                description = "إذا انقطع اتصال Wi-Fi ثم عاد لاحقاً، فإن فرق العدادات قد يحتوي بيانات نُقلت عبر شبكات أخرى. يقوم التطبيق برصد هذه الفجوة وتسجيل أساس جديد دون اختراع استهلاك.",
                recommendation = "تُسجل الحالة كـ 'فجوة في البيانات' للحفاظ على أمان السجلات ونزاهة الحسابات."
            ),
            TechnicalConstraintInfo(
                title = "تجنب احتساب التراكم التاريخي كاستهلاك جديد",
                description = "عند فتح التطبيق لأول مرة أو بعد انقطاع طويل، قد يحتوي عداد النظام على عدة جيجابايتات. لا يحتسب التطبيق هذا العداد كاستهلاك جديد، بل يعتبره نقطة أساس صفرية (Baseline).",
                recommendation = "يتم احتساب الزيادة فقط بين القراءات المتتالية الفعلية أثناء الاتصال."
            )
        )
        _uiState.update { it.copy(technicalConstraints = constraints) }
    }

    fun checkWifiStatus() {
        val connectionState = networkDetector.getWifiConnectionState()
        val hasLoc = networkDetector.hasLocationPermission()
        val hasNotif = networkDetector.hasNotificationPermission()

        _uiState.update {
            it.copy(
                isWifiConnected = connectionState.isConnected,
                currentWifiSsid = connectionState.ssid,
                isSsidConfirmed = connectionState.isSsidConfirmed,
                connectionNotes = connectionState.notes,
                hasLocationPermission = hasLoc,
                hasNotificationPermission = hasNotif
            )
        }
    }

    private fun observeServiceState() {
        viewModelScope.launch {
            WifiMonitoringService.isRunningFlow.collect { isRunning ->
                _uiState.update { it.copy(isServiceRunning = isRunning) }
            }
        }

        viewModelScope.launch {
            WifiMonitoringService.serviceStateFlow.collect { state ->
                _uiState.update { it.copy(serviceMonitoringState = state) }
            }
        }

        viewModelScope.launch {
            WifiMonitoringService.lastReadingTimeFlow.collect { timestamp ->
                if (timestamp != null && timestamp > 0L) {
                    _uiState.update { it.copy(lastReadingTime = timestamp) }
                }
            }
        }
    }

    private fun observeData() {
        // مراقبة استهلاك اليوم
        viewModelScope.launch {
            repository.getTodayUsage().collect { tuple ->
                _uiState.update {
                    it.copy(
                        todayRxBytes = tuple?.totalRx ?: 0L,
                        todayTxBytes = tuple?.totalTx ?: 0L
                    )
                }
            }
        }

        // مراقبة الاستهلاك الإجمالي
        viewModelScope.launch {
            repository.getAllTimeUsage().collect { tuple ->
                _uiState.update {
                    it.copy(
                        allTimeRxBytes = tuple?.totalRx ?: 0L,
                        allTimeTxBytes = tuple?.totalTx ?: 0L
                    )
                }
            }
        }

        // مراقبة ملخصات الشبكات
        viewModelScope.launch {
            repository.getNetworkSummariesFlow().collect { summaries ->
                _uiState.update {
                    it.copy(networkSummaries = summaries)
                }
            }
        }

        // مراقبة السجلات الأخيرة
        viewModelScope.launch {
            repository.recentSnapshots.collect { snapshots ->
                _uiState.update {
                    it.copy(recentSnapshots = snapshots)
                }
            }
        }
    }

    fun recordCurrentSnapshot(isAutomatic: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRecording = true) }
            try {
                checkWifiStatus()
                val (snapshot, delta) = repository.recordSnapshot()
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        lastRecordedDelta = delta,
                        lastReadingTime = snapshot.timestamp,
                        feedbackMessage = if (!isAutomatic) {
                            "تم تسجيل القراءة بنجاح (${delta.status.arabicLabel})"
                        } else null
                    )
                }
                // تحديث تفاصيل الشبكة المختارة إن وُجدت
                val currentSelectedSsid = _uiState.value.selectedNetworkSsid
                if (currentSelectedSsid != null) {
                    loadSelectedNetworkDetails(currentSelectedSsid, _uiState.value.selectedPeriod)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        feedbackMessage = "تعذر تسجيل القراءة: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun toggleMonitoring() {
        val willEnable = !_uiState.value.isMonitoringActive
        if (willEnable) {
            val startRequested = WifiMonitoringService.startService(appContext)
            if (startRequested) {
                MonitoringPreferences.setMonitoringEnabled(appContext, true)
                _uiState.update {
                    it.copy(
                        isMonitoringActive = true,
                        feedbackMessage = "تم طلب تشغيل خدمة المراقبة في الخلفية"
                    )
                }
            } else {
                MonitoringPreferences.setMonitoringEnabled(appContext, false)
                _uiState.update {
                    it.copy(
                        isMonitoringActive = false,
                        isServiceRunning = false,
                        serviceMonitoringState = ServiceMonitoringState.STOPPED,
                        feedbackMessage = "تعذر بدء خدمة المراقبة. حاول تشغيلها والتطبيق ظاهر على الشاشة."
                    )
                }
            }
        } else {
            MonitoringPreferences.setMonitoringEnabled(appContext, false)
            WifiMonitoringService.stopService(appContext)
            _uiState.update {
                it.copy(
                    isMonitoringActive = false,
                    isServiceRunning = false,
                    serviceMonitoringState = ServiceMonitoringState.STOPPED,
                    feedbackMessage = "تم إيقاف المراقبة وإلغاء الخدمة الخلفية"
                )
            }
        }
    }

    fun navigateTo(screen: Screen) {
        _uiState.update { it.copy(currentScreen = screen) }
        if (screen is Screen.NetworkDetail) {
            selectNetwork(screen.ssid)
        }
    }

    fun selectNetwork(ssid: String) {
        _uiState.update { it.copy(selectedNetworkSsid = ssid) }
        loadSelectedNetworkDetails(ssid, _uiState.value.selectedPeriod)
    }

    fun setFilterPeriod(period: FilterPeriod) {
        _uiState.update { it.copy(selectedPeriod = period) }
        val ssid = _uiState.value.selectedNetworkSsid
        if (ssid != null) {
            loadSelectedNetworkDetails(ssid, period)
        }
    }

    private fun loadSelectedNetworkDetails(ssid: String, period: FilterPeriod) {
        selectedNetworkJob?.cancel()
        selectedNetworkJob = viewModelScope.launch {
            val startTime = when (period) {
                FilterPeriod.TODAY -> repository.getTodayStartTime()
                FilterPeriod.WEEK -> repository.getWeekStartTime()
                FilterPeriod.MONTH -> repository.getMonthStartTime()
                FilterPeriod.ALL_TIME -> 0L
            }

            val flow = if (startTime == 0L) {
                repository.getSnapshotsForNetwork(ssid)
            } else {
                repository.getSnapshotsForNetworkSince(ssid, startTime)
            }

            flow.collectLatest { snapshots ->
                var rx = 0L
                var tx = 0L
                snapshots.forEach {
                    rx += it.deltaRxBytes
                    tx += it.deltaTxBytes
                }
                _uiState.update {
                    it.copy(
                        selectedNetworkSnapshots = snapshots,
                        selectedPeriodRxBytes = rx,
                        selectedPeriodTxBytes = tx
                    )
                }
            }
        }
    }

    fun clearFeedbackMessage() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    class Factory(
        private val repository: WifiTrackerRepository,
        private val networkDetector: WifiNetworkDetector,
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(repository, networkDetector, context.applicationContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

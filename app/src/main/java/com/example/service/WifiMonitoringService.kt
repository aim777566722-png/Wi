package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.WifiTrackerApplication
import com.example.data.model.ServiceMonitoringState
import com.example.data.preferences.MonitoringPreferences
import com.example.ui.components.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WifiMonitoringService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MONITORING) {
            stopMonitoringService()
            return START_NOT_STICKY
        }

        // تسجيل حالة بدء الخدمة
        _isRunningFlow.value = true
        _serviceStateFlow.value = ServiceMonitoringState.ACTIVE
        MonitoringPreferences.setMonitoringEnabled(this, true)

        // إطلاق الخدمة كـ Foreground Service فورا لتلبية متطلبات أندرويد
        startForegroundWithNotification("بدء تشغيل مراقب استهلاك Wi-Fi...")

        // تسجيل مستمع تغيرات الشبكة
        registerNetworkCallback()

        // بدء دورة القياس الدورية (كل 45 ثانية)
        startPeriodicMonitoringLoop()

        return START_STICKY
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                serviceScope.launch {
                    _serviceStateFlow.value = ServiceMonitoringState.ACTIVE
                    performReadingAndRefreshNotification("تم الاتصال بشبكة Wi-Fi")
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                serviceScope.launch {
                    performReadingAndRefreshNotification("تحديث خصائص اتصال Wi-Fi")
                }
            }

            override fun onLost(network: Network) {
                serviceScope.launch {
                    _serviceStateFlow.value = ServiceMonitoringState.WAITING_WIFI
                    val app = application as? WifiTrackerApplication
                    app?.repository?.recordSnapshot()
                    updateNotification("المراقبة نشطة: بانتظار اتصال Wi-Fi")
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            // التعامل بأمان في حال وجود قيود
        }
    }

    private fun startPeriodicMonitoringLoop() {
        serviceScope.launch {
            while (isActive) {
                performReadingAndRefreshNotification(null)
                delay(45_000L) // فحص كل 45 ثانية
            }
        }
    }

    private suspend fun performReadingAndRefreshNotification(reason: String?) {
        val app = application as? WifiTrackerApplication ?: return
        try {
            val (snapshot, delta) = app.repository.recordSnapshot()
            val (isWifi, ssid) = app.networkDetector.getCurrentWifiInfo()

            _lastReadingTimeFlow.value = snapshot.timestamp
            MonitoringPreferences.setLastReadingTimestamp(this, snapshot.timestamp)

            val statusText = if (isWifi) {
                _serviceStateFlow.value = ServiceMonitoringState.ACTIVE
                val networkName = ssid ?: "Wi-Fi عام"
                val todayUsage = app.repository.getTodayUsage().firstOrNull()
                val todayTotal = (todayUsage?.totalRx ?: 0L) + (todayUsage?.totalTx ?: 0L)
                "متصل بـ: $networkName | استهلاك اليوم: ${Formatters.formatBytes(todayTotal)}"
            } else {
                _serviceStateFlow.value = ServiceMonitoringState.WAITING_WIFI
                "المراقبة نشطة: بانتظار اتصال Wi-Fi"
            }

            updateNotification(statusText)
        } catch (e: Exception) {
            updateNotification("المراقبة نشطة (تعذر تسجيل قراءة مؤقت)")
        }
    }

    private fun startForegroundWithNotification(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, WifiMonitoringService::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("مراقب استهلاك Wi-Fi")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "إيقاف المراقبة",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "خدمة مراقبة استهلاك Wi-Fi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار مستمر يوضح حالة مراقبة استهلاك بيانات Wi-Fi واستهلاك اليوم"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopMonitoringService() {
        _isRunningFlow.value = false
        _serviceStateFlow.value = ServiceMonitoringState.STOPPED
        MonitoringPreferences.setMonitoringEnabled(this, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _isRunningFlow.value = false
        _serviceStateFlow.value = ServiceMonitoringState.STOPPED

        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // Ignore if already unregistered
            }
            networkCallback = null
        }

        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_MONITORING = "com.example.action.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.example.action.STOP_MONITORING"
        private const val CHANNEL_ID = "wifi_tracker_monitoring_channel"
        private const val NOTIFICATION_ID = 1001

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        private val _serviceStateFlow = MutableStateFlow(ServiceMonitoringState.STOPPED)
        val serviceStateFlow: StateFlow<ServiceMonitoringState> = _serviceStateFlow.asStateFlow()

        private val _lastReadingTimeFlow = MutableStateFlow<Long?>(null)
        val lastReadingTimeFlow: StateFlow<Long?> = _lastReadingTimeFlow.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, WifiMonitoringService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // في حال وجود قيود انطلاق الخدمات في الخلفية في أندرويد 12+
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, WifiMonitoringService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}

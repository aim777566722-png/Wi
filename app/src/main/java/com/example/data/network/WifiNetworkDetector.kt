package com.example.data.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.data.model.RawTrafficReading
import com.example.data.model.WifiConnectionState

class WifiNetworkDetector(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /**
     * التحقق مما إذا كانت صلاحية الموقع ممنوحة (مطلوبة لقراءة SSID في أندرويد 8.1+)
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * صلاحية محاولة قراءة اسم شبكة Wi-Fi:
     * Android 13+ يستخدم صلاحية الأجهزة القريبة، والإصدارات الأقدم تستخدم صلاحية الموقع.
     */
    fun hasSsidPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * التحقق من صلاحية الإشعارات (Android 13+)
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * التحقق مما إذا كانت خدمات الموقع مفعلة بالجهاز (GPS / Network location)
     */
    fun isLocationServiceEnabled(): Boolean {
        val lm = locationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    /**
     * فحص حالة اتصال Wi-Fi الحالي واستخراج SSID دون تخمين أو تضليل
     */
    fun getWifiConnectionState(): WifiConnectionState {
        val cm = connectivityManager ?: return WifiConnectionState(
            isConnected = false,
            ssid = null,
            isSsidConfirmed = false,
            notes = "تعذر الوصول إلى خدمة ConnectivityManager"
        )

        val activeNetwork = cm.activeNetwork ?: return WifiConnectionState(
            isConnected = false,
            ssid = null,
            isSsidConfirmed = false,
            notes = "لا يوجد اتصال شبكة نشط"
        )

        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return WifiConnectionState(
            isConnected = false,
            ssid = null,
            isSsidConfirmed = false,
            notes = "لا توجد خصائص شبكة متاحة"
        )

        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        if (!isWifi) {
            return WifiConnectionState(
                isConnected = false,
                ssid = null,
                isSsidConfirmed = false,
                notes = "الاتصال النشط ليس عبر Wi-Fi"
            )
        }

        // الهاتف متصل بـ Wi-Fi؛ نتحقق من الصلاحية المناسبة لإصدار Android.
        if (!hasSsidPermission()) {
            return WifiConnectionState(
                isConnected = true,
                ssid = null,
                isSsidConfirmed = false,
                notes = "متصل بـ Wi-Fi ولكن لم تُمنح صلاحية قراءة اسم الشبكة"
            )
        }

        var rawSsid: String? = null

        // استخراج SSID عبر WifiInfo من NetworkCapabilities (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val transportInfo = capabilities.transportInfo
            if (transportInfo is WifiInfo) {
                rawSsid = transportInfo.ssid
            }
        }

        // الطريقة المساندة عبر WifiManager
        if (rawSsid.isNullOrEmpty() || rawSsid == "<unknown ssid>") {
            val wifiInfo = wifiManager?.connectionInfo
            if (wifiInfo != null) {
                rawSsid = wifiInfo.ssid
            }
        }

        // تنظيف الاسم من علامات التنصيص المزدوجة
        val cleanSsid = rawSsid?.trim('"')

        return if (cleanSsid.isNullOrBlank() || cleanSsid == "<unknown ssid>") {
            WifiConnectionState(
                isConnected = true,
                ssid = null,
                isSsidConfirmed = false,
                notes = if (!isLocationServiceEnabled()) {
                    "متصل بـ Wi-Fi ولكن Android لم يُتح اسم الشبكة؛ تحقق من إعدادات Wi-Fi والصلاحيات"
                } else {
                    "متصل بـ Wi-Fi ولكن اسم الشبكة مخفي من قبل نقطة الوصول"
                }
            )
        } else {
            WifiConnectionState(
                isConnected = true,
                ssid = cleanSsid,
                isSsidConfirmed = true,
                notes = null
            )
        }
    }

    /**
     * فحص سريع لحالة Wi-Fi واسم الشبكة (للتوافق مع الواجهات السابقة)
     */
    fun getCurrentWifiInfo(): Pair<Boolean, String?> {
        val state = getWifiConnectionState()
        return Pair(state.isConnected, state.ssid)
    }

    private data class TrafficCounters(
        val rxBytes: Long,
        val txBytes: Long,
        val measurementNote: String? = null
    )

    /**
     * Prefer active Wi-Fi interface counters on Android 12+.
     * Older versions use a labelled estimate, never a guaranteed Wi-Fi-only value.
     */
    private fun readWifiCounters(isWifiConnected: Boolean): TrafficCounters {
        if (isWifiConnected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cm = connectivityManager
            val network = cm?.activeNetwork
            val isActiveWifi = network != null &&
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val interfaceName = if (isActiveWifi && network != null) {
                cm?.getLinkProperties(network)?.interfaceName
            } else null

            if (!interfaceName.isNullOrBlank()) {
                val rx = TrafficStats.getRxBytes(interfaceName)
                val tx = TrafficStats.getTxBytes(interfaceName)
                if (rx >= 0L && tx >= 0L) {
                    return TrafficCounters(
                        rxBytes = rx,
                        txBytes = tx,
                        measurementNote = "القياس من عدادات واجهة Wi-Fi ($interfaceName). قد لا تشمل العدادات حركة VPN وبعض تعديلات النظام."
                    )
                }
            }
        }

        val totalRx = TrafficStats.getTotalRxBytes()
        val totalTx = TrafficStats.getTotalTxBytes()
        val mobileRx = TrafficStats.getMobileRxBytes()
        val mobileTx = TrafficStats.getMobileTxBytes()
        val estimatedRx = if (totalRx >= 0L && mobileRx >= 0L) maxOf(0L, totalRx - mobileRx) else maxOf(0L, totalRx)
        val estimatedTx = if (totalTx >= 0L && mobileTx >= 0L) maxOf(0L, totalTx - mobileTx) else maxOf(0L, totalTx)
        val note = if (isWifiConnected) {
            "قياس تقديري: لا تتوفر عدادات واجهة Wi-Fi المباشرة؛ طرح عدادات الجوال من الإجمالي لا يضمن فصل Wi-Fi بدقة."
        } else null
        return TrafficCounters(estimatedRx, estimatedTx, note)
    }

    fun readCurrentRawTraffic(): RawTrafficReading {
        val connectionState = getWifiConnectionState()
        val counters = readWifiCounters(connectionState.isConnected)
        return RawTrafficReading(
            timestamp = System.currentTimeMillis(),
            bootElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            rawRxBytes = counters.rxBytes,
            rawTxBytes = counters.txBytes,
            isWifiConnected = connectionState.isConnected,
            ssid = connectionState.ssid,
            bssid = null,
            isSsidConfirmed = connectionState.isSsidConfirmed,
            measurementNote = counters.measurementNote
        )
    }
}

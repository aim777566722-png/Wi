package com.example.data.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
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

    // Android 12+ redacts WifiInfo from NetworkCapabilities callbacks unless the
    // callback opts in. Keep the confirmed SSID paired with that exact Network so
    // background sampling can use it without misattributing another Wi-Fi network.
    @Volatile private var callbackWifiNetwork: Network? = null
    @Volatile private var callbackWifiSsid: String? = null

    private fun normalizeSsid(rawSsid: String?): String? {
        val value = rawSsid?.trim('"')?.takeIf { it.isNotBlank() }
        return value?.takeUnless {
            it == "<unknown ssid>" || it == WifiManager.UNKNOWN_SSID
        }
    }

    fun updateWifiNetworkFromCallback(network: Network, capabilities: NetworkCapabilities) {
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val ssid = normalizeSsid((capabilities.transportInfo as? WifiInfo)?.ssid) ?: return
        synchronized(this) {
            callbackWifiNetwork = network
            callbackWifiSsid = ssid
        }
    }

    fun clearWifiNetworkFromCallback(network: Network) {
        synchronized(this) {
            if (callbackWifiNetwork == network) {
                callbackWifiNetwork = null
                callbackWifiSsid = null
            }
        }
    }

    private fun callbackSsidFor(network: Network): String? = synchronized(this) {
        if (callbackWifiNetwork == network) callbackWifiSsid else null
    }

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
     * Reading SSID is location-sensitive on Android. NEARBY_WIFI_DEVICES alone
     * does not guarantee that Android reveals the SSID from WifiInfo.
     */
    fun hasSsidPermission(): Boolean = hasLocationPermission()

    private data class ConnectedWifiNetwork(
        val network: android.net.Network,
        val capabilities: NetworkCapabilities
    )

    /**
     * Do not infer Wi-Fi connectivity from activeNetwork alone: a VPN or another
     * default route can hide the underlying Wi-Fi network from that property.
     * Prefer the default network when it is Wi-Fi, then locate another connected
     * Wi-Fi transport for metering and SSID attribution.
     */
    private fun findConnectedWifiNetwork(): ConnectedWifiNetwork? {
        val cm = connectivityManager ?: return null
        val active = cm.activeNetwork
        if (active != null) {
            val activeCaps = runCatching { cm.getNetworkCapabilities(active) }.getOrNull()
            if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                return ConnectedWifiNetwork(active, activeCaps)
            }
        }

        return runCatching {
            cm.allNetworks.firstNotNullOfOrNull { network ->
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    ConnectedWifiNetwork(network, caps)
                } else null
            }
        }.getOrNull()
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
        val connectedWifi = findConnectedWifiNetwork() ?: return WifiConnectionState(
            isConnected = false,
            ssid = null,
            isSsidConfirmed = false,
            notes = "لم يعثر Android على اتصال Wi-Fi متاح؛ قد يكون الاتصال الحالي عبر شبكة أخرى أو أن حالة الشبكة لم تتحدث بعد"
        )

        // Wi-Fi connectivity is confirmed independently of whether Android reveals the SSID.
        if (!hasSsidPermission()) {
            return WifiConnectionState(
                isConnected = true,
                ssid = null,
                isSsidConfirmed = false,
                notes = "متصل بـ Wi-Fi، لكن يلزم منح صلاحية الموقع الدقيق وتشغيل خدمات الموقع لقراءة اسم الشبكة (SSID)"
            )
        }

        var rawSsid: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val transportInfo = connectedWifi.capabilities.transportInfo
            if (transportInfo is WifiInfo) rawSsid = transportInfo.ssid
        }

        // Use WifiManager as a compatibility fallback after checking location permission.
        if (normalizeSsid(rawSsid) == null) {
            rawSsid = runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull()
        }

        // A callback registered with FLAG_INCLUDE_LOCATION_INFO can expose the SSID
        // even when direct NetworkCapabilities reads are redacted in the background.
        if (normalizeSsid(rawSsid) == null) {
            rawSsid = callbackSsidFor(connectedWifi.network)
        }

        val cleanSsid = normalizeSsid(rawSsid)
        return if (cleanSsid == null) {
            WifiConnectionState(
                isConnected = true,
                ssid = null,
                isSsidConfirmed = false,
                notes = if (!isLocationServiceEnabled()) {
                    "متصل بـ Wi-Fi؛ فعّل خدمات الموقع في إعدادات الهاتف لكي يكشف Android اسم الشبكة"
                } else {
                    "متصل بـ Wi-Fi، لكن Android لم يكشف اسم الشبكة؛ سيستمر تسجيل الاستهلاك تحت اسم Wi-Fi عام"
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
            val wifiNetwork = findConnectedWifiNetwork()?.network
            val interfaceName = if (wifiNetwork != null) {
                cm?.getLinkProperties(wifiNetwork)?.interfaceName
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

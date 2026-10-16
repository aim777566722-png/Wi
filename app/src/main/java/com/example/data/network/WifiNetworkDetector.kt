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
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.model.RawTrafficReading
import com.example.data.model.WifiConnectionState
import java.security.MessageDigest

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

    private fun ssidReadStatus(rawSsid: String?): String = when {
        normalizeSsid(rawSsid) != null -> "found"
        rawSsid == null -> "null_or_access_error"
        else -> "unknown_ssid"
    }

    private companion object {
        const val TAG = "WifiNetworkDetector"
    }

    fun updateWifiNetworkFromCallback(network: Network, capabilities: NetworkCapabilities) {
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return

        // NetworkCapabilities may redact transportInfo in callbacks. On Android 13+
        // the app uses NEARBY_WIFI_DEVICES rather than location permission, so also
        // ask WifiManager for the currently associated network name.
        val capabilitySsid = (capabilities.transportInfo as? WifiInfo)?.ssid
        @Suppress("DEPRECATION")
        val managerSsid = if (normalizeSsid(capabilitySsid) == null && hasSsidPermission()) {
            runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull()
        } else null
        val ssid = normalizeSsid(capabilitySsid) ?: normalizeSsid(managerSsid) ?: return

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
     * Android 13+ provides NEARBY_WIFI_DEVICES for Wi-Fi access without deriving
     * physical location. Older Android versions require location permission.
     */
    fun hasSsidPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            hasLocationPermission()
        }
    }

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
     * Creates a repeatable local-network label when Android hides the real SSID.
     * This is only a fingerprint for grouping sessions, not the Wi-Fi network's name.
     * It uses local route/address/DNS metadata, not GPS or SSID/BSSID.
     */
    private fun localNetworkLabel(network: Network): String? {
        val cm = connectivityManager ?: return null
        val linkProperties = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: return null

        val gateways = linkProperties.routes
            .filter { it.isDefaultRoute }
            .mapNotNull { route ->
                route.gateway?.hostAddress?.substringBefore("%")?.takeIf {
                    it.isNotBlank() && it != "0.0.0.0" && it != "::"
                }
            }
            .distinct()
            .sorted()
        val dnsServers = linkProperties.dnsServers
            .mapNotNull { it.hostAddress?.substringBefore("%") }
            .distinct()
            .sorted()

        // Do not include this phone's DHCP address: it can change while the
        // phone remains on the same Wi-Fi network and would create a new label.
        // Only use network-level routing/DNS data; if unavailable, avoid guessing.
        if (gateways.isEmpty() && dnsServers.isEmpty()) return null

        val fingerprintInput = listOf(
            "gateways=${gateways.joinToString(",")}",
            "dns=${dnsServers.joinToString(",")}"
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(fingerprintInput.toByteArray(Charsets.UTF_8))
        val shortId = digest.take(5).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        return "شبكة Wi-Fi تلقائية #$shortId"
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

        // Preserve a repeatable local identifier even when Android redacts the real SSID.
        val automaticNetworkLabel = localNetworkLabel(connectedWifi.network)

        // Wi-Fi connectivity is confirmed independently of whether Android reveals the SSID.
        if (!hasSsidPermission()) {
            val permissionNote = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "إذن الأجهزة القريبة / Wi-Fi غير ممنوح. افتح إعدادات التطبيق ثم الأذونات واسمح بـ «الأجهزة القريبة»؛ لا تشغّل GPS."
            } else {
                "هذا الإصدار من Android يحتاج إذن الموقع لقراءة اسم Wi-Fi. قد لا يكشف النظام الاسم إذا كانت خدمات الموقع متوقفة."
            }
            Log.w(
                TAG,
                "SSID lookup skipped: api=${Build.VERSION.SDK_INT}, nearbyPermission=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && hasSsidPermission()}, locationPermission=${hasLocationPermission()}, locationServices=${isLocationServiceEnabled()}"
            )
            val note = if (automaticNetworkLabel != null) {
                "$permissionNote اسم الشبكة الحقيقي غير متاح؛ سيجمع التطبيق القراءات تحت معرّف محلي $automaticNetworkLabel، وهذا المعرّف ليس اسم SSID وقد يتكرر إذا تشابهت إعدادات شبكتين."
            } else permissionNote
            return WifiConnectionState(
                isConnected = true,
                ssid = automaticNetworkLabel,
                isSsidConfirmed = false,
                notes = note
            )
        }

        var rawSsid: String? = null
        var managerSsid: String? = null
        var capabilitiesSsid: String? = null
        var cachedSsid: String? = null

        // Prefer WifiManager on Android 13+; never infer an SSID from unrelated network data.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            managerSsid = runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull()
            rawSsid = managerSsid
        }

        if (normalizeSsid(rawSsid) == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val transportInfo = connectedWifi.capabilities.transportInfo
            capabilitiesSsid = (transportInfo as? WifiInfo)?.ssid
            rawSsid = capabilitiesSsid
        }

        // Compatibility fallback for earlier Android releases and device-specific WifiManager behavior.
        if (normalizeSsid(rawSsid) == null) {
            @Suppress("DEPRECATION")
            managerSsid = runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull()
            rawSsid = managerSsid
        }

        // Use the last confirmed SSID for this exact Network if direct reads are redacted.
        if (normalizeSsid(rawSsid) == null) {
            cachedSsid = callbackSsidFor(connectedWifi.network)
            rawSsid = cachedSsid
        }

        val cleanSsid = normalizeSsid(rawSsid)
        return if (cleanSsid == null) {
            val note = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !isLocationServiceEnabled() ->
                    "Android API ${Build.VERSION.SDK_INT}: خدمات الموقع متوقفة، وقد يمنع ذلك النظام من كشف اسم Wi-Fi. لا يمكن ضمان قراءة الاسم على هذا الإصدار مع إيقافها."
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    "Android API ${Build.VERSION.SDK_INT}: إذن الأجهزة القريبة / Wi-Fi ممنوح، لكن النظام لم يُرجع اسم الشبكة. التشخيص: WifiManager=${ssidReadStatus(managerSsid)}، NetworkCapabilities=${ssidReadStatus(capabilitiesSsid)}، ذاكرة الاتصال=${ssidReadStatus(cachedSsid)}. لم يُستخدم اسم مخمّن حتى لا يُنسب الاستهلاك إلى شبكة خاطئة."
                else ->
                    "متصل بـ Wi-Fi، لكن النظام لم يكشف اسم الشبكة باستخدام الصلاحيات الحالية؛ سيُسجّل الاستهلاك مؤقتًا تحت Wi-Fi عام."
            }
            Log.w(
                TAG,
                "SSID unresolved: api=${Build.VERSION.SDK_INT}, nearbyPermission=${hasSsidPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU}, locationPermission=${hasLocationPermission()}, locationServices=${isLocationServiceEnabled()}, wifiManager=${ssidReadStatus(managerSsid)}, networkCapabilities=${ssidReadStatus(capabilitiesSsid)}, callbackCache=${ssidReadStatus(cachedSsid)}"
            )
            val automaticNote = if (automaticNetworkLabel != null) {
                "$note سيُستخدم $automaticNetworkLabel لتجميع الجلسات دون GPS؛ هذا معرّف محلي وليس اسم الشبكة الحقيقي؛ قد يتغير مع إعدادات الشبكة أو يتكرر إذا تشابهت إعدادات شبكتين."
            } else {
                "$note لم يتوفر معرّف محلي يمكن الاعتماد عليه."
            }
            WifiConnectionState(
                isConnected = true,
                ssid = automaticNetworkLabel,
                isSsidConfirmed = false,
                notes = automaticNote
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

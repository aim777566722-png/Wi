package com.example

import com.example.data.engine.TrafficDeltaCalculator
import com.example.data.local.entity.DataUsageSnapshotEntity
import com.example.data.model.RawTrafficReading
import com.example.data.model.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TrafficDeltaCalculatorTest {

    @Test
    fun computeDelta_whenFirstReading_producesBaselineInitialWithZeroDelta() {
        // قراءة أولى عند فتح التطبيق مع وجود عدادات تراكمية سابقة في النظام
        val reading = RawTrafficReading(
            timestamp = 1000000L,
            bootElapsedRealtimeMs = 500000L,
            rawRxBytes = 500_000_000L, // 500 MB سابقة
            rawTxBytes = 100_000_000L, // 100 MB سابقة
            isWifiConnected = true,
            ssid = "Home_WiFi",
            bssid = null
        )

        val delta = TrafficDeltaCalculator.computeDelta(
            currentReading = reading,
            previousSnapshot = null
        )

        // التحقق من عدم احتساب التراكم القديم كاستهلاك جديد
        assertEquals(0L, delta.deltaRxBytes)
        assertEquals(0L, delta.deltaTxBytes)
        assertEquals(0L, delta.totalDeltaBytes)
        assertEquals(ReadingStatus.BASELINE_INITIAL, delta.status)
        assertNotNull(delta.notes)
    }

    @Test
    fun computeDelta_whenNormalConsecutiveReadings_computesCorrectDeltas() {
        // قراءة سابقة
        val previous = DataUsageSnapshotEntity(
            id = 1,
            networkInternalId = 1,
            ssid = "Home_WiFi",
            timestamp = 1000000L,
            rawRxBytes = 10_000_000L,
            rawTxBytes = 2_000_000L,
            deltaRxBytes = 0L,
            deltaTxBytes = 0L,
            readingStatus = ReadingStatus.BASELINE_INITIAL.name,
            bootElapsedTimeMs = 300_000L
        )

        // قراءة لاحقة بعد استهلاك 5 MB تنزيل و 1 MB رفع
        val current = RawTrafficReading(
            timestamp = 1060000L,
            bootElapsedRealtimeMs = 360_000L,
            rawRxBytes = 15_000_000L, // +5 MB
            rawTxBytes = 3_000_000L,  // +1 MB
            isWifiConnected = true,
            ssid = "Home_WiFi",
            bssid = null
        )

        val delta = TrafficDeltaCalculator.computeDelta(
            currentReading = current,
            previousSnapshot = previous
        )

        assertEquals(5_000_000L, delta.deltaRxBytes)
        assertEquals(1_000_000L, delta.deltaTxBytes)
        assertEquals(6_000_000L, delta.totalDeltaBytes)
        assertEquals(ReadingStatus.VALID, delta.status)
    }

    @Test
    fun computeDelta_whenNoBytesChanged_returnsNoChangeWithZeroDelta() {
        val previous = DataUsageSnapshotEntity(
            id = 1,
            networkInternalId = 1,
            ssid = "Home_WiFi",
            timestamp = 1000000L,
            rawRxBytes = 10_000_000L,
            rawTxBytes = 2_000_000L,
            deltaRxBytes = 500L,
            deltaTxBytes = 200L,
            readingStatus = ReadingStatus.VALID.name,
            bootElapsedTimeMs = 300_000L
        )

        // قراءة متطابقة مع السابقة تماماً
        val current = RawTrafficReading(
            timestamp = 1030000L,
            bootElapsedRealtimeMs = 330_000L,
            rawRxBytes = 10_000_000L,
            rawTxBytes = 2_000_000L,
            isWifiConnected = true,
            ssid = "Home_WiFi",
            bssid = null
        )

        val delta = TrafficDeltaCalculator.computeDelta(
            currentReading = current,
            previousSnapshot = previous
        )

        assertEquals(0L, delta.deltaRxBytes)
        assertEquals(0L, delta.deltaTxBytes)
        assertEquals(ReadingStatus.NO_CHANGE, delta.status)
    }

    @Test
    fun computeDelta_whenSystemRebootDetected_resetsBaselineWithZeroDelta() {
        // قراءة قبل إعادة تشغيل الهاتف (وقت التشغيل 10 ساعات = 36,000,000 ms)
        val previous = DataUsageSnapshotEntity(
            id = 1,
            networkInternalId = 1,
            ssid = "Office_WiFi",
            timestamp = 1000000L,
            rawRxBytes = 80_000_000L,
            rawTxBytes = 20_000_000L,
            deltaRxBytes = 5_000L,
            deltaTxBytes = 1_000L,
            readingStatus = ReadingStatus.VALID.name,
            bootElapsedTimeMs = 36_000_000L
        )

        // قراءة بعد إعادة تشغيل الهاتف (وقت التشغيل عاد إلى دقيقتين = 120,000 ms والعدادات ت صفرت)
        val current = RawTrafficReading(
            timestamp = 1005000L,
            bootElapsedRealtimeMs = 120_000L, // أقل بكثير من القراءة السابقة -> رصد إعادة التشغيل
            rawRxBytes = 50_000L,
            rawTxBytes = 10_000L,
            isWifiConnected = true,
            ssid = "Office_WiFi",
            bssid = null
        )

        val delta = TrafficDeltaCalculator.computeDelta(
            currentReading = current,
            previousSnapshot = previous
        )

        assertEquals(0L, delta.deltaRxBytes)
        assertEquals(0L, delta.deltaTxBytes)
        assertEquals(ReadingStatus.REBOOT_RESET, delta.status)
        assertNotNull(delta.notes)
    }

    @Test
    fun computeDelta_whenNetworkSwitched_createsNewSessionWithZeroDelta() {
        val previous = DataUsageSnapshotEntity(
            id = 1,
            networkInternalId = 1,
            ssid = "Home_WiFi",
            timestamp = 1000000L,
            rawRxBytes = 20_000_000L,
            rawTxBytes = 5_000_000L,
            deltaRxBytes = 100_000L,
            deltaTxBytes = 50_000L,
            readingStatus = ReadingStatus.VALID.name,
            bootElapsedTimeMs = 400_000L
        )

        val current = RawTrafficReading(
            timestamp = 1030000L,
            bootElapsedRealtimeMs = 430_000L,
            rawRxBytes = 22_000_000L,
            rawTxBytes = 6_000_000L,
            isWifiConnected = true,
            ssid = "CoffeeShop_WiFi", // شبكة جديدة مختلفة
            bssid = null
        )

        val delta = TrafficDeltaCalculator.computeDelta(
            currentReading = current,
            previousSnapshot = previous
        )

        assertEquals(0L, delta.deltaRxBytes)
        assertEquals(0L, delta.deltaTxBytes)
        assertEquals(ReadingStatus.NETWORK_SWITCH, delta.status)
        assertNotNull(delta.notes)
    }

    @Test
    fun computeDelta_whenWifiDisconnected_returnsDisconnectedFallback() {
        val reading = RawTrafficReading(
            timestamp = 1000000L,
            bootElapsedRealtimeMs = 100000L,
            rawRxBytes = 0L,
            rawTxBytes = 0L,
            isWifiConnected = false,
            ssid = null,
            bssid = null
        )

        val delta = TrafficDeltaCalculator.computeDelta(
            currentReading = reading,
            previousSnapshot = null
        )

        assertEquals(0L, delta.deltaRxBytes)
        assertEquals(0L, delta.deltaTxBytes)
        assertEquals(ReadingStatus.DISCONNECTED_FALLBACK, delta.status)
    }

    @Test
    fun computeDelta_whenReconnectingAfterDisconnection_detectsDataGapWithZeroDelta() {
        // السجل السابق كان انقطاع اتصال Wi-Fi
        val previous = DataUsageSnapshotEntity(
            id = 1,
            networkInternalId = null,
            ssid = "غير متصل بـ Wi-Fi",
            timestamp = 1000000L,
            rawRxBytes = 30_000_000L,
            rawTxBytes = 5_000_000L,
            deltaRxBytes = 0L,
            deltaTxBytes = 0L,
            readingStatus = ReadingStatus.DISCONNECTED_FALLBACK.name,
            bootElapsedTimeMs = 500_000L
        )

        // عودة الاتصال بشبكة Home_WiFi، مع زيادة في العدادات حدثت أثناء الانقطاع
        val current = RawTrafficReading(
            timestamp = 1050000L,
            bootElapsedRealtimeMs = 550_000L,
            rawRxBytes = 35_000_000L, // +5 MB أثناء الانقطاع
            rawTxBytes = 6_000_000L,
            isWifiConnected = true,
            ssid = "Home_WiFi",
            bssid = null
        )

        val delta = TrafficDeltaCalculator.computeDelta(
            currentReading = current,
            previousSnapshot = previous
        )

        // يجب رصد فجوة البيانات وعدم نسب حركة فترة الانقطاع لـ Home_WiFi
        assertEquals(0L, delta.deltaRxBytes)
        assertEquals(0L, delta.deltaTxBytes)
        assertEquals(ReadingStatus.DATA_GAP_DETECTED, delta.status)
        assertNotNull(delta.notes)
    }

    @Test
    fun computeDelta_whenSsidIsUnknown_recordsGeneralWifiStatusWithoutGuessing() {
        val previous = DataUsageSnapshotEntity(
            id = 1,
            networkInternalId = null,
            ssid = TrafficDeltaCalculator.GENERAL_WIFI_FALLBACK_SSID,
            timestamp = 1000000L,
            rawRxBytes = 10_000_000L,
            rawTxBytes = 2_000_000L,
            deltaRxBytes = 0L,
            deltaTxBytes = 0L,
            readingStatus = ReadingStatus.UNKNOWN_SSID.name,
            bootElapsedTimeMs = 300_000L
        )

        // قراءة لاحقة مع بقاء SSID غير متاح لعدم منح الصلاحية
        val current = RawTrafficReading(
            timestamp = 1060000L,
            bootElapsedRealtimeMs = 360_000L,
            rawRxBytes = 12_000_000L, // +2 MB
            rawTxBytes = 3_000_000L,  // +1 MB
            isWifiConnected = true,
            ssid = null, // غير مؤكد
            bssid = null,
            isSsidConfirmed = false
        )

        val delta = TrafficDeltaCalculator.computeDelta(
            currentReading = current,
            previousSnapshot = previous
        )

        // احتساب الزيادة مع وسم الحالة بوضوح كـ UNKNOWN_SSID لعدم تضليل المستخدم
        assertEquals(2_000_000L, delta.deltaRxBytes)
        assertEquals(1_000_000L, delta.deltaTxBytes)
        assertEquals(ReadingStatus.UNKNOWN_SSID, delta.status)
        assertNotNull(delta.notes)
    }

    @Test
    fun computeDelta_whenCounterRollbackDetected_returnsCounterRollback() {
        val previous = DataUsageSnapshotEntity(
            id = 1,
            networkInternalId = 1,
            ssid = "Home_WiFi",
            timestamp = 1000000L,
            rawRxBytes = 50_000_000L,
            rawTxBytes = 10_000_000L,
            deltaRxBytes = 10_000L,
            deltaTxBytes = 2_000L,
            readingStatus = ReadingStatus.VALID.name,
            bootElapsedTimeMs = 1_000_000L
        )

        // تراجع في العداد الخام بدون إعادة تشغيل (حالة شاذة)
        val current = RawTrafficReading(
            timestamp = 1010000L,
            bootElapsedRealtimeMs = 1_010_000L,
            rawRxBytes = 40_000_000L, // انخفض العداد فجأة
            rawTxBytes = 10_000_000L,
            isWifiConnected = true,
            ssid = "Home_WiFi",
            bssid = null
        )

        val delta = TrafficDeltaCalculator.computeDelta(
            currentReading = current,
            previousSnapshot = previous
        )

        assertEquals(0L, delta.deltaRxBytes)
        assertEquals(0L, delta.deltaTxBytes)
        assertEquals(ReadingStatus.COUNTER_ROLLBACK, delta.status)
    }
}

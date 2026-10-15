package com.example.data.engine

import com.example.data.local.entity.DataUsageSnapshotEntity
import com.example.data.model.ComputedDelta
import com.example.data.model.RawTrafficReading
import com.example.data.model.ReadingStatus

/**
 * محرك احتساب فروق استهلاك البيانات بدقة تقنية صارمة
 * يراعي القيود التقنية لـ Android:
 * - إعادة تشغيل الجهاز وتصفير العدادات (System Reboot)
 * - تغير شبكة Wi-Fi وانتقال الاتصال
 * - انقطاع الاتصال وفجوات البيانات (Data Gaps)
 * - العدادات التراكمية السابقة لضمان عدم احتساب كامل التراكم كاستهلاك جديد
 * - الانهيارات أو التراجعات الشاذة في عدادات النظام (Rollbacks)
 * - عدم تكرار احتساب القراءة نفسها (Duplicate readings / No change)
 * - إخفاء النظام لاسم الشبكة وعدم تأكيد SSID (Unknown SSID)
 */
object TrafficDeltaCalculator {

    const val GENERAL_WIFI_FALLBACK_SSID = "Wi-Fi عام (بدون تحديد SSID)"

    /**
     * احتساب الفرق بين القراءة الحالية وآخر قراءة مسجلة
     */
    fun computeDelta(
        currentReading: RawTrafficReading,
        previousSnapshot: DataUsageSnapshotEntity?
    ): ComputedDelta {
        // 1. حالة عدم الاتصال بشبكة Wi-Fi
        if (!currentReading.isWifiConnected) {
            return ComputedDelta(
                deltaRxBytes = 0L,
                deltaTxBytes = 0L,
                status = ReadingStatus.DISCONNECTED_FALLBACK,
                notes = "الهاتف غير متصل بأي شبكة Wi-Fi حالياً؛ توقف التسجيل"
            )
        }

        // 2. حالة القراءة المرجعية الأولى (لا توجد سجلات سابقة)
        if (previousSnapshot == null) {
            return ComputedDelta(
                deltaRxBytes = 0L,
                deltaTxBytes = 0L,
                status = ReadingStatus.BASELINE_INITIAL,
                notes = "نقطة مرجعية أولى. تم تسجيل العدادات الخام بدون احتساب استهلاك سابق"
            )
        }

        // 3. التحقق من إعادة تشغيل الهاتف (System Reboot)
        val isRebootDetected = currentReading.bootElapsedRealtimeMs < previousSnapshot.bootElapsedTimeMs ||
                (currentReading.rawRxBytes < previousSnapshot.rawRxBytes && currentReading.bootElapsedRealtimeMs < 120_000L)

        if (isRebootDetected) {
            return ComputedDelta(
                deltaRxBytes = 0L,
                deltaTxBytes = 0L,
                status = ReadingStatus.REBOOT_RESET,
                notes = "تم رصد إعادة تشغيل الجهاز وتصفير عدادات النظام؛ تم تعيين أساس جديد"
            )
        }

        // 4. التحقق من العودة بعد انقطاع الاتصال (Data Gap)
        // إذا كان السجل السابق حالة انقطاع، فإن فرق العدادات الحالي يحتوي حركة حدثت خارج شبكة Wi-Fi
        // لا يجوز نسب هذا الفرق لشبكة Wi-Fi المستعادة، بل نسجل فجوة وأساساً جديداً
        if (previousSnapshot.readingStatus == ReadingStatus.DISCONNECTED_FALLBACK.name) {
            return ComputedDelta(
                deltaRxBytes = 0L,
                deltaTxBytes = 0L,
                status = ReadingStatus.DATA_GAP_DETECTED,
                notes = "إعادة اتصال بعد انقطاع؛ تم تسجيل أساس جديد دون احتساب استهلاك مجهول خلال فترة الانقطاع"
            )
        }

        // 5. التحقق من تغير شبكة Wi-Fi
        val currentSsid = currentReading.ssid ?: GENERAL_WIFI_FALLBACK_SSID
        if (previousSnapshot.ssid != currentSsid) {
            return ComputedDelta(
                deltaRxBytes = 0L,
                deltaTxBytes = 0L,
                status = ReadingStatus.NETWORK_SWITCH,
                notes = "تم التبديل من [${previousSnapshot.ssid}] إلى [${currentSsid}]؛ تم فتح جلسة قياس جديدة"
            )
        }

        // 6. التحقق من تراجع شاذ في العدادات (Counter Rollback / Overflow)
        if (currentReading.rawRxBytes < previousSnapshot.rawRxBytes ||
            currentReading.rawTxBytes < previousSnapshot.rawTxBytes
        ) {
            return ComputedDelta(
                deltaRxBytes = 0L,
                deltaTxBytes = 0L,
                status = ReadingStatus.COUNTER_ROLLBACK,
                notes = "رُصد انخفاض غير مبرر في عدادات النظام؛ تم استبعاد الزيادة السلبية"
            )
        }

        // 7. التحقق من عدم حدوث أي تغيير في العدادات (No Change / منع التكرار غير المفيد)
        if (currentReading.rawRxBytes == previousSnapshot.rawRxBytes &&
            currentReading.rawTxBytes == previousSnapshot.rawTxBytes
        ) {
            return ComputedDelta(
                deltaRxBytes = 0L,
                deltaTxBytes = 0L,
                status = ReadingStatus.NO_CHANGE,
                notes = "لا توجد حركة بيانات جديدة منذ القراءة السابقة"
            )
        }

        // 8. احتساب الزيادة الفعلية
        val deltaRx = currentReading.rawRxBytes - previousSnapshot.rawRxBytes
        val deltaTx = currentReading.rawTxBytes - previousSnapshot.rawTxBytes

        // إذا كان اسم SSID غير مؤكد (حتى مع وجود صلاحية)، يتم توثيق الحالة كـ UNKNOWN_SSID
        val status = if (!currentReading.isSsidConfirmed || currentReading.ssid == null) {
            ReadingStatus.UNKNOWN_SSID
        } else {
            ReadingStatus.VALID
        }

        val notes = if (status == ReadingStatus.UNKNOWN_SSID) {
            "اسم SSID الحقيقي غير متاح من Android؛ يُجمع الاستهلاك تحت المعرّف المحلي إن توفر، وليس تحت اسم شبكة مؤكد."
        } else null

        return ComputedDelta(
            deltaRxBytes = deltaRx,
            deltaTxBytes = deltaTx,
            status = status,
            notes = notes
        )
    }
}

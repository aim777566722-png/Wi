package com.example.data.model

/**
 * حالة قراءة العداد وتحديد موثوقية احتساب فرق الاستهلاك
 */
enum class ReadingStatus(val arabicLabel: String, val descriptionArabic: String) {
    VALID("قراءة موثوقة", "تم احتساب فرق الاستهلاك بشكل طبيعي من عدادات النظام أثناء الاتصال المستمر"),
    BASELINE_INITIAL("نقطة مرجعية أولى", "القراءة الأولى بعد بدء المراقبة أو فتح التطبيق؛ فرق الاستهلاك = 0 لتفادي احتساب تراكم الماضي"),
    REBOOT_RESET("إعادة تشغيل الجهاز", "تم رصد إعادة ضبط عدادات النظام الصفرية إثر إعادة تشغيل الهاتف وتأسيس نقطة بداية جديدة"),
    NETWORK_SWITCH("تغيير الشبكة", "تم رصد التبديل إلى شبكة Wi-Fi جديدة وتسجيل أساس جديد دون دمج حركة الشبكات"),
    DISCONNECTED_FALLBACK("انقطاع الاتصال", "فقدان الاتصال بشبكة Wi-Fi؛ توقف احتساب الاستهلاك حتى عودة الاتصال"),
    DATA_GAP_DETECTED("فجوة في البيانات", "تم رصد انقطاع ثم إعادة اتصال؛ تم تسجيل أساس جديد دون احتساب استهلاك افتراضي خلال الفجوة"),
    UNKNOWN_SSID("شبكة غير محددة الاسم", "اتصال Wi-Fi نشط ولكن تعذر استخراج SSID بدقة لغياب صلاحية الموقع؛ تم تسجيل الاستهلاك كـ Wi-Fi عام"),
    COUNTER_ROLLBACK("اختلال في العداد", "قراءة غير منطقية أو تراجع بالعداد الخام؛ تم استبعادها تلقائياً لحماية سلامة السجلات"),
    NO_CHANGE("لا يوجد استهلاك جديد", "القراءة متطابقة مع السابقة تماماً؛ لم تطرأ أي زيادة في نقل البيانات")
}

/**
 * حالة خدمة المراقبة
 */
enum class ServiceMonitoringState(val arabicLabel: String) {
    ACTIVE("المراقبة نشطة وتعمل في الخلفية"),
    WAITING_WIFI("المراقبة تعمل وبانتظار اتصال Wi-Fi"),
    STOPPED("المراقبة متوقفة"),
    PERMISSION_MISSING("تتطلب منح الصلاحيات اللازمة")
}

/**
 * حالة تفصيلية لاتصال Wi-Fi
 */
data class WifiConnectionState(
    val isConnected: Boolean,
    val ssid: String?,
    val isSsidConfirmed: Boolean,
    val notes: String? = null
)

/**
 * قراءة خام مباشرة من Android APIs (TrafficStats / NetworkStatsManager)
 */
data class RawTrafficReading(
    val timestamp: Long,
    val bootElapsedRealtimeMs: Long,
    val rawRxBytes: Long, // إجمالي بايتات الاستقبال (تنزيل)
    val rawTxBytes: Long, // إجمالي بايتات الإرسال (رفع)
    val isWifiConnected: Boolean,
    val ssid: String?,
    val bssid: String?,
    val isSsidConfirmed: Boolean = true
)

/**
 * فرق الاستهلاك المحسوب منذ القراءة السابقة
 */
data class ComputedDelta(
    val deltaRxBytes: Long,
    val deltaTxBytes: Long,
    val status: ReadingStatus,
    val notes: String? = null
) {
    val totalDeltaBytes: Long
        get() = deltaRxBytes + deltaTxBytes
}

/**
 * ملخص إحصائي لاستهلاك شبكة Wi-Fi معينة
 */
data class NetworkSummary(
    val internalId: Long,
    val ssid: String,
    val firstSeenTimestamp: Long,
    val lastSeenTimestamp: Long,
    val totalRxBytes: Long,
    val totalTxBytes: Long,
    val todayRxBytes: Long,
    val todayTxBytes: Long,
    val monthRxBytes: Long,
    val monthTxBytes: Long,
    val snapshotsCount: Int
) {
    val totalBytes: Long get() = totalRxBytes + totalTxBytes
    val todayTotalBytes: Long get() = todayRxBytes + todayTxBytes
    val monthTotalBytes: Long get() = monthRxBytes + monthTxBytes
}

/**
 * الفترات الزمنية للفلترة في شاشات الإحصائيات
 */
enum class FilterPeriod(val arabicTitle: String) {
    TODAY("اليوم"),
    WEEK("الأسبوع"),
    MONTH("الشهر"),
    ALL_TIME("الكل")
}

/**
 * معلومات القيود التقنية المعروفة لنظام Android
 */
data class TechnicalConstraintInfo(
    val title: String,
    val description: String,
    val recommendation: String
)

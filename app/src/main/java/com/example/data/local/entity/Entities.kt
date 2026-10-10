package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * جدول شبكات Wi-Fi المسجلة محلياً
 * تم تصميمه مع حقول قابلة للتوسع مستقبلاً لمزامنة الأجهزة والعائلات
 */
@Entity(
    tableName = "wifi_networks",
    indices = [
        Index(value = ["ssid"]),
        Index(value = ["deviceId"])
    ]
)
data class NetworkEntity(
    @PrimaryKey(autoGenerate = true)
    val internalId: Long = 0,
    val ssid: String,
    val bssid: String? = null,
    val firstSeenTimestamp: Long = System.currentTimeMillis(),
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val customAlias: String? = null,
    val deviceId: String = "local_device",
    val userId: String? = null
)

/**
 * جدول سجلات وقراءات استهلاك البيانات
 * يحفظ القراءات الخام والفروق المحسوبة وحالة القراءة
 * مصمم للاحتفاظ بسجلات لسنوات دون حذف تلقائي
 */
@Entity(
    tableName = "data_usage_snapshots",
    indices = [
        Index(value = ["ssid"]),
        Index(value = ["timestamp"]),
        Index(value = ["networkInternalId"]),
        Index(value = ["deviceId"])
    ]
)
data class DataUsageSnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val networkInternalId: Long? = null,
    val ssid: String,
    val timestamp: Long = System.currentTimeMillis(),
    val rawRxBytes: Long,
    val rawTxBytes: Long,
    val deltaRxBytes: Long,
    val deltaTxBytes: Long,
    val readingStatus: String, // Value from ReadingStatus enum
    val bootElapsedTimeMs: Long,
    val deviceId: String = "local_device",
    val userId: String? = null,
    val notes: String? = null
)

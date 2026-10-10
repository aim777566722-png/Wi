package com.example.ui.components

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {

    /**
     * تحويل حجم البايتات إلى نص عربي مقروء ومختصر بدقة عالية
     */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 بايت"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        val tb = gb / 1024.0

        return when {
            tb >= 1.0 -> String.format(Locale.getDefault(), "%.2f تيرابايت", tb)
            gb >= 1.0 -> String.format(Locale.getDefault(), "%.2f جيجابايت", gb)
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.2f ميجابايت", mb)
            kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f كيلوبايت", kb)
            else -> "$bytes بايت"
        }
    }

    /**
     * تنسيق التاريخ والوقت باللغة العربية
     */
    fun formatDateTime(timestamp: Long): String {
        if (timestamp <= 0) return "غير متوفر"
        val sdf = SimpleDateFormat("yyyy/MM/dd  HH:mm:ss", Locale("ar"))
        return sdf.format(Date(timestamp))
    }

    /**
     * تنسيق الوقت فقط
     */
    fun formatTime(timestamp: Long): String {
        if (timestamp <= 0) return "-"
        val sdf = SimpleDateFormat("HH:mm:ss", Locale("ar"))
        return sdf.format(Date(timestamp))
    }

    /**
     * تنسيق التاريخ فقط
     */
    fun formatDate(timestamp: Long): String {
        if (timestamp <= 0) return "-"
        val sdf = SimpleDateFormat("yyyy/MM/dd", Locale("ar"))
        return sdf.format(Date(timestamp))
    }
}

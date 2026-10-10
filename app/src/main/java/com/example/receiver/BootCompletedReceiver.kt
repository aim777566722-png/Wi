package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.preferences.MonitoringPreferences
import com.example.service.WifiMonitoringService

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            // التحقق مما إذا كان المستخدم قد فعّل المراقبة قبل إعادة التشغيل
            if (MonitoringPreferences.isMonitoringEnabled(context)) {
                try {
                    WifiMonitoringService.startService(context)
                } catch (e: Exception) {
                    // في بعض إصدارات Android 12+ قد تفرض قيود على Foreground Service من BroadcastReceiver
                }
            }
        }
    }
}

package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.service.WifiMonitoringService

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            try {
                WifiMonitoringService.startService(context)
            } catch (e: Exception) {
                // قد تمنع بعض إصدارات Android بدء الخدمة في الخلفية؛ يبدأ التطبيق المراقبة عند فتحه.
            }
        }
    }
}

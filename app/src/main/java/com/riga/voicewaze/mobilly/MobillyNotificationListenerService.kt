package com.riga.voicewaze.mobilly

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MobillyNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MobillyListener"
        private const val RIX_PREFS_NAME = "rix_state"
        private const val RIX_ENTRY_TIMESTAMP_KEY = "entry_timestamp"
        private const val RIX_EVENT_ACTION = "com.riga.voicewaze.RIX_EVENT_UPDATED"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName.orEmpty()
        if (!packageName.contains("mobilly", ignoreCase = true)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        val fullText = listOf(title, text, bigText)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        if (fullText.isBlank()) return

        Log.d(TAG, "PACKAGE: $packageName")
        Log.d(TAG, "TEXT: $fullText")

        if (!fullText.contains("RIXA", ignoreCase = true)) return

        if (fullText.contains("начата", ignoreCase = true)) {
            handleRixEntry()
            return
        }

        if (fullText.contains("Сумма оплаты:", ignoreCase = true)) {
            Log.d(TAG, "RIX exit notification detected")
            sendRixEventBroadcast()
        }
    }

    private fun handleRixEntry() {
        val prefs = getSharedPreferences(RIX_PREFS_NAME, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs.edit().putLong(RIX_ENTRY_TIMESTAMP_KEY, now).apply()
        Log.d(TAG, "RIX entry saved: $now")
        sendRixEventBroadcast()
    }

    private fun sendRixEventBroadcast() {
        sendBroadcast(Intent(RIX_EVENT_ACTION).setPackage(packageName))
    }
}

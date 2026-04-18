package com.riga.voicewaze.mobilly

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast

class MobillyNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return

        if (!pkg.lowercase().contains("mobilly")) return

        val notification: Notification = sbn.notification
        val extras = notification.extras

        val title = extras.getString(Notification.EXTRA_TITLE)?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val ticker = notification.tickerText?.toString()?.trim().orEmpty()

        val message = buildString {
            append("Mobilly")
            if (title.isNotEmpty()) {
                append("\n")
                append(title)
            }
            when {
                text.isNotEmpty() -> {
                    append("\n")
                    append(text)
                }
                bigText.isNotEmpty() -> {
                    append("\n")
                    append(bigText)
                }
                ticker.isNotEmpty() -> {
                    append("\n")
                    append(ticker)
                }
            }
        }

        if (message.isBlank()) return

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }
}
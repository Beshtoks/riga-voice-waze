package com.riga.voicewaze.jurmala

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build

class JurmalaReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {

        val store = JurmalaPointStore(context)
        if (store.isPaidToday()) return

        val today = JurmalaTime.todayKey()
        val pointName = store.getPendingPointName(today) ?: "Юрмала"
        val type = intent?.getStringExtra("type") ?: ""

        showStrongNotification(context, pointName, type)

        when (type) {
            "2330", "10" -> {
                JurmalaScheduler.schedule10Min(
                    context,
                    System.currentTimeMillis() + 10 * 60 * 1000L
                )
            }
        }
    }

    private fun showStrongNotification(
        context: Context,
        pointName: String,
        type: String
    ) {

        val channelId = "jurmala_strong_channel"
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val soundUri =
                Uri.parse("android.resource://${context.packageName}/raw/jurmala_alert_urgent")

            val channel = NotificationChannel(
                channelId,
                "Юрмала — уведомления",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Критические напоминания Юрмалы"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 300, 500)
                setSound(
                    soundUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, JurmalaAlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("point_name", pointName)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            777,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (type) {
            "2330" -> "Юрмала — требуется подтверждение"
            "10" -> "Юрмала — повторное напоминание"
            "60" -> "Юрмала — напоминание"
            else -> "Юрмала"
        }

        val text = when (type) {
            "2330" -> "После 23:30 требуется подтверждение оплаты"
            "10" -> "Оплата ещё не подтверждена"
            "60" -> "Напоминание по въезду в зону"
            else -> "Нажми, чтобы открыть окно и подтвердить"
        }

        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)

        val notification = builder.build()

        val notificationId = ((System.currentTimeMillis() / 1000L) % Int.MAX_VALUE).toInt()
        manager.notify(notificationId, notification)
    }
}
package com.riga.voicewaze.jurmala

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class JurmalaForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        val store = JurmalaPointStore(this)

        val pointName = intent?.getStringExtra("point_name") ?: "Юрмала"
        val today = JurmalaTime.todayKey()

        // если уже было предупреждение сегодня — не дублируем
        if (store.isAlertShownToday(today)) {
            return START_STICKY
        }

        // фиксируем факт входа
        store.markZoneEnteredToday(today, pointName)
        store.setAlertShownToday(today)

        // показываем жёсткое окно
        showAlert(pointName)

        return START_STICKY
    }

    private fun showAlert(pointName: String) {
        val intent = Intent(this, JurmalaAlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("point_name", pointName)
        }
        startActivity(intent)
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, "jurmala_channel")
            .setContentTitle("Jurmala active")
            .setContentText("Monitoring zone")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "jurmala_channel",
                "Jurmala",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
package com.riga.voicewaze.jurmala

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat

class JurmalaForegroundService : Service() {

    companion object {
        private const val SERVICE_CHANNEL_ID = "jurmala_service_channel"
        private const val ALERT_CHANNEL_ID = "jurmala_alert_channel"
        private const val SERVICE_NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002

        const val EXTRA_POINT_NAME = "point_name"
    }

    private lateinit var store: JurmalaPointStore
    private var locationManager: JurmalaLocationManager? = null

    override fun onCreate() {
        super.onCreate()

        store = JurmalaPointStore(this)
        createChannels()

        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification())

        locationManager = JurmalaLocationManager(
            context = this,
            store = store
        ) { point ->
            handleZoneEnter(point)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!store.isEnabled()) {
            locationManager?.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        val shouldTrackLocation = !store.isEnteredToday()

        if (shouldTrackLocation) {
            locationManager?.start()
        } else {
            locationManager?.stop()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        locationManager?.stop()
        locationManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleZoneEnter(point: JurmalaPoint) {
        val dayKey = JurmalaTime.todayKey()

        if (store.isPaidToday(dayKey)) {
            locationManager?.stop()
            return
        }

        store.setEnteredToday(dayKey)
        store.markZoneEnteredToday(dayKey, point.name)
        locationManager?.stop()

        if (store.isAlertShownToday(dayKey)) return

        store.setAlertShownToday(dayKey)

        if (canShowOverlay()) {
            val overlayIntent = Intent(this, JurmalaOverlayService::class.java).apply {
                putExtra(EXTRA_POINT_NAME, point.name)
            }
            startService(overlayIntent)
        } else {
            showAlertNotification(point.name)
        }
    }

    private fun canShowOverlay(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun buildServiceNotification(): Notification {
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("Контроль Юрмалы")
            .setContentText("Активен")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun showAlertNotification(pointName: String) {
        val fullScreenIntent = Intent(this, JurmalaAlertActivity::class.java).apply {
            putExtra(EXTRA_POINT_NAME, pointName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            2001,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = "Въезд через: $pointName"

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("Зона Юрмалы")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "Контроль Юрмалы",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Фоновый контроль въезда в зону Юрмалы"
            setShowBadge(false)
        }

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Уведомления Юрмалы",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Предупреждения о въезде в зону Юрмалы"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }

        nm.createNotificationChannel(serviceChannel)
        nm.createNotificationChannel(alertChannel)
    }
}
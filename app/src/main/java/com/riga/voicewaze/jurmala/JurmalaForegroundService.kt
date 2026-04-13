package com.riga.voicewaze.jurmala

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.riga.voicewaze.R
import com.riga.voicewaze.ui.main.MainActivity
import java.util.Calendar
import java.util.Locale

class JurmalaForegroundService : Service() {

    companion object {
        private const val SERVICE_CHANNEL_ID = "jurmala_service_channel"
        private const val ALERT_CHANNEL_ID = "jurmala_alert_channel"
        private const val SERVICE_NOTIFICATION_ID = 4101
        private const val ALERT_NOTIFICATION_ID = 4102
    }

    private lateinit var store: JurmalaPointStore
    private var locationManager: JurmalaLocationManager? = null

    override fun onCreate() {
        super.onCreate()
        store = JurmalaPointStore(this)
        ensureChannels()
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification())
        startLocationTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!store.isEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasFineLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (locationManager == null) {
            startLocationTracking()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        locationManager?.stop()
        locationManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLocationTracking() {
        if (!store.isEnabled()) return
        if (!hasFineLocationPermission()) return

        locationManager?.stop()
        locationManager = JurmalaLocationManager(
            context = this,
            store = store
        ) { point ->
            handleZoneEnter(point)
        }
        try {
            locationManager?.start()
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun handleZoneEnter(point: JurmalaPoint) {
        val dayKey = currentDayKey()
        if (store.isPaidToday(dayKey)) return

        store.markZoneEnteredToday(dayKey, point.name)
        showAlertNotification(point.name)
    }

    private fun buildServiceNotification() = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_map)
        .setContentTitle("Контроль Юрмалы")
        .setContentText("Работает в фоне")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun showAlertNotification(pointName: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("jurmala_open_dialog", true)
            putExtra("jurmala_point_name", pointName)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            2001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutableFlag()
        )

        val builder = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Зона Юрмалы")
            .setContentText("Въезд через: $pointName")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Въезд через: $pointName. Нажми, чтобы открыть приложение и подтвердить оплату."))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (hasPostNotificationsPermission()) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(ALERT_NOTIFICATION_ID, builder.build())
        }
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)

        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "Контроль Юрмалы",
            NotificationManager.IMPORTANCE_LOW
        )

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Въезд в зону Юрмалы",
            NotificationManager.IMPORTANCE_HIGH
        )

        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(alertChannel)
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasPostNotificationsPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun pendingIntentMutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    private fun currentDayKey(): String {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }
}

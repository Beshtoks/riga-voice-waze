package com.riga.voicewaze.jurmala

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object JurmalaScheduler {

    private const val REQ_60_MIN = 1001
    private const val REQ_2330 = 1002
    private const val REQ_10_MIN = 1003

    const val ACTION_REMINDER = "com.riga.voicewaze.jurmala.JURMALA_REMINDER"
    const val EXTRA_TYPE = "type"

    private const val TYPE_60 = "60"
    private const val TYPE_2330 = "2330"
    private const val TYPE_10 = "10"

    fun scheduleAfterThanks(context: Context) {
        val store = JurmalaPointStore(context)
        if (store.isPaidToday()) return

        cancelAll(context)

        val now = System.currentTimeMillis()
        if (isAfter2330()) {
            schedule10Min(context, now + TEN_MINUTES_MS)
        } else {
            schedule60Min(context, now + SIXTY_MINUTES_MS)
            schedule2330(context)
        }
    }

    fun schedule10Min(context: Context, triggerAt: Long) {
        schedule(context, REQ_10_MIN, TYPE_10, triggerAt)
    }

    fun scheduleNextTenMinuteReminder(context: Context) {
        schedule10Min(context, System.currentTimeMillis() + TEN_MINUTES_MS)
    }

    fun cancelAll(context: Context) {
        val alarm = getAlarm(context)
        alarm.cancel(createPendingIntent(context, REQ_60_MIN, TYPE_60))
        alarm.cancel(createPendingIntent(context, REQ_2330, TYPE_2330))
        alarm.cancel(createPendingIntent(context, REQ_10_MIN, TYPE_10))
    }

    private fun schedule60Min(context: Context, triggerAt: Long) {
        schedule(context, REQ_60_MIN, TYPE_60, triggerAt)
    }

    private fun schedule2330(context: Context) {
        val trigger = getToday2330Millis()
        val now = System.currentTimeMillis()
        if (trigger <= now) return
        schedule(context, REQ_2330, TYPE_2330, trigger)
    }

    private fun schedule(context: Context, requestCode: Int, type: String, triggerAt: Long) {
        val alarmManager = getAlarm(context)
        val pendingIntent = createPendingIntent(context, requestCode, type)
        alarmManager.cancel(pendingIntent)

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms() -> {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
            else -> {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }
    }

    private fun createPendingIntent(context: Context, requestCode: Int, type: String): PendingIntent {
        val intent = Intent(context, JurmalaReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_TYPE, type)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getAlarm(context: Context): AlarmManager {
        return context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    private fun isAfter2330(): Boolean {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        return h > 23 || (h == 23 && m >= 30)
    }

    private fun getToday2330Millis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 30)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private const val TEN_MINUTES_MS = 10 * 60 * 1000L
    private const val SIXTY_MINUTES_MS = 60 * 60 * 1000L
}

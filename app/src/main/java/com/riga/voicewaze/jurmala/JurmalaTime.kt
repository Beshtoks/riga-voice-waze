package com.riga.voicewaze.jurmala

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object JurmalaTime {

    private val rigaTimeZone: TimeZone = TimeZone.getTimeZone("Europe/Riga")

    fun todayKey(nowMillis: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
        sdf.timeZone = rigaTimeZone
        return sdf.format(Date(nowMillis))
    }

    fun isUrgentWindow(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val calendar = Calendar.getInstance(rigaTimeZone)
        calendar.timeInMillis = nowMillis
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        return hour > 23 || (hour == 23 && minute >= 30)
    }

    fun todayUrgentStartMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance(rigaTimeZone)
        calendar.timeInMillis = nowMillis
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 30)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun endOfTodayMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance(rigaTimeZone)
        calendar.timeInMillis = nowMillis
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        return calendar.timeInMillis
    }
}

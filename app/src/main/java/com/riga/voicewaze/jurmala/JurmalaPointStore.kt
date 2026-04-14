package com.riga.voicewaze.jurmala

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class JurmalaPointStore(context: Context) {

    private val prefs = context.getSharedPreferences("jurmala_store", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_POINTS = "points"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PAID_DAY = "paid_day"
        private const val KEY_PENDING_DAY = "pending_day"
        private const val KEY_PENDING_POINT_NAME = "pending_point_name"
        private const val KEY_LAST_LATE_REMINDER_AT = "last_late_reminder_at"
        private const val KEY_ENTERED_DAY = "entered_day"
    }

    fun savePoints(list: List<JurmalaPoint>) {
        val arr = JSONArray()
        list.forEach { point ->
            val obj = JSONObject()
            obj.put("name", point.name)
            obj.put("lat", point.lat)
            obj.put("lng", point.lng)
            obj.put("radius", point.radius)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_POINTS, arr.toString()).apply()
    }

    fun loadPoints(): MutableList<JurmalaPoint> {
        val result = mutableListOf<JurmalaPoint>()
        val raw = prefs.getString(KEY_POINTS, null) ?: return result

        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result.add(
                JurmalaPoint(
                    name = obj.getString("name"),
                    lat = obj.getDouble("lat"),
                    lng = obj.getDouble("lng"),
                    radius = obj.getInt("radius")
                )
            )
        }
        return result
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isEnabled(): Boolean {
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setPaidToday(dayKey: String = JurmalaTime.todayKey()) {
        prefs.edit().putString(KEY_PAID_DAY, dayKey).apply()
        clearPendingToday()
    }

    fun isPaidToday(dayKey: String = JurmalaTime.todayKey()): Boolean {
        return prefs.getString(KEY_PAID_DAY, "") == dayKey
    }

    fun clearPaidToday() {
        prefs.edit()
            .remove(KEY_PAID_DAY)
            .remove(KEY_LAST_LATE_REMINDER_AT)
            .apply()
    }

    fun markZoneEnteredToday(dayKey: String, pointName: String) {
        prefs.edit()
            .putString(KEY_PENDING_DAY, dayKey)
            .putString(KEY_PENDING_POINT_NAME, pointName)
            .putString(KEY_ENTERED_DAY, dayKey)
            .apply()
    }

    fun getPendingPointName(dayKey: String): String? {
        val savedDay = prefs.getString(KEY_PENDING_DAY, null)
        if (savedDay != dayKey) return null
        return prefs.getString(KEY_PENDING_POINT_NAME, null)
    }

    fun clearPendingToday() {
        prefs.edit()
            .remove(KEY_PENDING_DAY)
            .remove(KEY_PENDING_POINT_NAME)
            .remove(KEY_LAST_LATE_REMINDER_AT)
            .apply()
    }

    fun getLastLateReminderAt(): Long {
        return prefs.getLong(KEY_LAST_LATE_REMINDER_AT, 0L)
    }

    fun setLastLateReminderAt(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_LATE_REMINDER_AT, timestamp).apply()
    }

    fun isEnteredToday(dayKey: String = JurmalaTime.todayKey()): Boolean {
        return prefs.getString(KEY_ENTERED_DAY, "") == dayKey
    }

    fun setEnteredToday(dayKey: String = JurmalaTime.todayKey()) {
        prefs.edit().putString(KEY_ENTERED_DAY, dayKey).apply()
    }

    fun clearEnteredToday() {
        prefs.edit().remove(KEY_ENTERED_DAY).apply()
    }

    fun resetToOutOfZone() {
        clearPaidToday()
        clearPendingToday()
        clearEnteredToday()
    }
}
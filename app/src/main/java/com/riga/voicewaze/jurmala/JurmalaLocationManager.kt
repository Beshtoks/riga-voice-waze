package com.riga.voicewaze.jurmala

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager

class JurmalaLocationManager(
    context: Context,
    private val store: JurmalaPointStore,
    private val onEnterZone: (JurmalaPoint) -> Unit
) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val insideStates = mutableMapOf<String, Boolean>()
    private var baselineInitialized = false
    private var started = false

    private val listener = LocationListener { location ->
        checkLocation(location)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        if (!store.isEnabled()) return
        if (store.isEnteredToday()) return

        baselineInitialized = false
        insideStates.clear()

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                15000L,
                25f,
                listener
            )
        }

        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                15000L,
                25f,
                listener
            )
        }

        started = true
    }

    fun stop() {
        if (!started) return
        locationManager.removeUpdates(listener)
        insideStates.clear()
        baselineInitialized = false
        started = false
    }

    private fun checkLocation(location: Location) {
        val points = store.loadPoints()
        if (points.isEmpty()) return

        val currentStates = mutableMapOf<String, Boolean>()
        val todayKey = JurmalaTime.todayKey()

        for (point in points) {
            val result = FloatArray(1)
            Location.distanceBetween(
                location.latitude,
                location.longitude,
                point.lat,
                point.lng,
                result
            )

            currentStates[point.name] = result[0] <= point.radius
        }

        // Первое полученное положение считаем базовым.
        // Иначе при включении контроля уже внутри радиуса будет ложный "въезд".
        if (!baselineInitialized) {
            insideStates.clear()
            insideStates.putAll(currentStates)
            baselineInitialized = true
            return
        }

        for (point in points) {
            val isInside = currentStates[point.name] == true
            val wasInside = insideStates[point.name] == true

            if (!wasInside && isInside) {
                store.setEnteredToday(todayKey)
                store.markZoneEnteredToday(todayKey, point.name)
                onEnterZone(point)
            }
        }

        insideStates.clear()
        insideStates.putAll(currentStates)
    }
}
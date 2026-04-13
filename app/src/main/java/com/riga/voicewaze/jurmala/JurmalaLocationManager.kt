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

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val insideStates = mutableMapOf<String, Boolean>()

    private val listener = LocationListener { location ->
        checkLocation(location)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!store.isEnabled()) return

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, listener)
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, listener)
        }
    }

    fun stop() {
        locationManager.removeUpdates(listener)
    }

    private fun checkLocation(location: Location) {
        val points = store.loadPoints()
        for (point in points) {
            val result = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, point.lat, point.lng, result)
            val isInside = result[0] <= point.radius
            val wasInside = insideStates[point.name] ?: false

            if (!wasInside && isInside) {
                onEnterZone(point)
            }

            insideStates[point.name] = isInside
        }
    }
}

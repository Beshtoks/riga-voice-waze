package com.riga.voicewaze.ui.map

import android.app.Activity
import android.content.Intent
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.riga.voicewaze.R
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.events.MapEventsReceiver
import java.io.IOException
import java.util.Locale

class MapPickerActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var marker: Marker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_map_picker)

        mapView = findViewById(R.id.mapView)
        mapView.setMultiTouchControls(true)

        val initialLat = intent.getDoubleExtra(EXTRA_INITIAL_LATITUDE, DEFAULT_LATITUDE)
        val initialLon = intent.getDoubleExtra(EXTRA_INITIAL_LONGITUDE, DEFAULT_LONGITUDE)
        val startPoint = GeoPoint(initialLat, initialLon)

        mapView.controller.setZoom(13.0)
        mapView.controller.setCenter(startPoint)
        placeMarker(startPoint)

        val overlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

            override fun longPressHelper(p: GeoPoint?): Boolean {
                val point = p ?: return false
                placeMarker(point)
                returnSelection(point)
                return true
            }
        })

        mapView.overlays.add(overlay)
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    private fun placeMarker(point: GeoPoint) {
        marker?.let { mapView.overlays.remove(it) }
        marker = Marker(mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(marker)
        mapView.invalidate()
    }

    private fun returnSelection(point: GeoPoint) {
        val result = Intent().apply {
            putExtra(EXTRA_LATITUDE, point.latitude)
            putExtra(EXTRA_LONGITUDE, point.longitude)
        }

        val resolved = reverseGeocode(point.latitude, point.longitude)
        if (resolved.first.isNotBlank()) {
            result.putExtra(EXTRA_DISPLAY_NAME, resolved.first)
        }
        if (resolved.second.isNotBlank()) {
            result.putExtra(EXTRA_ADDRESS, resolved.second)
        }

        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun reverseGeocode(latitude: Double, longitude: Double): Pair<String, String> {
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val holder = arrayOfNulls<android.location.Address>(1)
                geocoder.getFromLocation(latitude, longitude, 1) { list ->
                    holder[0] = list.firstOrNull()
                }
                var tries = 0
                while (holder[0] == null && tries < 20) {
                    Thread.sleep(50)
                    tries++
                }
                listOfNotNull(holder[0])
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(latitude, longitude, 1).orEmpty()
            }
            val address = addresses.firstOrNull()
            if (address == null) {
                "" to ""
            } else {
                val displayName = listOfNotNull(address.featureName, address.thoroughfare, address.locality)
                    .firstOrNull().orEmpty()
                val fullAddress = buildString {
                    for (i in 0..address.maxAddressLineIndex) {
                        val line = address.getAddressLine(i).orEmpty()
                        if (line.isNotBlank()) {
                            if (isNotEmpty()) append(", ")
                            append(line)
                        }
                    }
                }
                displayName to fullAddress
            }
        } catch (_: IOException) {
            "" to ""
        } catch (_: InterruptedException) {
            "" to ""
        }
    }

    companion object {
        const val EXTRA_INITIAL_LATITUDE = "extra_initial_latitude"
        const val EXTRA_INITIAL_LONGITUDE = "extra_initial_longitude"
        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_DISPLAY_NAME = "extra_display_name"
        const val EXTRA_ADDRESS = "extra_address"

        private const val DEFAULT_LATITUDE = 56.9496
        private const val DEFAULT_LONGITUDE = 24.1052
    }
}

package com.riga.voicewaze.ui.map

import android.app.Activity
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.riga.voicewaze.R
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread

class MapPickerActivity : AppCompatActivity(), OnMapReadyCallback {

    private var googleMap: GoogleMap? = null
    private var marker: Marker? = null

    private var initialLatitude: Double = DEFAULT_LATITUDE
    private var initialLongitude: Double = DEFAULT_LONGITUDE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_picker)

        initialLatitude = intent.getDoubleExtra(EXTRA_INITIAL_LATITUDE, DEFAULT_LATITUDE)
        initialLongitude = intent.getDoubleExtra(EXTRA_INITIAL_LONGITUDE, DEFAULT_LONGITUDE)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        val startPoint = LatLng(initialLatitude, initialLongitude)

        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false

        map.moveCamera(CameraUpdateFactory.newLatLngZoom(startPoint, 13f))
        placeMarker(startPoint)

        map.setOnMapLongClickListener { selectedPoint ->
            placeMarker(selectedPoint)
            returnSelection(selectedPoint)
        }
    }

    private fun placeMarker(point: LatLng) {
        val map = googleMap ?: return
        marker?.remove()
        marker = map.addMarker(
            MarkerOptions()
                .position(point)
                .title("Выбранная точка")
        )
    }

    private fun returnSelection(point: LatLng) {
        thread {
            val resolved = reverseGeocode(point.latitude, point.longitude)

            runOnUiThread {
                val result = Intent().apply {
                    putExtra(EXTRA_LATITUDE, point.latitude)
                    putExtra(EXTRA_LONGITUDE, point.longitude)

                    if (resolved.first.isNotBlank()) {
                        putExtra(EXTRA_DISPLAY_NAME, resolved.first)
                    }
                    if (resolved.second.isNotBlank()) {
                        putExtra(EXTRA_ADDRESS, resolved.second)
                    }
                }

                setResult(Activity.RESULT_OK, result)
                finish()
            }
        }
    }

    private fun reverseGeocode(latitude: Double, longitude: Double): Pair<String, String> {
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val lock = Object()
                var address: Address? = null
                var completed = false

                geocoder.getFromLocation(latitude, longitude, 1) { list ->
                    synchronized(lock) {
                        address = list.firstOrNull()
                        completed = true
                        lock.notifyAll()
                    }
                }

                synchronized(lock) {
                    var tries = 0
                    while (!completed && tries < 40) {
                        lock.wait(50)
                        tries++
                    }
                }

                addressToPair(address)
            } else {
                @Suppress("DEPRECATION")
                val address = geocoder.getFromLocation(latitude, longitude, 1).orEmpty().firstOrNull()
                addressToPair(address)
            }
        } catch (_: IOException) {
            "" to ""
        } catch (_: InterruptedException) {
            "" to ""
        } catch (_: Exception) {
            "" to ""
        }
    }

    private fun addressToPair(address: Address?): Pair<String, String> {
        if (address == null) return "" to ""

        val displayName = listOfNotNull(
            address.featureName?.takeIf { it.isNotBlank() },
            address.thoroughfare?.takeIf { it.isNotBlank() },
            address.locality?.takeIf { it.isNotBlank() }
        ).firstOrNull().orEmpty()

        val fullAddress = buildString {
            for (i in 0..address.maxAddressLineIndex) {
                val line = address.getAddressLine(i).orEmpty()
                if (line.isNotBlank()) {
                    if (isNotEmpty()) append(", ")
                    append(line)
                }
            }
        }

        return displayName to fullAddress
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
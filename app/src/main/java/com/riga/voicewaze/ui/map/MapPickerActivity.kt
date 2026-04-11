package com.riga.voicewaze.ui.map

import android.app.Activity
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.riga.voicewaze.R
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker as OsmMarker
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread

class MapPickerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var btnGoogle: Button
    private lateinit var btnOsm: Button
    private lateinit var osmMapView: MapView

    private var googleMap: GoogleMap? = null
    private var googleMarker: Marker? = null
    private var osmMarker: OsmMarker? = null

    private var initialLatitude: Double = DEFAULT_LATITUDE
    private var initialLongitude: Double = DEFAULT_LONGITUDE
    private var currentProvider: String = PROVIDER_GOOGLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_map_picker)

        initialLatitude = intent.getDoubleExtra(EXTRA_INITIAL_LATITUDE, DEFAULT_LATITUDE)
        initialLongitude = intent.getDoubleExtra(EXTRA_INITIAL_LONGITUDE, DEFAULT_LONGITUDE)
        currentProvider = intent.getStringExtra(EXTRA_MAP_PROVIDER) ?: PROVIDER_GOOGLE

        btnGoogle = findViewById(R.id.btnGoogleMap)
        btnOsm = findViewById(R.id.btnOsmMap)
        osmMapView = findViewById(R.id.osmMapView)

        btnGoogle.setOnClickListener { switchProvider(PROVIDER_GOOGLE) }
        btnOsm.setOnClickListener { switchProvider(PROVIDER_OSM) }

        setupGoogleMap()
        setupOsmMap()
        switchProvider(currentProvider)
    }

    private fun setupGoogleMap() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.googleMapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    private fun setupOsmMap() {
        osmMapView.setTileSource(TileSourceFactory.MAPNIK)
        osmMapView.setMultiTouchControls(true)

        val startPoint = GeoPoint(initialLatitude, initialLongitude)
        osmMapView.controller.setZoom(15.0)
        osmMapView.controller.setCenter(startPoint)
        placeOsmMarker(startPoint)

        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p == null) return false
                placeOsmMarker(p)
                returnSelection(p.latitude, p.longitude)
                return true
            }
        })

        osmMapView.overlays.add(eventsOverlay)
        osmMapView.invalidate()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        val startPoint = LatLng(initialLatitude, initialLongitude)
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false

        map.moveCamera(CameraUpdateFactory.newLatLngZoom(startPoint, 15f))
        placeGoogleMarker(startPoint)

        map.setOnMapLongClickListener { selectedPoint ->
            placeGoogleMarker(selectedPoint)
            returnSelection(selectedPoint.latitude, selectedPoint.longitude)
        }
    }

    private fun switchProvider(provider: String) {
        currentProvider = provider

        val googleContainer = findViewById<View>(R.id.googleMapContainer)
        val osmContainer = findViewById<View>(R.id.osmMapContainer)

        if (provider == PROVIDER_GOOGLE) {
            googleContainer.visibility = View.VISIBLE
            osmContainer.visibility = View.GONE
            btnGoogle.isEnabled = false
            btnOsm.isEnabled = true
        } else {
            googleContainer.visibility = View.GONE
            osmContainer.visibility = View.VISIBLE
            btnGoogle.isEnabled = true
            btnOsm.isEnabled = false
        }
    }

    private fun placeGoogleMarker(point: LatLng) {
        val map = googleMap ?: return
        googleMarker?.remove()
        googleMarker = map.addMarker(
            MarkerOptions()
                .position(point)
                .title("Выбранная точка")
        )
    }

    private fun placeOsmMarker(point: GeoPoint) {
        val marker = osmMarker ?: OsmMarker(osmMapView).also {
            osmMapView.overlays.add(it)
            osmMarker = it
        }

        marker.position = point
        marker.setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)
        marker.title = "Выбранная точка"
        osmMapView.invalidate()
    }

    private fun returnSelection(latitude: Double, longitude: Double) {
        thread {
            val resolved = reverseGeocode(latitude, longitude)

            runOnUiThread {
                val result = Intent().apply {
                    putExtra(EXTRA_LATITUDE, latitude)
                    putExtra(EXTRA_LONGITUDE, longitude)
                    if (resolved.first.isNotBlank()) putExtra(EXTRA_DISPLAY_NAME, resolved.first)
                    if (resolved.second.isNotBlank()) putExtra(EXTRA_ADDRESS, resolved.second)
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

    override fun onResume() {
        super.onResume()
        osmMapView.onResume()
    }

    override fun onPause() {
        osmMapView.onPause()
        super.onPause()
    }

    companion object {
        const val EXTRA_INITIAL_LATITUDE = "extra_initial_latitude"
        const val EXTRA_INITIAL_LONGITUDE = "extra_initial_longitude"
        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_DISPLAY_NAME = "extra_display_name"
        const val EXTRA_ADDRESS = "extra_address"
        const val EXTRA_MAP_PROVIDER = "extra_map_provider"

        const val PROVIDER_GOOGLE = "GOOGLE"
        const val PROVIDER_OSM = "OSM"

        private const val DEFAULT_LATITUDE = 56.9496
        private const val DEFAULT_LONGITUDE = 24.1052
    }
}

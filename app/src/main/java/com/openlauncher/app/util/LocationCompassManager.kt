package com.openlauncher.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.GpsStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val speedMps: Float = 0f,
    val bearing: Float? = 0f
)

class LocationCompassManager(context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager   = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val _location   = MutableStateFlow<LocationData?>(null)
    private val _bearing    = MutableStateFlow(0f)
    private val _satellites = MutableStateFlow(0)

    val location: StateFlow<LocationData?> = _location
    val bearing: StateFlow<Float> = _bearing
    val satellites: StateFlow<Int> = _satellites

    private val gravity      = FloatArray(3)
    private val geomagnetic  = FloatArray(3)
    private var bearingSin   = 0f
    private var bearingCos   = 1f
    private var lastLocationForBearing: Location? = null

    private var gnssCallback: Any? = null
    @Suppress("DEPRECATION")
    private var gpsStatusListener: GpsStatus.Listener? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, gravity, 0, 3)
                Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, geomagnetic, 0, 3)
            }
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                val azimuthRad = orientation[0].toDouble()
                val alpha = 0.10f
                bearingSin = alpha * sin(azimuthRad).toFloat() + (1f - alpha) * bearingSin
                bearingCos = alpha * cos(azimuthRad).toFloat() + (1f - alpha) * bearingCos
                _bearing.value = ((Math.toDegrees(atan2(bearingSin.toDouble(), bearingCos.toDouble())) + 360) % 360).toFloat()
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            _location.value = LocationData(
                latitude  = loc.latitude,
                longitude = loc.longitude,
                altitude  = loc.altitude,
                accuracy  = loc.accuracy,
                speedMps  = if (loc.hasSpeed()) loc.speed else 0f
            )

            if (loc.hasBearing() && loc.bearing != 0f) {
                _bearing.value = loc.bearing
            } else {
                val lastLoc = lastLocationForBearing
                if (lastLoc != null) {
                    val distance = lastLoc.distanceTo(loc)
                    if (distance > 3f) {
                        val computedBearing = lastLoc.bearingTo(loc)
                        _bearing.value = (computedBearing + 360f) % 360f
                        lastLocationForBearing = loc
                    }
                } else {
                    lastLocationForBearing = loc
                }
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }

        try {
            if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 3000L, 5f, locationListener
                )
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    locationListener.onLocationChanged(it)
                }
            }
        } catch (_: Exception) {}

        try {
            if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 5000L, 10f, locationListener
                )
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let {
                    locationListener.onLocationChanged(it)
                }
            }
        } catch (_: Exception) {}

        // GNSS / GPS Satellite Listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val callback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    var used = 0
                    val total = status.satelliteCount
                    for (i in 0 until total) {
                        if (status.usedInFix(i)) used++
                    }
                    _satellites.value = if (used > 0) used else total
                }
            }
            gnssCallback = callback
            try {
                locationManager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
            } catch (_: Exception) {}
        } else {
            @Suppress("DEPRECATION")
            val listener = GpsStatus.Listener {
                try {
                    @Suppress("DEPRECATION")
                    val status = locationManager.getGpsStatus(null)
                    if (status != null) {
                        var used = 0
                        var total = 0
                        for (sat in status.satellites) {
                            total++
                            if (sat.usedInFix()) used++
                        }
                        _satellites.value = if (used > 0) used else total
                    }
                } catch (_: Exception) {}
            }
            gpsStatusListener = listener
            try {
                @Suppress("DEPRECATION")
                locationManager.addGpsStatusListener(listener)
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        sensorManager.unregisterListener(sensorListener)
        locationManager.removeUpdates(locationListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (gnssCallback as? GnssStatus.Callback)?.let {
                locationManager.unregisterGnssStatusCallback(it)
            }
            gnssCallback = null
        } else {
            gpsStatusListener?.let {
                @Suppress("DEPRECATION")
                locationManager.removeGpsStatusListener(it)
            }
            gpsStatusListener = null
        }
        lastLocationForBearing = null
    }
}

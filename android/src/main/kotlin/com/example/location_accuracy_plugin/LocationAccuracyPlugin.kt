package com.example.location_accuracy_plugin

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlin.math.*

class LocationAccuracyPlugin :
  FlutterPlugin,
  MethodChannel.MethodCallHandler,
  ActivityAware {

  private val TAG = "LocationAccuracy"

  private lateinit var context: Context
  private var activity: Activity? = null

  private lateinit var methodChannel: MethodChannel
  private lateinit var gpsChannel: EventChannel
  private lateinit var imuChannel: EventChannel

  // GPS
  private var fusedClient: FusedLocationProviderClient? = null
  private var gpsCallback: LocationCallback? = null
  private var gpsSink: EventChannel.EventSink? = null
  private var locationRequest: LocationRequest? = null

  // Request config
  private var gpsIntervalMs: Int = 1000
  private var fastestIntervalMs: Int = 250
  private var currentPriority: Int = Priority.PRIORITY_HIGH_ACCURACY

  // Dynamic priority control (hysteresis)
  private var promoteHighAboveM: Double = 15.0
  private var demoteBalancedBelowM: Double = 12.0
  private var lastPrioritySwitchMs: Long = 0L
  private var minSwitchIntervalMs: Long = 5_000L

  // Accuracy gating and smoothing
  private var targetAccuracyM: Double = 10.0
  private var discardAccuracyAboveM: Double = 30.0
  private var settleSamples: Int = 3
  private var goodFixCount: Int = 0

  // Location smoothing and deadband filtering variables
  private var smoothLat: Double? = null
  private var smoothLon: Double? = null
  private var lastEmittedLat: Double? = null
  private var lastEmittedLon: Double? = null
  private var deadbandMeters: Double = 1.5 // Minimum movement to emit update

  // Last-good holding
  private var lastGoodLat: Double? = null
  private var lastGoodLon: Double? = null
  private var lastGoodTs: Long = 0L
  private var lastGoodAccM: Double = Double.MAX_VALUE
  private var goodHoldTimeoutMs: Long = 10_000 // ms to hold last good fix visually

  // IMU
  private var sensorManager: SensorManager? = null
  private var imuSink: EventChannel.EventSink? = null
  private var sensorListener: SensorEventListener? = null
  private var imuHz: Int = 50

  // Observation window for HMM-like weighted average smoothing
  private val stateCountToKeep = 10
  private val observations = mutableListOf<Location>()

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    context = binding.applicationContext
    Log.i(TAG, "onAttachedToEngine")

    methodChannel = MethodChannel(binding.binaryMessenger, "sensor_fused_location/methods")
    methodChannel.setMethodCallHandler(this)

    gpsChannel = EventChannel(binding.binaryMessenger, "sensor_fused_location/gps")
    imuChannel = EventChannel(binding.binaryMessenger, "sensor_fused_location/imu")

    gpsChannel.setStreamHandler(object : EventChannel.StreamHandler {
      override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        gpsSink = events
        Log.i(TAG, "GPS onListen -> starting location if possible")
        startLocationIfPossible()
      }
      override fun onCancel(arguments: Any?) {
        Log.i(TAG, "GPS onCancel -> stopping location")
        gpsSink = null
        stopLocation()
      }
    })

    imuChannel.setStreamHandler(object : EventChannel.StreamHandler {
      override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        imuSink = events
        Log.i(TAG, "IMU onListen -> starting IMU")
        startImu()
      }
      override fun onCancel(arguments: Any?) {
        Log.i(TAG, "IMU onCancel -> stopping IMU")
        imuSink = null
        stopImu()
      }
    })

    fusedClient = LocationServices.getFusedLocationProviderClient(context)
    sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    when (call.method) {
      "initialize" -> {
        val highAccuracyDefault = call.argument<Boolean>("highAccuracy") ?: true
        currentPriority = if (highAccuracyDefault) Priority.PRIORITY_HIGH_ACCURACY
        else Priority.PRIORITY_BALANCED_POWER_ACCURACY

        gpsIntervalMs = call.argument<Int>("gpsIntervalMs") ?: 1000
        imuHz = call.argument<Int>("imuHz") ?: 50
        fastestIntervalMs = max(250, call.argument<Int>("fastestIntervalMs") ?: (gpsIntervalMs / 2))

        targetAccuracyM = (call.argument<Double>("targetAccuracyM") ?: 10.0).coerceAtLeast(5.0)
        discardAccuracyAboveM = call.argument<Double>("discardAccuracyAboveM") ?: 30.0
        settleSamples = call.argument<Int>("settleSamples") ?: 3
        deadbandMeters = call.argument<Double>("deadbandMeters") ?: 1.5
        goodHoldTimeoutMs = (call.argument<Int>("goodHoldTimeoutMs") ?: 10_000).toLong()

        promoteHighAboveM = call.argument<Double>("promoteHighAboveM") ?: 15.0
        demoteBalancedBelowM = call.argument<Double>("demoteBalancedBelowM") ?: 12.0
        minSwitchIntervalMs = (call.argument<Int>("minSwitchIntervalMs") ?: 5_000).toLong()

        Log.i(TAG, "initialize: priority=${priorityName(currentPriority)}, " +
                "gpsIntervalMs=$gpsIntervalMs, fastestIntervalMs=$fastestIntervalMs, imuHz=$imuHz, " +
                "targetAcc=$targetAccuracyM, discard>$discardAccuracyAboveM, settle=$settleSamples, " +
                "deadband=${"%.2f".format(deadbandMeters)}m, goodHold=${goodHoldTimeoutMs}ms, " +
                "promote>${promoteHighAboveM}m, demote<=${demoteBalancedBelowM}m")

        buildLocationRequest(currentPriority)
        result.success(null)
      }
      "requestPermissions" -> {
        Log.i(TAG, "requestPermissions()")
        requestPermissions()
        result.success(null)
      }
      else -> result.notImplemented()
    }
  }

  private fun buildLocationRequest(priority: Int) {
    locationRequest = LocationRequest.Builder(priority, gpsIntervalMs.toLong())
      .setMinUpdateIntervalMillis(fastestIntervalMs.toLong())
      .setWaitForAccurateLocation(priority == Priority.PRIORITY_HIGH_ACCURACY)
      .setMaxUpdateDelayMillis(gpsIntervalMs.toLong())
      .build()
    Log.i(TAG, "buildLocationRequest: priority=${priorityName(priority)}, " +
            "interval=${gpsIntervalMs}ms fastest=${fastestIntervalMs}ms waitAcc=${priority==Priority.PRIORITY_HIGH_ACCURACY}")
  }

  private fun maybeSwitchPriority(latestAccM: Double) {
    val now = System.currentTimeMillis()
    if (now - lastPrioritySwitchMs < minSwitchIntervalMs) return

    val wantHigh = latestAccM > promoteHighAboveM
    val wantBalanced = latestAccM <= demoteBalancedBelowM

    if (wantHigh && currentPriority != Priority.PRIORITY_HIGH_ACCURACY) {
      Log.i(TAG, "Switching priority to HIGH (acc=${"%.1f".format(latestAccM)}m)")
      currentPriority = Priority.PRIORITY_HIGH_ACCURACY
      lastPrioritySwitchMs = now
      restartLocationUpdates()
    } else if (wantBalanced && currentPriority != Priority.PRIORITY_BALANCED_POWER_ACCURACY) {
      Log.i(TAG, "Switching priority to BALANCED (acc=${"%.1f".format(latestAccM)}m)")
      currentPriority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
      lastPrioritySwitchMs = now
      restartLocationUpdates()
    }
  }

  private fun restartLocationUpdates() {
    Log.i(TAG, "restartLocationUpdates() with ${priorityName(currentPriority)}")
    buildLocationRequest(currentPriority)
    if (gpsSink != null && hasLocationPermissions() && locationRequest != null) {
      gpsCallback?.let { fusedClient?.removeLocationUpdates(it) }
      if (gpsCallback == null) createCallback()
      fusedClient?.requestLocationUpdates(locationRequest!!, gpsCallback!!, Looper.getMainLooper())
    }
  }

  private fun createCallback() {
    gpsCallback = object : LocationCallback() {
      override fun onLocationResult(locationResult: LocationResult) {
        val loc = locationResult.lastLocation ?: return
        val acc = loc.accuracy.toDouble()

        maybeSwitchPriority(acc)

        if (acc.isNaN() || acc <= 0.0 || acc > discardAccuracyAboveM) {
          Log.d(TAG, "Discard fix: acc=${"%.1f".format(acc)}m > ${discardAccuracyAboveM}m")
          return
        }

        if (acc <= targetAccuracyM) {
          goodFixCount++
          lastGoodLat = loc.latitude
          lastGoodLon = loc.longitude
          lastGoodTs = loc.time
          lastGoodAccM = acc
        } else {
          goodFixCount = 0
        }
        val isGood = acc <= targetAccuracyM && goodFixCount >= settleSamples

        // Accuracy-weighted smoothing
        if (smoothLat == null) {
          smoothLat = loc.latitude
          smoothLon = loc.longitude
        } else {
          val w = when {
            acc <= 5.0 -> 0.6
            acc <= 10.0 -> 0.4
            acc <= 20.0 -> 0.25
            else -> 0.15
          }
          smoothLat = (1 - w) * smoothLat!! + w * loc.latitude
          smoothLon = (1 - w) * smoothLon!! + w * loc.longitude
        }

        // Add current location to observations for filtering
        observations.add(Location(loc))
        if (observations.size > stateCountToKeep) observations.removeAt(0)

        // Calculate weighted average location as a simple HMM-like filter
        val filteredLocation = simpleHMMFilter(observations)

        val now = loc.time
        val canHoldGood = lastGoodLat != null && (now - lastGoodTs) <= goodHoldTimeoutMs
        val outLatLon = if (!isGood && canHoldGood) {
          Pair(lastGoodLat!!, lastGoodLon!!)
        } else {
          filteredLocation
        }

        // Deadband filter: suppress small location changes
        val finalLatLon = if (lastEmittedLat != null && lastEmittedLon != null) {
          val d = distanceMeters(lastEmittedLat!!, lastEmittedLon!!, outLatLon.first, outLatLon.second)
          if (d < deadbandMeters) {
            Pair(lastEmittedLat!!, lastEmittedLon!!)
          } else {
            outLatLon
          }
        } else outLatLon

        lastEmittedLat = finalLatLon.first
        lastEmittedLon = finalLatLon.second

        Log.d(TAG,
          "Fix: raw=(${loc.latitude.format6()}, ${loc.longitude.format6()}) " +
                  "acc=${acc.format1()}m spd=${loc.speed.format2()}m/s hdg=${loc.bearing.format1()}° " +
                  "smooth=(${smoothLat!!.format6()}, ${smoothLon!!.format6()}) " +
                  "filtered=(${filteredLocation.first.format6()}, ${filteredLocation.second.format6()}) " +
                  "emit=(${finalLatLon.first.format6()}, ${finalLatLon.second.format6()}) " +
                  "isGood=$isGood usingLastGood=${(!isGood && canHoldGood)} priority=${priorityName(currentPriority)}"
        )

        val map = hashMapOf<String, Any>(
          "ts" to loc.time,
          "lat" to finalLatLon.first,
          "lon" to finalLatLon.second,
          "acc" to acc,
          "spd" to loc.speed.toDouble(),
          "hdg" to loc.bearing.toDouble(),
          "dr" to false,
          "isGood" to isGood,
          "isUsable" to true,
          "usingLastGood" to (!isGood && canHoldGood),
          "priority" to priorityName(currentPriority)
        )
        gpsSink?.success(map)
      }
    }
  }

  // Simple weighted average filter simulating HMM smoothing
  private fun simpleHMMFilter(observations: List<Location>): Pair<Double, Double> {
    if (observations.isEmpty()) return Pair(smoothLat ?: 0.0, smoothLon ?: 0.0)

    var totalWeight = 0.0
    var weightedLatSum = 0.0
    var weightedLonSum = 0.0
    for (loc in observations) {
      val weight = if (loc.accuracy > 0) 1.0 / loc.accuracy else 1.0
      weightedLatSum += loc.latitude * weight
      weightedLonSum += loc.longitude * weight
      totalWeight += weight
    }

    val avgLat = weightedLatSum / totalWeight
    val avgLon = weightedLonSum / totalWeight

    Log.d(TAG, "HMM filter: weighted average location = ($avgLat, $avgLon) based on ${observations.size} points")
    return Pair(avgLat, avgLon)
  }

  private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6378137.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat/2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon/2).pow(2.0)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c
  }

  private fun startLocationIfPossible() {
    if (gpsSink == null) return
    if (!hasLocationPermissions()) {
      Log.w(TAG, "No location permission; cannot start updates")
      return
    }

    if (locationRequest == null) {
      buildLocationRequest(currentPriority)
    }
    if (gpsCallback == null) {
      createCallback()
    }
    Log.i(TAG, "requestLocationUpdates start (priority=${priorityName(currentPriority)})")
    fusedClient?.requestLocationUpdates(locationRequest!!, gpsCallback!!, Looper.getMainLooper())
  }

  private fun stopLocation() {
    gpsCallback?.let { fusedClient?.removeLocationUpdates(it) }
    gpsCallback = null
    goodFixCount = 0
    smoothLat = null
    smoothLon = null
    lastEmittedLat = null
    lastEmittedLon = null
    observations.clear()
    Log.i(TAG, "Stopped location updates and cleared observations")
  }

  private fun startImu() {
    if (imuSink == null) return

    val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
      ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return

    if (sensorListener == null) {
      sensorListener = object : SensorEventListener {
        var lastAx = 0f; var lastAy = 0f; var lastAz = 0f
        var lastGx = 0f; var lastGy = 0f; var lastGz = 0f

        override fun onSensorChanged(event: SensorEvent) {
          val ts = System.currentTimeMillis()
          when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
              lastAx = event.values[0]
              lastAy = event.values[1]
              lastAz = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
              lastGx = event.values[0]
              lastGy = event.values[1]
              lastGz = event.values[2]
            }
          }

          val map = hashMapOf<String, Any>(
            "ts" to ts,
            "ax" to lastAx,
            "ay" to lastAy,
            "az" to lastAz,
            "gx" to lastGx,
            "gy" to lastGy,
            "gz" to lastGz
          )
          imuSink?.success(map)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
      }
    }

    val rateUs = (1_000_000 / imuHz).coerceAtLeast(5_000)
    Log.i(TAG, "Register IMU listeners at ~${imuHz}Hz (rateUs=$rateUs)")
    accel?.let { sensorManager?.registerListener(sensorListener, it, rateUs) }
    gyro.let { sensorManager?.registerListener(sensorListener, it, rateUs) }
  }

  private fun stopImu() {
    sensorListener?.let { sensorManager?.unregisterListener(it) }
    sensorListener = null
    Log.i(TAG, "Unregistered IMU listeners")
  }

  private fun requestPermissions() {
    activity?.let { act ->
      val perms = mutableListOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
      )
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
      }
      Log.i(TAG, "Requesting permissions: $perms")
      ActivityCompat.requestPermissions(act, perms.toTypedArray(), 1001)
    } ?: run {
      Log.w(TAG, "requestPermissions: no activity attached")
    }
  }

  private fun hasLocationPermissions(): Boolean {
    val fine = ActivityCompat.checkSelfPermission(
      context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ActivityCompat.checkSelfPermission(
      context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    Log.d(TAG, "hasLocationPermissions fine=$fine coarse=$coarse")
    return fine || coarse
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
    Log.i(TAG, "onAttachedToActivity: ${activity?.localClassName}")
  }
  override fun onDetachedFromActivityForConfigChanges() {
    Log.i(TAG, "onDetachedFromActivityForConfigChanges")
    activity = null
  }
  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    activity = binding.activity
    Log.i(TAG, "onReattachedToActivityForConfigChanges: ${activity?.localClassName}")
  }
  override fun onDetachedFromActivity() {
    Log.i(TAG, "onDetachedFromActivity")
    activity = null
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    Log.i(TAG, "onDetachedFromEngine")
    methodChannel.setMethodCallHandler(null)
    stopLocation()
    stopImu()
    gpsSink = null
    imuSink = null
  }

  // Helpers for formatting
  private fun Double.format1() = String.format("%.1f", this)
  private fun Float.format2() = String.format("%.2f", this)
  private fun Float.format1() = String.format("%.1f", this)
  private fun Double.format6() = String.format("%.6f", this)
  private fun Float.format3() = String.format("%.3f", this)

  private fun priorityName(p: Int) = when (p) {
    Priority.PRIORITY_HIGH_ACCURACY -> "HIGH"
    Priority.PRIORITY_BALANCED_POWER_ACCURACY -> "BALANCED"
    Priority.PRIORITY_LOW_POWER -> "LOW"
    Priority.PRIORITY_PASSIVE -> "PASSIVE"
    else -> "UNKNOWN"
  }
}


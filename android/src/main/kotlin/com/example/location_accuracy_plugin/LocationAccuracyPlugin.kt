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
import com.google.android.gms.location.*
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

    // Kalman Filter
    private val kalmanFilter = KalmanFilter2D()
    private var kalmanInitialized = false
    // Snap-to-Roads functionality
    private val mapMatcher = MapMatcher()
    private var enableSnapToRoads: Boolean = false
    private var snapToRoadConfidenceThreshold: Double = 0.3
    private var maxSnapDistanceMeters: Double = 50.0

    // Road matching buffer for sequence processing
    private val gpsBuffer = mutableListOf<GPSPoint>()
    private val maxBufferSize = 10

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

                Log.i(
                    TAG, "initialize: priority=${priorityName(currentPriority)}, " +
                            "gpsIntervalMs=$gpsIntervalMs, fastestIntervalMs=$fastestIntervalMs, imuHz=$imuHz, " +
                            "targetAcc=$targetAccuracyM, discard>$discardAccuracyAboveM, settle=$settleSamples, " +
                            "deadband=${"%.2f".format(deadbandMeters)}m, goodHold=${goodHoldTimeoutMs}ms, " +
                            "promote>${promoteHighAboveM}m, demote<=${demoteBalancedBelowM}m"
                )

                buildLocationRequest(currentPriority)

                // Snap-to-roads configuration
                enableSnapToRoads = call.argument<Boolean>("enableSnapToRoads") ?: false
                snapToRoadConfidenceThreshold = call.argument<Double>("snapConfidenceThreshold") ?: 0.3
                maxSnapDistanceMeters = call.argument<Double>("maxSnapDistance") ?: 50.0

                Log.i(TAG, "Snap-to-roads: enabled=$enableSnapToRoads, " +
                        "confidence_threshold=$snapToRoadConfidenceThreshold, " +
                        "max_distance=${maxSnapDistanceMeters}m")


                result.success(null)
            }

            "requestPermissions" -> {
                Log.i(TAG, "requestPermissions()")
                requestPermissions()
                result.success(null)
            }

            "getHMMAccuracy" -> {
                val filteredLocation = simpleHMMFilter(observations)
                val hmmAccuracy = computeHMMAccuracy(observations, filteredLocation)
                result.success(hmmAccuracy)
            }

            "loadRoadData" -> {
                try {
                    Log.i(TAG, "Loading road data...")
                    val roadDataList = call.argument<List<Map<String, Any>>>("roads") ?: emptyList()
                    Log.i(TAG, "Received ${roadDataList.size} road segments from Flutter")

                    val roadSegments = roadDataList.map { roadData ->
                        val coordinatesData = roadData["coordinates"] as List<Map<String, Double>>
                        val coordinates = coordinatesData.map { coord ->
                            LatLng(coord["latitude"]!!, coord["longitude"]!!)
                        }

                        val roadSegment = RoadSegment(
                            id = (roadData["id"] as Number).toLong(),
                            coordinates = coordinates,
                            roadType = roadData["roadType"] as String? ?: "unknown",
                            maxSpeed = (roadData["maxSpeed"] as Number?)?.toInt() ?: 50,
                            isOneWay = roadData["isOneWay"] as Boolean? ?: false
                        )

                        Log.d(TAG, "Created road segment: ID=${roadSegment.id}, coords=${coordinates.size}, type=${roadSegment.roadType}")
                        coordinates.forEachIndexed { index, coord ->
                            Log.v(TAG, "  Point $index: (${coord.latitude}, ${coord.longitude})")
                        }

                        roadSegment
                    }

                    // Clear existing roads first
                    mapMatcher.clearAllRoads()

                    // Load new roads
                    mapMatcher.loadRoadSegments(roadSegments)

                    Log.i(TAG, "Successfully loaded ${roadSegments.size} road segments")
                    result.success(mapOf("loaded" to roadSegments.size))

                } catch (e: Exception) {
                    Log.e(TAG, "Error loading road data", e)
                    result.error("LOAD_ERROR", "Failed to load road  ${e.message}", e.stackTrace)
                }
            }


            "clearRoadData" -> {
                // Clear road data if needed
                Log.i(TAG, "Road data cleared")
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
        Log.i(
            TAG, "buildLocationRequest: priority=${priorityName(priority)}, " +
                    "interval=${gpsIntervalMs}ms fastest=${fastestIntervalMs}ms waitAcc=${priority == Priority.PRIORITY_HIGH_ACCURACY}"
        )
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
                val timestamp = loc.time


                Log.d(TAG, "=== LOCATION UPDATE DEBUG ===")
                Log.d(TAG, "Raw GPS: lat=${loc.latitude}, lon=${loc.longitude}, acc=${acc}m")
                Log.d(TAG, "Enable snap-to-roads: $enableSnapToRoads")
//                Log.d(TAG, "Road segments loaded: ${roadSegments.size}")

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

                // Create GPS point for snap-to-roads
                val gpsPoint = GPSPoint(loc.latitude, loc.longitude, timestamp, loc.accuracy)

                // Always find nearest road (regardless of snap-to-roads enabled state)
                val nearestRoadResult = mapMatcher.findNearestRoad(gpsPoint)


                // Add to buffer for sequence processing
                gpsBuffer.add(gpsPoint)
                if (gpsBuffer.size > maxBufferSize) {
                    gpsBuffer.removeAt(0)
                }

                // Snap-to-roads processing
                var snapResult: SnapResult? = null
                var snappedLat = loc.latitude
                var snappedLon = loc.longitude
                var snapConfidence = 0.0
                var snapDistance = 0.0
                var snapRoadId: Long? = null
                var snapRoadType = ""

                if (enableSnapToRoads) {
                    snapResult = mapMatcher.snapToRoad(gpsPoint)

                    if (snapResult != null &&
                        snapResult.confidence >= snapToRoadConfidenceThreshold &&
                        snapResult.distance <= maxSnapDistanceMeters) {

                        snappedLat = snapResult.snappedPoint.latitude
                        snappedLon = snapResult.snappedPoint.longitude
                        snapConfidence = snapResult.confidence
                        snapDistance = snapResult.distance
                        snapRoadId = snapResult.roadSegment.id
                        snapRoadType = snapResult.roadSegment.roadType

                        Log.d(TAG, "Applied snap-to-road: road=${snapRoadId}, " +
                                "distance=${String.format("%.2f", snapDistance)}m, " +
                                "confidence=${String.format("%.3f", snapConfidence)}")
                    } else {
                        Log.d(TAG, "Snap-to-road not applied: " +
                                if (snapResult == null) "no nearby roads"
                                else "confidence=${String.format("%.3f", snapResult.confidence)} < threshold=$snapToRoadConfidenceThreshold")
                    }
                }


                if (acc <= targetAccuracyM) {
                    goodFixCount++
                    lastGoodLat = snappedLat  // Use snapped coordinates for "good" location
                    lastGoodLon = snappedLon
                    lastGoodTs = loc.time
                    lastGoodAccM = acc
                } else {
                    goodFixCount = 0
                }


                val isGood = acc <= targetAccuracyM && goodFixCount >= settleSamples
                val now = loc.time
                val canHoldGood = lastGoodLat != null && (now - lastGoodTs) <= goodHoldTimeoutMs

                // Add current location to observations for HMM-like filtering
                // Use snapped location for filtering if available
                val locationForFiltering = Location("snapped").apply {
                    latitude = snappedLat
                    longitude = snappedLon
                    accuracy = loc.accuracy
                    time = loc.time
                    speed = loc.speed
                    bearing = loc.bearing
                }

                // Add current location to observations for HMM-like filtering
                observations.add(loc)
                if (observations.size > stateCountToKeep) observations.removeAt(0)

                // Calculate weighted average location as a simple HMM-like filter
                val hmmFilteredLocation = simpleHMMFilter(observations)
                val hmmAccuracy = computeHMMAccuracy(observations, hmmFilteredLocation)

                // Initialize or update Kalman Filter
                if (!kalmanInitialized) {
                    kalmanFilter.initialize(loc.latitude, loc.longitude, acc, timestamp)
                    kalmanInitialized = true
                }
                val (kalmanLat, kalmanLon) = kalmanFilter.update(loc.latitude, loc.longitude, acc, timestamp)
                val kalmanImprovementM = distanceMeters(loc.latitude, loc.longitude, kalmanLat, kalmanLon)

                // Determine the location to emit, using Kalman output as the primary source
                val baseOutputLatLon = if (!isGood && canHoldGood) {
                    Pair(lastGoodLat!!, lastGoodLon!!)
                } else {
                    Pair(kalmanLat, kalmanLon)
                }

                // Apply deadband filter to the chosen output location
                val finalLatLon = if (lastEmittedLat != null && lastEmittedLon != null) {
                    val d = distanceMeters(lastEmittedLat!!, lastEmittedLon!!, baseOutputLatLon.first, baseOutputLatLon.second)
                    if (d < deadbandMeters) {
                        Pair(lastEmittedLat!!, lastEmittedLon!!) // Suppress small movement
                    } else {
                        baseOutputLatLon // Emit new position
                    }
                } else {
                    baseOutputLatLon
                }

                lastEmittedLat = finalLatLon.first
                lastEmittedLon = finalLatLon.second

                // Enhanced logging with snap-to-roads info
                Log.d(TAG, """
                Fix Update | Priority: ${priorityName(currentPriority)} | Accuracy: ${acc.format1()}m
                - Raw GPS:        (${loc.latitude.format6()}, ${loc.longitude.format6()})
                - Snap-to-Road:   ${if (snapResult != null)
                    "(${snappedLat.format6()}, ${snappedLon.format6()}) | Road: $snapRoadId ($snapRoadType) | Conf: ${snapConfidence.format3()} | Dist: ${snapDistance.format2()}m"
                else "Not applied"}
                - HMM Filtered:   (${hmmFilteredLocation.first.format6()}, ${hmmFilteredLocation.second.format6()}) | HMM Acc: ${hmmAccuracy.format1()}m
                - Kalman Filter:  (${kalmanLat.format6()}, ${kalmanLon.format6()}) | Improvement: ${kalmanImprovementM.format2()}m
                - Final Emitted:  (${finalLatLon.first.format6()}, ${finalLatLon.second.format6()}) | Good: $isGood, Held: ${!isGood && canHoldGood}
            """.trimIndent())

                // Send the comprehensive data map to Flutter
                // Send the comprehensive data map to Flutter including snap-to-roads data
                val map = hashMapOf<String, Any>(
                    "ts" to loc.time,
                    "lat" to loc.latitude,  // Raw latitude
                    "lon" to loc.longitude, // Raw longitude
                    "acc" to acc,
                    "spd" to loc.speed.toDouble(),
                    "hdg" to loc.bearing.toDouble(),
                    "isGood" to isGood,
                    "priority" to priorityName(currentPriority),

                    // HMM Data
                    "hmmLat" to hmmFilteredLocation.first,
                    "hmmLon" to hmmFilteredLocation.second,
                    "hmmAcc" to hmmAccuracy,

                    // Kalman Data
                    "kalmanLat" to kalmanLat,
                    "kalmanLon" to kalmanLon,

                    // Final, dead-banded output
                    "finalLat" to finalLatLon.first,
                    "finalLon" to finalLatLon.second,

                    // Snap-to-Roads Data
                    "snapEnabled" to enableSnapToRoads,
                    "snapLat" to snappedLat,
                    "snapLon" to snappedLon,
                    "snapConfidence" to snapConfidence,
                    "snapDistance" to snapDistance,
                    "snapRoadId" to (snapRoadId ?: -1),
                    "snapRoadType" to snapRoadType,
                    "snapApplied" to (snapResult != null && snapConfidence >= snapToRoadConfidenceThreshold),

                    // Nearest road information
                    "nearestRoadId" to (nearestRoadResult?.roadSegment?.id ?: -1),
                    "nearestRoadName" to (nearestRoadResult?.roadSegment?.getDisplayName() ?: ""),
                    "nearestRoadType" to (nearestRoadResult?.roadSegment?.roadType ?: ""),
                    "nearestRoadDistance" to (nearestRoadResult?.distance ?: 0.0),
                    "nearestRoadFullAddress" to (nearestRoadResult?.roadSegment?.getFullAddress() ?: "")
                )

                Log.d(TAG, "Nearest road: ${nearestRoadResult?.roadSegment?.getDisplayName() ?: "None"} " +
                        "at ${String.format("%.2f", nearestRoadResult?.distance ?: 0.0)}m")

                gpsSink?.success(map)
            }
        }
    }


    // Additional formatting helpers
    private fun Double.format3() = String.format("%.3f", this)

    // Helper to load sample road data for testing
    private fun loadSampleRoadData() {
        val sampleRoads = listOf(
            RoadSegment(
                id = 1001,
                coordinates = listOf(
                    LatLng(37.7749, -122.4194),  // San Francisco sample
                    LatLng(37.7849, -122.4094)
                ),
                roadType = "primary",
                maxSpeed = 50
            ),
            RoadSegment(
                id = 1002,
                coordinates = listOf(
                    LatLng(37.7849, -122.4094),
                    LatLng(37.7949, -122.3994)
                ),
                roadType = "secondary",
                maxSpeed = 40
            )
        )

        mapMatcher.loadRoadSegments(sampleRoads)
        Log.i(TAG, "Loaded sample road data for testing")
    }
    // Simple accuracy-weighted average filter
    private fun simpleHMMFilter(observations: List<Location>): Pair<Double, Double> {
        if (observations.isEmpty()) return Pair(0.0, 0.0)

        var totalWeight = 0.0
        var weightedLatSum = 0.0
        var weightedLonSum = 0.0
        for (loc in observations) {
            val weight = if (loc.accuracy > 0) 1.0 / loc.accuracy else 1.0
            weightedLatSum += loc.latitude * weight
            weightedLonSum += loc.longitude * weight
            totalWeight += weight
        }

        return if (totalWeight > 0) {
            Pair(weightedLatSum / totalWeight, weightedLonSum / totalWeight)
        } else {
            Pair(observations.last().latitude, observations.last().longitude)
        }
    }

    // HMM accuracy calculation: weighted standard deviation in meters
    private fun computeHMMAccuracy(observations: List<Location>, filteredLocation: Pair<Double, Double>): Double {
        if (observations.size < 2) return 0.0
        val (avgLat, avgLon) = filteredLocation
        var totalWeight = 0.0
        var weightedVarianceSum = 0.0
        for (loc in observations) {
            val weight = if (loc.accuracy > 0) 1.0 / loc.accuracy else 1.0
            val d = distanceMeters(avgLat, avgLon, loc.latitude, loc.longitude)
            weightedVarianceSum += d * d * weight
            totalWeight += weight
        }
        return if (totalWeight > 0) {
            sqrt(weightedVarianceSum / totalWeight) // meters
        } else {
            0.0
        }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6378137.0 // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    // --- IMU, permissions, lifecycle, and helpers ---

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
        try {
            fusedClient?.requestLocationUpdates(locationRequest!!, gpsCallback!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted, cannot request updates.", e)
        }
    }

    private fun stopLocation() {
        gpsCallback?.let { fusedClient?.removeLocationUpdates(it) }
        gpsCallback = null
        goodFixCount = 0
        lastEmittedLat = null
        lastEmittedLon = null
        observations.clear()
        kalmanInitialized = false
        Log.i(TAG, "Stopped location updates and cleared state")
    }

    private fun startImu() {
        if (imuSink == null) return

        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (sensorListener == null) {
            sensorListener = object : SensorEventListener {
                var lastAx = 0f; var lastAy = 0f; var lastAz = 0f
                var lastGx = 0f; var lastGy = 0f; var lastGz = 0f

                override fun onSensorChanged(event: SensorEvent) {
                    val ts = System.currentTimeMillis()
                    when (event.sensor.type) {
                        Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                            lastAx = event.values[0]; lastAy = event.values[1]; lastAz = event.values[2]
                        }
                        Sensor.TYPE_GYROSCOPE -> {
                            lastGx = event.values[0]; lastGy = event.values[1]; lastGz = event.values[2]
                        }
                    }

                    val map = hashMapOf<String, Any>(
                        "ts" to ts,
                        "ax" to lastAx, "ay" to lastAy, "az" to lastAz,
                        "gx" to lastGx, "gy" to lastGy, "gz" to lastGz
                    )
                    imuSink?.success(map)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
        }

        val rateUs = (1_000_000 / imuHz).coerceAtLeast(20_000) // SensorManager.SENSOR_DELAY_GAME
        Log.i(TAG, "Register IMU listeners at ~${imuHz}Hz (rateUs=$rateUs)")
        accel?.let { sensorManager?.registerListener(sensorListener, it, rateUs) }
        gyro?.let { sensorManager?.registerListener(sensorListener, it, rateUs) }
    }

    private fun stopImu() {
        sensorListener?.let { sensorManager?.unregisterListener(it) }
        sensorListener = null
        Log.i(TAG, "Unregistered IMU listeners")
    }

    private fun requestPermissions() {
        activity?.let { act ->
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
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
    }

    // Formatting helpers
    private fun Double.format1() = String.format("%.1f", this)
    private fun Double.format2() = String.format("%.2f", this)
    private fun Double.format6() = String.format("%.6f", this)

    private fun priorityName(p: Int) = when (p) {
        Priority.PRIORITY_HIGH_ACCURACY -> "HIGH"
        Priority.PRIORITY_BALANCED_POWER_ACCURACY -> "BALANCED"
        Priority.PRIORITY_LOW_POWER -> "LOW"
        Priority.PRIORITY_PASSIVE -> "PASSIVE"
        else -> "UNKNOWN"
    }
}


//import android.Manifest
//import android.app.Activity
//import android.content.Context
//import android.content.pm.PackageManager
//import android.hardware.Sensor
//import android.hardware.SensorEvent
//import android.hardware.SensorEventListener
//import android.hardware.SensorManager
//import android.location.Location
//import android.os.Build
//import android.os.Looper
//import android.util.Log
//import androidx.core.app.ActivityCompat
//import com.google.android.gms.location.FusedLocationProviderClient
//import com.google.android.gms.location.LocationCallback
//import com.google.android.gms.location.LocationRequest
//import com.google.android.gms.location.LocationResult
//import com.google.android.gms.location.LocationServices
//import com.google.android.gms.location.Priority
//import io.flutter.embedding.engine.plugins.FlutterPlugin
//import io.flutter.embedding.engine.plugins.activity.ActivityAware
//import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
//import io.flutter.plugin.common.EventChannel
//import io.flutter.plugin.common.MethodCall
//import io.flutter.plugin.common.MethodChannel
//import kotlin.math.*
//
//class LocationAccuracyPlugin :
//    FlutterPlugin,
//    MethodChannel.MethodCallHandler,
//    ActivityAware {
//
//    private val TAG = "LocationAccuracy"
//
//    private lateinit var context: Context
//    private var activity: Activity? = null
//
//    private lateinit var methodChannel: MethodChannel
//    private lateinit var gpsChannel: EventChannel
//    private lateinit var imuChannel: EventChannel
//
//    // GPS
//    private var fusedClient: FusedLocationProviderClient? = null
//    private var gpsCallback: LocationCallback? = null
//    private var gpsSink: EventChannel.EventSink? = null
//    private var locationRequest: LocationRequest? = null
//
//    // Request config
//    private var gpsIntervalMs: Int = 1000
//    private var fastestIntervalMs: Int = 250
//    private var currentPriority: Int = Priority.PRIORITY_HIGH_ACCURACY
//
//    // Dynamic priority control (hysteresis)
//    private var promoteHighAboveM: Double = 15.0
//    private var demoteBalancedBelowM: Double = 12.0
//    private var lastPrioritySwitchMs: Long = 0L
//    private var minSwitchIntervalMs: Long = 5_000L
//
//    // Accuracy gating and smoothing
//    private var targetAccuracyM: Double = 10.0
//    private var discardAccuracyAboveM: Double = 30.0
//    private var settleSamples: Int = 3
//    private var goodFixCount: Int = 0
//
//    // Location smoothing and deadband filtering variables
//    private var smoothLat: Double? = null
//    private var smoothLon: Double? = null
//    private var lastEmittedLat: Double? = null
//    private var lastEmittedLon: Double? = null
//    private var deadbandMeters: Double = 1.5 // Minimum movement to emit update
//
//    // Last-good holding
//    private var lastGoodLat: Double? = null
//    private var lastGoodLon: Double? = null
//    private var lastGoodTs: Long = 0L
//    private var lastGoodAccM: Double = Double.MAX_VALUE
//    private var goodHoldTimeoutMs: Long = 10_000 // ms to hold last good fix visually
//
//    // IMU
//    private var sensorManager: SensorManager? = null
//    private var imuSink: EventChannel.EventSink? = null
//    private var sensorListener: SensorEventListener? = null
//    private var imuHz: Int = 50
//
//    // Observation window for HMM-like weighted average smoothing
//    private val stateCountToKeep = 10
//    private val observations = mutableListOf<Location>()
//
//    //MARK: KALMAN
//    private val kalmanFilter = KalmanFilter2D()
//    private var kalmanInitialized = false
//
//
//    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
//        context = binding.applicationContext
//        Log.i(TAG, "onAttachedToEngine")
//
//        methodChannel = MethodChannel(binding.binaryMessenger, "sensor_fused_location/methods")
//        methodChannel.setMethodCallHandler(this)
//
//        gpsChannel = EventChannel(binding.binaryMessenger, "sensor_fused_location/gps")
//        imuChannel = EventChannel(binding.binaryMessenger, "sensor_fused_location/imu")
//
//        gpsChannel.setStreamHandler(object : EventChannel.StreamHandler {
//            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
//                gpsSink = events
//                Log.i(TAG, "GPS onListen -> starting location if possible")
//                startLocationIfPossible()
//            }
//
//            override fun onCancel(arguments: Any?) {
//                Log.i(TAG, "GPS onCancel -> stopping location")
//                gpsSink = null
//                stopLocation()
//            }
//        })
//
//        imuChannel.setStreamHandler(object : EventChannel.StreamHandler {
//            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
//                imuSink = events
//                Log.i(TAG, "IMU onListen -> starting IMU")
//                startImu()
//            }
//
//            override fun onCancel(arguments: Any?) {
//                Log.i(TAG, "IMU onCancel -> stopping IMU")
//                imuSink = null
//                stopImu()
//            }
//        })
//
//        fusedClient = LocationServices.getFusedLocationProviderClient(context)
//        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
//    }
//
//    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
//        when (call.method) {
//            "initialize" -> {
//                val highAccuracyDefault = call.argument<Boolean>("highAccuracy") ?: true
//                currentPriority = if (highAccuracyDefault) Priority.PRIORITY_HIGH_ACCURACY
//                else Priority.PRIORITY_BALANCED_POWER_ACCURACY
//
//                gpsIntervalMs = call.argument<Int>("gpsIntervalMs") ?: 1000
//                imuHz = call.argument<Int>("imuHz") ?: 50
//                fastestIntervalMs = max(250, call.argument<Int>("fastestIntervalMs") ?: (gpsIntervalMs / 2))
//
//                targetAccuracyM = (call.argument<Double>("targetAccuracyM") ?: 10.0).coerceAtLeast(5.0)
//                discardAccuracyAboveM = call.argument<Double>("discardAccuracyAboveM") ?: 30.0
//                settleSamples = call.argument<Int>("settleSamples") ?: 3
//                deadbandMeters = call.argument<Double>("deadbandMeters") ?: 1.5
//                goodHoldTimeoutMs = (call.argument<Int>("goodHoldTimeoutMs") ?: 10_000).toLong()
//
//                promoteHighAboveM = call.argument<Double>("promoteHighAboveM") ?: 15.0
//                demoteBalancedBelowM = call.argument<Double>("demoteBalancedBelowM") ?: 12.0
//                minSwitchIntervalMs = (call.argument<Int>("minSwitchIntervalMs") ?: 5_000).toLong()
//
//                Log.i(
//                    TAG, "initialize: priority=${priorityName(currentPriority)}, " +
//                            "gpsIntervalMs=$gpsIntervalMs, fastestIntervalMs=$fastestIntervalMs, imuHz=$imuHz, " +
//                            "targetAcc=$targetAccuracyM, discard>$discardAccuracyAboveM, settle=$settleSamples, " +
//                            "deadband=${"%.2f".format(deadbandMeters)}m, goodHold=${goodHoldTimeoutMs}ms, " +
//                            "promote>${promoteHighAboveM}m, demote<=${demoteBalancedBelowM}m"
//                )
//
//                buildLocationRequest(currentPriority)
//                result.success(null)
//            }
//
//            "requestPermissions" -> {
//                Log.i(TAG, "requestPermissions()")
//                requestPermissions()
//                result.success(null)
//            }
//
//            "getHMMAccuracy" -> {
//                val filteredLocation = simpleHMMFilter(observations)
//                val hmmAccuracy = computeHMMAccuracy(observations, filteredLocation)
//                result.success(hmmAccuracy)
//            }
//
//            else -> result.notImplemented()
//        }
//    }
//
//    private fun buildLocationRequest(priority: Int) {
//        locationRequest = LocationRequest.Builder(priority, gpsIntervalMs.toLong())
//            .setMinUpdateIntervalMillis(fastestIntervalMs.toLong())
//            .setWaitForAccurateLocation(priority == Priority.PRIORITY_HIGH_ACCURACY)
//            .setMaxUpdateDelayMillis(gpsIntervalMs.toLong())
//            .build()
//        Log.i(
//            TAG, "buildLocationRequest: priority=${priorityName(priority)}, " +
//                    "interval=${gpsIntervalMs}ms fastest=${fastestIntervalMs}ms waitAcc=${priority == Priority.PRIORITY_HIGH_ACCURACY}"
//        )
//    }
//
//    private fun maybeSwitchPriority(latestAccM: Double) {
//        val now = System.currentTimeMillis()
//        if (now - lastPrioritySwitchMs < minSwitchIntervalMs) return
//
//        val wantHigh = latestAccM > promoteHighAboveM
//        val wantBalanced = latestAccM <= demoteBalancedBelowM
//
//        if (wantHigh && currentPriority != Priority.PRIORITY_HIGH_ACCURACY) {
//            Log.i(TAG, "Switching priority to HIGH (acc=${"%.1f".format(latestAccM)}m)")
//            currentPriority = Priority.PRIORITY_HIGH_ACCURACY
//            lastPrioritySwitchMs = now
//            restartLocationUpdates()
//        } else if (wantBalanced && currentPriority != Priority.PRIORITY_BALANCED_POWER_ACCURACY) {
//            Log.i(TAG, "Switching priority to BALANCED (acc=${"%.1f".format(latestAccM)}m)")
//            currentPriority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
//            lastPrioritySwitchMs = now
//            restartLocationUpdates()
//        }
//    }
//
//    private fun restartLocationUpdates() {
//        Log.i(TAG, "restartLocationUpdates() with ${priorityName(currentPriority)}")
//        buildLocationRequest(currentPriority)
//        if (gpsSink != null && hasLocationPermissions() && locationRequest != null) {
//            gpsCallback?.let { fusedClient?.removeLocationUpdates(it) }
//            if (gpsCallback == null) createCallback()
//            fusedClient?.requestLocationUpdates(locationRequest!!, gpsCallback!!, Looper.getMainLooper())
//        }
//    }
//
//    private fun createCallback() {
//        gpsCallback = object : LocationCallback() {
//            override fun onLocationResult(locationResult: LocationResult) {
//                val loc = locationResult.lastLocation ?: return
//                val acc = loc.accuracy.toDouble()
//                val timestamp = loc.time
//
//                maybeSwitchPriority(acc)
//
//                if (acc.isNaN() || acc <= 0.0 || acc > discardAccuracyAboveM) {
//                    Log.d(TAG, "Discard fix: acc=${"%.1f".format(acc)}m > ${discardAccuracyAboveM}m")
//                    return
//                }
//
//                if (acc <= targetAccuracyM) {
//                    goodFixCount++
//                    lastGoodLat = loc.latitude
//                    lastGoodLon = loc.longitude
//                    lastGoodTs = loc.time
//                    lastGoodAccM = acc
//                } else {
//                    goodFixCount = 0
//                }
//                val isGood = acc <= targetAccuracyM && goodFixCount >= settleSamples
//
//                // Accuracy-weighted smoothing
//                if (smoothLat == null) {
//                    smoothLat = loc.latitude
//                    smoothLon = loc.longitude
//                } else {
//                    val w = when {
//                        acc <= 5.0 -> 0.6
//                        acc <= 10.0 -> 0.4
//                        acc <= 20.0 -> 0.25
//                        else -> 0.15
//                    }
//                    smoothLat = (1 - w) * smoothLat!! + w * loc.latitude
//                    smoothLon = (1 - w) * smoothLon!! + w * loc.longitude
//                }
//
//                // Add current location to observations for filtering
//                observations.add(Location(loc))
//                if (observations.size > stateCountToKeep) observations.removeAt(0)
//
//                // Calculate weighted average location as a simple HMM-like filter
//                val filteredLocation = simpleHMMFilter(observations)
//                val hmmAccuracy = computeHMMAccuracy(observations, filteredLocation)
//
//                val now = loc.time
//                val canHoldGood = lastGoodLat != null && (now - lastGoodTs) <= goodHoldTimeoutMs
//                val outLatLon = if (!isGood && canHoldGood) {
//                    Pair(lastGoodLat!!, lastGoodLon!!)
//                } else {
//                    filteredLocation
//                }
//
//                // Deadband filter: suppress small location changes
//                val finalLatLon = if (lastEmittedLat != null && lastEmittedLon != null) {
//                    val d = distanceMeters(lastEmittedLat!!, lastEmittedLon!!, outLatLon.first, outLatLon.second)
//                    if (d < deadbandMeters) {
//                        Pair(lastEmittedLat!!, lastEmittedLon!!)
//                    } else {
//                        outLatLon
//                    }
//                } else outLatLon
//
//                lastEmittedLat = finalLatLon.first
//                lastEmittedLon = finalLatLon.second
//
//                if (!kalmanInitialized) {
//                    kalmanFilter.initialize(loc.latitude, loc.longitude, acc, timestamp)
//                    kalmanInitialized = true
//                }
//
//                val (filteredLat, filteredLon) = kalmanFilter.update(loc.latitude, loc.longitude, acc, timestamp)
//
//                // Use filtered location as final output
//                val kalManFinalLatLon = Pair(filteredLat, filteredLon)
//
//                Log.d(TAG,
//                    "Kalman filter location=(${filteredLat.format6()}, ${filteredLon.format6()}) raw=(${loc.latitude.format6()}, ${loc.longitude.format6()}) acc=${acc.format1()}m"
//                )
//
//                //MARK:  Calculate and log the difference between the raw and filtered locations
//                val kalmanImprovementM = distanceMeters(loc.latitude, loc.longitude, filteredLat, filteredLon)
//
//                Log.d(TAG, "Kalman filter improvement: %.2f meters from raw fix".format(kalmanImprovementM))
//
//                Log.d(
//                    TAG,
//                    "Fix: raw=(${loc.latitude.format6()}, ${loc.longitude.format6()}) " +
//                            "acc=${acc.format1()}m spd=${loc.speed.format2()}m/s hdg=${loc.bearing.format1()}° " +
//                            "smooth=(${smoothLat!!.format6()}, ${smoothLon!!.format6()}) " +
//                            "filtered=(${filteredLocation.first.format6()}, ${filteredLocation.second.format6()}) " +
//                            "emit=(${finalLatLon.first.format6()}, ${finalLatLon.second.format6()}) " +
//                            "isGood=$isGood usingLastGood=${(!isGood && canHoldGood)} priority=${
//                                priorityName(
//                                    currentPriority
//                                )
//                            } HMMAcc=${hmmAccuracy.format1()} ,, KALMAN= ${kalManFinalLatLon.first.format6()}, ${kalManFinalLatLon.second.format6()}"
//                )
//
//                val map = hashMapOf<String, Any>(
//                    "ts" to loc.time,
//                    "lat" to filteredLocation.first,  // send raw HMM filtered latitude
//                    "lon" to filteredLocation.second, // send raw HMM filtered longitude
//                    "hmmLat" to filteredLocation.first,  // optionally duplicate or separate keys
//                    "hmmLon" to filteredLocation.second,
//                    "acc" to acc,
//                    "spd" to loc.speed.toDouble(),
//                    "hdg" to loc.bearing.toDouble(),
//                    "dr" to false,
//                    "isGood" to isGood,
//                    "isUsable" to true,
//                    "usingLastGood" to (!isGood && canHoldGood),
//                    "priority" to priorityName(currentPriority),
//                    "hmmAcc" to hmmAccuracy,
//                    "finalLat" to finalLatLon.first,   // existing final fired lat
//                    "finalLon" to finalLatLon.second   // existing final fired lon
//                )
//                gpsSink?.success(map)
//            }
//        }
//    }
//
//    // Simple weighted average filter simulating HMM smoothing
//    private fun simpleHMMFilter(observations: List<Location>): Pair<Double, Double> {
//        if (observations.isEmpty()) return Pair(smoothLat ?: 0.0, smoothLon ?: 0.0)
//
//        var totalWeight = 0.0
//        var weightedLatSum = 0.0
//        var weightedLonSum = 0.0
//        for (loc in observations) {
//            val weight = if (loc.accuracy > 0) 1.0 / loc.accuracy else 1.0
//            weightedLatSum += loc.latitude * weight
//            weightedLonSum += loc.longitude * weight
//            totalWeight += weight
//        }
//
//        val avgLat = weightedLatSum / totalWeight
//        val avgLon = weightedLonSum / totalWeight
//
//        Log.d(TAG, "HMM filter: weighted average location = ($avgLat, $avgLon) based on ${observations.size} points")
//        return Pair(avgLat, avgLon)
//    }
//
//    // HMM accuracy calculation: weighted standard deviation in meters
//    private fun computeHMMAccuracy(observations: List<Location>, filteredLocation: Pair<Double, Double>): Double {
//        if (observations.isEmpty()) return 0.0
//        val (avgLat, avgLon) = filteredLocation
//        var totalWeight = 0.0
//        var weightedVarianceSum = 0.0
//        for (loc in observations) {
//            val weight = if (loc.accuracy > 0) 1.0 / loc.accuracy else 1.0
//            val d = distanceMeters(avgLat, avgLon, loc.latitude, loc.longitude)
//            weightedVarianceSum += d * d * weight
//            totalWeight += weight
//        }
//        val weightedVariance = weightedVarianceSum / totalWeight
//        return sqrt(weightedVariance) // meters
//    }
//
//    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
//        val R = 6378137.0
//        val dLat = Math.toRadians(lat2 - lat1)
//        val dLon = Math.toRadians(lon2 - lon1)
//        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
//        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
//        return R * c
//    }
//
//    // --- IMU, permissions, formatting, etc ---
//
//    private fun startLocationIfPossible() {
//        if (gpsSink == null) return
//        if (!hasLocationPermissions()) {
//            Log.w(TAG, "No location permission; cannot start updates")
//            return
//        }
//
//        if (locationRequest == null) {
//            buildLocationRequest(currentPriority)
//        }
//        if (gpsCallback == null) {
//            createCallback()
//        }
//        Log.i(TAG, "requestLocationUpdates start (priority=${priorityName(currentPriority)})")
//        fusedClient?.requestLocationUpdates(locationRequest!!, gpsCallback!!, Looper.getMainLooper())
//    }
//
//    private fun stopLocation() {
//        gpsCallback?.let { fusedClient?.removeLocationUpdates(it) }
//        gpsCallback = null
//        goodFixCount = 0
//        smoothLat = null
//        smoothLon = null
//        lastEmittedLat = null
//        lastEmittedLon = null
//        observations.clear()
//        Log.i(TAG, "Stopped location updates and cleared observations")
//    }
//
//    private fun startImu() {
//        if (imuSink == null) return
//
//        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
//            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
//        val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
//
//        if (sensorListener == null) {
//            sensorListener = object : SensorEventListener {
//                var lastAx = 0f;
//                var lastAy = 0f;
//                var lastAz = 0f
//                var lastGx = 0f;
//                var lastGy = 0f;
//                var lastGz = 0f
//
//                override fun onSensorChanged(event: SensorEvent) {
//                    val ts = System.currentTimeMillis()
//                    when (event.sensor.type) {
//                        Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
//                            lastAx = event.values[0]
//                            lastAy = event.values[1]
//                            lastAz = event.values[2]
//                        }
//
//                        Sensor.TYPE_GYROSCOPE -> {
//                            lastGx = event.values[0]
//                            lastGy = event.values[1]
//                            lastGz = event.values[2]
//                        }
//                    }
//
//                    val map = hashMapOf<String, Any>(
//                        "ts" to ts,
//                        "ax" to lastAx,
//                        "ay" to lastAy,
//                        "az" to lastAz,
//                        "gx" to lastGx,
//                        "gy" to lastGy,
//                        "gz" to lastGz
//                    )
//                    imuSink?.success(map)
//                }
//
//                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
//            }
//        }
//
//        val rateUs = (1_000_000 / imuHz).coerceAtLeast(5_000)
//        Log.i(TAG, "Register IMU listeners at ~${imuHz}Hz (rateUs=$rateUs)")
//        accel?.let { sensorManager?.registerListener(sensorListener, it, rateUs) }
//        gyro.let { sensorManager?.registerListener(sensorListener, it, rateUs) }
//    }
//
//    private fun stopImu() {
//        sensorListener?.let { sensorManager?.unregisterListener(it) }
//        sensorListener = null
//        Log.i(TAG, "Unregistered IMU listeners")
//    }
//
//    private fun requestPermissions() {
//        activity?.let { act ->
//            val perms = mutableListOf(
//                Manifest.permission.ACCESS_COARSE_LOCATION,
//                Manifest.permission.ACCESS_FINE_LOCATION
//            )
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//                perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
//            }
//            Log.i(TAG, "Requesting permissions: $perms")
//            ActivityCompat.requestPermissions(act, perms.toTypedArray(), 1001)
//        } ?: run {
//            Log.w(TAG, "requestPermissions: no activity attached")
//        }
//    }
//
//    private fun hasLocationPermissions(): Boolean {
//        val fine = ActivityCompat.checkSelfPermission(
//            context, Manifest.permission.ACCESS_FINE_LOCATION
//        ) == PackageManager.PERMISSION_GRANTED
//        val coarse = ActivityCompat.checkSelfPermission(
//            context, Manifest.permission.ACCESS_COARSE_LOCATION
//        ) == PackageManager.PERMISSION_GRANTED
//        Log.d(TAG, "hasLocationPermissions fine=$fine coarse=$coarse")
//        return fine || coarse
//    }
//
//    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
//        activity = binding.activity
//        Log.i(TAG, "onAttachedToActivity: ${activity?.localClassName}")
//    }
//
//    override fun onDetachedFromActivityForConfigChanges() {
//        Log.i(TAG, "onDetachedFromActivityForConfigChanges")
//        activity = null
//    }
//
//    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
//        activity = binding.activity
//        Log.i(TAG, "onReattachedToActivityForConfigChanges: ${activity?.localClassName}")
//    }
//
//    override fun onDetachedFromActivity() {
//        Log.i(TAG, "onDetachedFromActivity")
//        activity = null
//    }
//
//    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
//        Log.i(TAG, "onDetachedFromEngine")
//        methodChannel.setMethodCallHandler(null)
//        stopLocation()
//        stopImu()
//        gpsSink = null
//        imuSink = null
//    }
//
//    // Helpers for formatting
//    private fun Double.format1() = String.format("%.1f", this)
//    private fun Float.format2() = String.format("%.2f", this)
//    private fun Float.format1() = String.format("%.1f", this)
//    private fun Double.format6() = String.format("%.6f", this)
//    private fun Float.format3() = String.format("%.3f", this)
//
//    private fun priorityName(p: Int) = when (p) {
//        Priority.PRIORITY_HIGH_ACCURACY -> "HIGH"
//        Priority.PRIORITY_BALANCED_POWER_ACCURACY -> "BALANCED"
//        Priority.PRIORITY_LOW_POWER -> "LOW"
//        Priority.PRIORITY_PASSIVE -> "PASSIVE"
//        else -> "UNKNOWN"
//    }
//}
//
//// You may need the data class for Location if not present:
//data class Location(val latitude: Double, val longitude: Double, val accuracy: Float, val time: Long) {
//    constructor(loc: Location) : this(
//        loc.latitude,
//        loc.longitude,
//        loc.accuracy,
//        loc.time
//    )
//}

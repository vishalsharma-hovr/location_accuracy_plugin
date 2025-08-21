package com.example.location_accuracy_plugin

import android.util.Log
import kotlin.math.*

// Snap-to-Roads Data Classes
data class LatLng(
    val latitude: Double,
    val longitude: Double
)

data class GPSPoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val accuracy: Float
)

//data class RoadSegment(
//    val id: Long,
//    val coordinates: List<LatLng>,
//    val roadType: String,
//    val maxSpeed: Int,
//    val isOneWay: Boolean = false
//)

data class SnapResult(
    val originalPoint: LatLng,
    val snappedPoint: LatLng,
    val roadSegment: RoadSegment,
    val confidence: Double,
    val distance: Double
)

/*// Spatial Index for efficient road lookup
//class SpatialIndex {
//    private val roadIndex = mutableMapOf<String, MutableList<RoadSegment>>()
//
//    fun insert(road: RoadSegment) {
//        val bounds = calculateBounds(road.coordinates)
//        val gridKey = "${bounds.first.toInt()}_${bounds.second.toInt()}"
//        roadIndex.getOrPut(gridKey) { mutableListOf() }.add(road)
//    }
//
//    fun findNearby(point: LatLng, radiusMeters: Double): List<RoadSegment> {
//        val nearbyRoads = mutableListOf<RoadSegment>()
//        val gridKeys = getGridKeysInRadius(point, radiusMeters)
//
//        gridKeys.forEach { key ->
//            roadIndex[key]?.let { roads ->
//                nearbyRoads.addAll(roads.filter { road ->
//                    isRoadNearPoint(road, point, radiusMeters)
//                })
//            }
//        }
//
//        return nearbyRoads
//    }
//
//    private fun calculateBounds(coordinates: List<LatLng>): Pair<Double, Double> {
//        val centerLat = coordinates.map { it.latitude }.average()
//        val centerLon = coordinates.map { it.longitude }.average()
//        return Pair(centerLat, centerLon)
//    }
//
//    private fun getGridKeysInRadius(point: LatLng, radiusMeters: Double): List<String> {
//        // Simplified grid lookup - in production, use proper spatial indexing
//        val gridSize = 0.01 // ~1km grid
//        val keys = mutableListOf<String>()
//
//        for (latOffset in -1..1) {
//            for (lonOffset in -1..1) {
//                val lat = (point.latitude + latOffset * gridSize).toInt()
//                val lon = (point.longitude + lonOffset * gridSize).toInt()
//                keys.add("${lat}_${lon}")
//            }
//        }
//
//        return keys
//    }
//
//    private fun isRoadNearPoint(road: RoadSegment, point: LatLng, radiusMeters: Double): Boolean {
//        return road.coordinates.any { coord ->
//            haversineDistance(point.latitude, point.longitude, coord.latitude, coord.longitude) <= radiusMeters
//        }
//    }
//
//    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
//        val R = 6371000.0 // Earth radius in meters
//        val dLat = Math.toRadians(lat2 - lat1)
//        val dLon = Math.toRadians(lon2 - lon1)
//
//        val a = sin(dLat / 2).pow(2.0) +
//                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
//                sin(dLon / 2).pow(2.0)
//
//        return R * 2 * asin(sqrt(a))
//    }
//}*/

// Map Matcher Class

private val TAG = "LocationAccuracy"
class MapMatcher {
    private val roadSegments = mutableMapOf<Long, RoadSegment>()
    private val spatialIndex = SpatialIndex()
    
    companion object {
        private const val EARTH_RADIUS = 6371000.0
        private const val SEARCH_RADIUS = 50.0
        private const val GPS_SIGMA = 10.0
    }

    data class NearestRoadResult(
        val roadSegment: RoadSegment,
        val distance: Double,
        val snapPoint: LatLng
    )

    fun clearAllRoads() {
        roadSegments.clear()
        spatialIndex.clear()
        Log.i(TAG, "Cleared all road data")
    }

    fun addRoadSegment(segment: RoadSegment) {
        roadSegments[segment.id] = segment
        spatialIndex.insert(segment)
        Log.d(TAG, "Added road segment: ID=${segment.id}, type=${segment.roadType}, points=${segment.coordinates.size}")

        // Log each coordinate
        segment.coordinates.forEachIndexed { index, coord ->
            Log.v(TAG, "  Road ${segment.id} Point $index: (${coord.latitude}, ${coord.longitude})")
        }
    }

    fun loadRoadSegments(segments: List<RoadSegment>) {
        segments.forEach { addRoadSegment(it) }
        Log.i(TAG, "Loaded ${segments.size} total road segments")
        Log.i(TAG, "Available road IDs: ${roadSegments.keys}")
    }

    fun getRoadCount(): Int = roadSegments.size
    fun findNearestRoad(gpsPoint: GPSPoint): NearestRoadResult? {
        val point = LatLng(gpsPoint.latitude, gpsPoint.longitude)

        Log.d(TAG, "=== NEAREST ROAD COORDINATE SEARCH ===")
        Log.d(TAG, "Input GPS Point: (${point.latitude}, ${point.longitude})")

        if (roadSegments.isEmpty()) {
            Log.e(TAG, "❌ No road segments loaded for coordinate search")
            return null
        }

        var nearestRoad: RoadSegment? = null
        var nearestDistance = Double.MAX_VALUE
        var nearestSnapPoint: LatLng? = null
        var nearestSegmentIndex = -1

        // Check all roads to find the absolutely nearest one
        roadSegments.values.forEachIndexed { roadIndex, road ->
            Log.v(TAG, "Checking road ${road.id} (${road.getDisplayName()}) with ${road.coordinates.size} coordinate points")

            // Log all road coordinates for this road
            road.coordinates.forEachIndexed { coordIndex, coord ->
                Log.v(TAG, "  Road ${road.id} Point[$coordIndex]: (${coord.latitude}, ${coord.longitude})")
            }

            for (i in 0 until road.coordinates.size - 1) {
                val segmentStart = road.coordinates[i]
                val segmentEnd = road.coordinates[i + 1]

                Log.v(TAG, "  Processing segment $i: Start(${segmentStart.latitude}, ${segmentStart.longitude}) -> End(${segmentEnd.latitude}, ${segmentEnd.longitude})")

                val snapPoint = projectPointOntoSegment(point, segmentStart, segmentEnd)
                val distance = haversineDistance(
                    point.latitude, point.longitude,
                    snapPoint.latitude, snapPoint.longitude
                )

                Log.v(TAG, "    Snap point: (${snapPoint.latitude}, ${snapPoint.longitude}), Distance: ${String.format("%.2f", distance)}m")

                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearestRoad = road
                    nearestSnapPoint = snapPoint
                    nearestSegmentIndex = i

                    Log.d(TAG, "  🎯 NEW NEAREST: Road ${road.id}, Segment $i, Snap Point: (${snapPoint.latitude}, ${snapPoint.longitude}), Distance: ${String.format("%.2f", distance)}m")
                }
            }
        }

        return if (nearestRoad != null && nearestSnapPoint != null) {
            Log.i(TAG, "✅ FINAL NEAREST ROAD RESULT:")
            Log.i(TAG, "  Road ID: ${nearestRoad.id}")
            Log.i(TAG, "  Road Name: ${nearestRoad.getDisplayName()}")
            Log.i(TAG, "  Segment Index: $nearestSegmentIndex")
            Log.i(TAG, "  Original GPS: (${point.latitude}, ${point.longitude})")
            Log.i(TAG, "  Snapped Coordinates: (${nearestSnapPoint.latitude}, ${nearestSnapPoint.longitude})")
            Log.i(TAG, "  Distance: ${String.format("%.2f", nearestDistance)}m")

            NearestRoadResult(nearestRoad, nearestDistance, nearestSnapPoint)
        } else {
            Log.w(TAG, "❌ No nearest road found")
            null
        }
    }

    // Enhanced snapToRoad with coordinate logging
    fun snapToRoad(gpsPoint: GPSPoint): SnapResult? {
        val startTime = System.currentTimeMillis()
        val point = LatLng(gpsPoint.latitude, gpsPoint.longitude)

        Log.d(TAG, "=== SNAP-TO-ROAD COORDINATE PROCESSING ===")
        Log.d(TAG, "Input GPS: (${gpsPoint.latitude}, ${gpsPoint.longitude}), Accuracy: ${gpsPoint.accuracy}m")

        val nearbyRoads = spatialIndex.findNearby(point, SEARCH_RADIUS)
        Log.d(TAG, "Found ${nearbyRoads.size} roads within ${SEARCH_RADIUS}m")

        var bestResult: SnapResult? = null

        nearbyRoads.forEach { road ->
            Log.v(TAG, "Processing road ${road.id} (${road.getDisplayName()}) for snap-to-road")

            val snapResult = findBestSnapPoint(point, road)

            Log.v(TAG, "  Snap result for road ${road.id}:")
            Log.v(TAG, "    Original: (${snapResult.originalPoint.latitude}, ${snapResult.originalPoint.longitude})")
            Log.v(TAG, "    Snapped:  (${snapResult.snappedPoint.latitude}, ${snapResult.snappedPoint.longitude})")
            Log.v(TAG, "    Distance: ${String.format("%.2f", snapResult.distance)}m")
            Log.v(TAG, "    Confidence: ${String.format("%.3f", snapResult.confidence)}")

            if (bestResult == null || snapResult.confidence > bestResult!!.confidence) {
                bestResult = snapResult
                Log.d(TAG, "  🎯 NEW BEST SNAP: Road ${road.id}, Coordinates: (${snapResult.snappedPoint.latitude}, ${snapResult.snappedPoint.longitude})")
            }
        }

        val processingTime = System.currentTimeMillis() - startTime

        bestResult?.let { result ->
            Log.i(TAG, "✅ FINAL SNAP-TO-ROAD RESULT:")
            Log.i(TAG, "  Road ID: ${result.roadSegment.id}")
            Log.i(TAG, "  Road Name: ${result.roadSegment.getDisplayName()}")
            Log.i(TAG, "  Original GPS: (${result.originalPoint.latitude}, ${result.originalPoint.longitude})")
            Log.i(TAG, "  Snapped Coordinates: (${result.snappedPoint.latitude}, ${result.snappedPoint.longitude})")
            Log.i(TAG, "  Distance: ${String.format("%.2f", result.distance)}m")
            Log.i(TAG, "  Confidence: ${String.format("%.3f", result.confidence)}")
            Log.i(TAG, "  Processing Time: ${processingTime}ms")
        } ?: Log.w(TAG, "❌ No valid snap result found")

        return bestResult
    }
//    fun findNearestRoad(gpsPoint: GPSPoint): NearestRoadResult? {
//        val point = LatLng(gpsPoint.latitude, gpsPoint.longitude)
//
//        Log.d(TAG, "Finding nearest road to point: (${point.latitude}, ${point.longitude})")
//
//        if (roadSegments.isEmpty()) {
//            Log.w(TAG, "No road segments loaded for nearest road search")
//            return null
//        }
//
//        var nearestRoad: RoadSegment? = null
//        var nearestDistance = Double.MAX_VALUE
//        var nearestSnapPoint: LatLng? = null
//
//        // Check all roads to find the absolutely nearest one
//        roadSegments.values.forEach { road ->
//            for (i in 0 until road.coordinates.size - 1) {
//                val segmentStart = road.coordinates[i]
//                val segmentEnd = road.coordinates[i + 1]
//
//                val snapPoint = projectPointOntoSegment(point, segmentStart, segmentEnd)
//                val distance = haversineDistance(
//                    point.latitude, point.longitude,
//                    snapPoint.latitude, snapPoint.longitude
//                )
//
//                if (distance < nearestDistance) {
//                    nearestDistance = distance
//                    nearestRoad = road
//                    nearestSnapPoint = snapPoint
//                }
//            }
//        }
//
//        return if (nearestRoad != null && nearestSnapPoint != null) {
//            Log.d(TAG, "Nearest road: ${nearestRoad.getDisplayName()} at ${String.format("%.2f", nearestDistance)}m")
//            NearestRoadResult(nearestRoad, nearestDistance, nearestSnapPoint)
//        } else {
//            Log.w(TAG, "No nearest road found")
//            null
//        }
//    }
//
//
//    fun snapToRoad(gpsPoint: GPSPoint): SnapResult? {
//        val startTime = System.currentTimeMillis()
//        val point = LatLng(gpsPoint.latitude, gpsPoint.longitude)
//
//        Log.d(TAG, "=== SNAP TO ROAD DEBUG ===")
//        Log.d(TAG, "GPS Point: (${gpsPoint.latitude}, ${gpsPoint.longitude})")
//        Log.d(TAG, "Total roads loaded: ${roadSegments.size}")
//        Log.d(TAG, "Search radius: ${SEARCH_RADIUS}m")
//
//        if (roadSegments.isEmpty()) {
//            Log.e(TAG, "❌ No road segments loaded!")
//            return null
//        }
//
//        // First, let's check distances to all roads manually for debugging
//        Log.d(TAG, "=== DISTANCE CHECK TO ALL ROADS ===")
//        var closestDistance = Double.MAX_VALUE
//        var closestRoadId: Long? = null
//
//        roadSegments.values.forEach { road ->
//            val minDistanceToRoad = road.coordinates.minOf { coord ->
//                haversineDistance(point.latitude, point.longitude, coord.latitude, coord.longitude)
//            }
//
//            Log.d(TAG, "Road ${road.id}: min distance = ${String.format("%.2f", minDistanceToRoad)}m")
//
//            if (minDistanceToRoad < closestDistance) {
//                closestDistance = minDistanceToRoad
//                closestRoadId = road.id
//            }
//        }
//
//        Log.d(TAG, "Closest road: $closestRoadId at ${String.format("%.2f", closestDistance)}m")
//
//        // Now try spatial index
//
//        val nearbyRoads = spatialIndex.findNearby(point, SEARCH_RADIUS)
//        Log.d(TAG, "Spatial index found: ${nearbyRoads.size} roads")
//
//        if (nearbyRoads.isEmpty()) {
//            Log.w(TAG, "❌ Spatial index found no nearby roads within ${SEARCH_RADIUS}m")
//
//            // Try with much larger radius for debugging
//            val debugRoads = spatialIndex.findNearby(point, SEARCH_RADIUS * 5)
//            Log.d(TAG, "Debug: ${debugRoads.size} roads found within ${SEARCH_RADIUS * 5}m")
//
//            // If closest road is within reasonable distance, there's a spatial index problem
//            if (closestDistance < SEARCH_RADIUS * 2) {
//                Log.e(TAG, "❌ SPATIAL INDEX BUG: Closest road is ${String.format("%.2f", closestDistance)}m but spatial index didn't find it")
//
//                // Fallback: manually check closest road
//                closestRoadId?.let { roadId ->
//                    val road = roadSegments[roadId]
//                    if (road != null && closestDistance <= SEARCH_RADIUS) {
//                        Log.i(TAG, "🔧 Using fallback: processing closest road $roadId")
//                        val snapResult = findBestSnapPoint(point, road)
//                        Log.i(TAG, "Fallback result: distance=${String.format("%.2f", snapResult.distance)}m, confidence=${String.format("%.3f", snapResult.confidence)}")
//                        return snapResult
//                    }
//                }
//            }
//
//            return null
//        }
//
//        var bestResult: SnapResult? = null
//
//        nearbyRoads.forEach { road ->
//            Log.v(TAG, "Processing road ${road.id}")
//            val snapResult = findBestSnapPoint(point, road)
//            Log.v(TAG, "Road ${road.id}: distance=${String.format("%.2f", snapResult.distance)}m, confidence=${String.format("%.3f", snapResult.confidence)}")
//
//            if (bestResult == null || snapResult.confidence > bestResult!!.confidence) {
//                bestResult = snapResult
//            }
//        }
//
//        val processingTime = System.currentTimeMillis() - startTime
//
//        bestResult?.let { result ->
//            Log.i(TAG, "✅ Best snap result: road=${result.roadSegment.id}, distance=${String.format("%.2f", result.distance)}m, confidence=${String.format("%.3f", result.confidence)}, time=${processingTime}ms")
//        }
//
//        return bestResult
//    }



        private fun findBestSnapPoint(point: LatLng, road: RoadSegment): SnapResult {
        var minDistance = Double.MAX_VALUE
        var bestSnapPoint: LatLng? = null
        
        for (i in 0 until road.coordinates.size - 1) {
            val segmentStart = road.coordinates[i]
            val segmentEnd = road.coordinates[i + 1]
            
            val snapPoint = projectPointOntoSegment(point, segmentStart, segmentEnd)
            val distance = haversineDistance(
                point.latitude, point.longitude,
                snapPoint.latitude, snapPoint.longitude
            )
            
            if (distance < minDistance) {
                minDistance = distance
                bestSnapPoint = snapPoint
            }
        }
        
        val confidence = calculateConfidence(minDistance)
        
        return SnapResult(
            originalPoint = point,
            snappedPoint = bestSnapPoint!!,
            roadSegment = road,
            confidence = confidence,
            distance = minDistance
        )
    }
    
    private fun projectPointOntoSegment(point: LatLng, segStart: LatLng, segEnd: LatLng): LatLng {
        val dx = segEnd.longitude - segStart.longitude
        val dy = segEnd.latitude - segStart.latitude
        
        if (dx == 0.0 && dy == 0.0) return segStart
        
        val t = ((point.longitude - segStart.longitude) * dx + 
                 (point.latitude - segStart.latitude) * dy) / (dx * dx + dy * dy)
        
        val clampedT = t.coerceIn(0.0, 1.0)
        
        return LatLng(
            latitude = segStart.latitude + clampedT * dy,
            longitude = segStart.longitude + clampedT * dx
        )
    }
    
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = sin(dLat / 2).pow(2.0) + 
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * 
                sin(dLon / 2).pow(2.0)
        
        return EARTH_RADIUS * 2 * asin(sqrt(a))
    }
    
    private fun calculateConfidence(distance: Double): Double {
        val confidence = exp(-0.5 * (distance / GPS_SIGMA).pow(2.0))
        Log.v("MapMatcher", "Confidence: distance=${String.format("%.2f", distance)}m -> ${String.format("%.3f", confidence)}")
        return confidence
    }
}


class SpatialIndex {
    private val roadIndex = mutableMapOf<String, MutableList<RoadSegment>>()
    private val gridSize = 0.01 // Increased to ~1km grid for better coverage

    companion object {
        private const val TAG = "SpatialIndex"
    }

    fun clear() {
        roadIndex.clear()
        Log.d(TAG, "Cleared spatial index")
    }

    fun insert(road: RoadSegment) {
        val gridKeys = getGridKeysForRoad(road)
        gridKeys.forEach { key ->
            roadIndex.getOrPut(key) { mutableListOf() }.add(road)
        }

        Log.d(TAG, "Added road ${road.id} to ${gridKeys.size} grid cells")
        Log.v(TAG, "Grid keys for road ${road.id}: $gridKeys")
    }

    fun findNearby(point: LatLng, radiusMeters: Double): List<RoadSegment> {
        val gridKeys = getGridKeysInRadius(point, radiusMeters)
        val nearbyRoads = mutableSetOf<RoadSegment>()

        Log.d(TAG, "Searching for point (${point.latitude}, ${point.longitude}) with radius ${radiusMeters}m")
        Log.d(TAG, "Checking grid keys: $gridKeys")

        gridKeys.forEach { key ->
            val roadsInCell = roadIndex[key]
            Log.v(TAG, "Grid cell $key: ${roadsInCell?.size ?: 0} roads")

            roadsInCell?.forEach { road ->
                val isNear = isRoadNearPoint(road, point, radiusMeters)
                Log.v(TAG, "Road ${road.id} near check: $isNear")
                if (isNear) {
                    nearbyRoads.add(road)
                }
            }
        }

        Log.d(TAG, "Found ${nearbyRoads.size} nearby roads")
        return nearbyRoads.toList()
    }

    private fun getGridKeysForRoad(road: RoadSegment): Set<String> {
        val keys = mutableSetOf<String>()

        road.coordinates.forEach { coord ->
            val baseKey = getGridKey(coord.latitude, coord.longitude)
            keys.add(baseKey)

            // Add neighboring cells to handle roads crossing grid boundaries
            for (latOffset in -1..1) {
                for (lonOffset in -1..1) {
                    val offsetLat = coord.latitude + (latOffset * gridSize)
                    val offsetLon = coord.longitude + (lonOffset * gridSize)
                    keys.add(getGridKey(offsetLat, offsetLon))
                }
            }
        }

        return keys
    }

    private fun getGridKeysInRadius(point: LatLng, radiusMeters: Double): List<String> {
        val radiusDegrees = radiusMeters / 111000.0
        val keys = mutableSetOf<String>()

        val cellsInRadius = (radiusDegrees / gridSize).toInt() + 2 // Add buffer

        for (latOffset in -cellsInRadius..cellsInRadius) {
            for (lonOffset in -cellsInRadius..cellsInRadius) {
                val offsetLat = point.latitude + (latOffset * gridSize)
                val offsetLon = point.longitude + (lonOffset * gridSize)
                keys.add(getGridKey(offsetLat, offsetLon))
            }
        }

        return keys.toList()
    }

    private fun getGridKey(lat: Double, lon: Double): String {
        val gridLat = (lat / gridSize).toInt()
        val gridLon = (lon / gridSize).toInt()
        return "${gridLat}_${gridLon}"
    }

    private fun isRoadNearPoint(road: RoadSegment, point: LatLng, radiusMeters: Double): Boolean {
        // Check if any point on the road is within radius
        val hasNearPoint = road.coordinates.any { coord ->
            val distance = haversineDistance(point.latitude, point.longitude, coord.latitude, coord.longitude)
            distance <= radiusMeters
        }

        if (hasNearPoint) return true

        // Check if point is near any road segment (not just vertices)
        for (i in 0 until road.coordinates.size - 1) {
            val segStart = road.coordinates[i]
            val segEnd = road.coordinates[i + 1]
            val distanceToSegment = pointToLineSegmentDistance(point, segStart, segEnd)
            if (distanceToSegment <= radiusMeters) {
                return true
            }
        }

        return false
    }

    private fun pointToLineSegmentDistance(point: LatLng, lineStart: LatLng, lineEnd: LatLng): Double {
        val A = point.latitude - lineStart.latitude
        val B = point.longitude - lineStart.longitude
        val C = lineEnd.latitude - lineStart.latitude
        val D = lineEnd.longitude - lineStart.longitude

        val dot = A * C + B * D
        val lenSq = C * C + D * D

        if (lenSq == 0.0) {
            return haversineDistance(point.latitude, point.longitude, lineStart.latitude, lineStart.longitude)
        }

        val param = dot / lenSq
        val clampedParam = param.coerceIn(0.0, 1.0)

        val projLat = lineStart.latitude + clampedParam * C
        val projLon = lineStart.longitude + clampedParam * D

        return haversineDistance(point.latitude, point.longitude, projLat, projLon)
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)

        return R * 2 * asin(sqrt(a))
    }
}


data class RoadSegment(
    val id: Long,
    val coordinates: List<LatLng>,
    val roadType: String,
    val maxSpeed: Int,
    val isOneWay: Boolean = false,
    // Add road name fields
    val name: String = "",
    val ref: String = "", // Reference number (like "US-101", "I-95")
    val streetNumber: String = "",
    val locality: String = "", // City/area name
    val adminArea: String = "" // State/province
) {
    // Helper to get display name
    fun getDisplayName(): String {
        return when {
            name.isNotEmpty() -> name
            ref.isNotEmpty() -> ref
            else -> "Road #$id"
        }
    }

    fun getFullAddress(): String {
        val parts = mutableListOf<String>()
        if (name.isNotEmpty()) parts.add(name)
        if (ref.isNotEmpty()) parts.add(ref)
        if (locality.isNotEmpty()) parts.add(locality)
        if (adminArea.isNotEmpty()) parts.add(adminArea)
        return parts.joinToString(", ")
    }
}

// import 'location_accuracy_plugin_platform_interface.dart';

// class LocationAccuracyPlugin {
//   Future<String?> getPlatformVersion() {
//     return LocationAccuracyPluginPlatform.instance.getPlatformVersion();
//   }
// }

import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/services.dart';

class LocationAccuracyModel {
  final DateTime timestamp;
  final double latitude;
  final double longitude;
  final double accuracyM;
  final double speedMS;
  final double headingDeg;
  final bool isDeadReckoned;
  final double hmmAcc;

  // Snap-to-Roads fields
  final bool snapEnabled;
  final double snapLat;
  final double snapLon;
  final double snapConfidence;
  final double snapDistance;
  final int snapRoadId;
  final String snapRoadType;
  final bool snapApplied;

  // Kalman filter fields
  final double kalmanLat;
  final double kalmanLon;

  // Final processed coordinates
  final double finalLat;
  final double finalLon;

  // Nearest road information (ADD THESE FIELDS)
  final int nearestRoadId;
  final String nearestRoadName;
  final String nearestRoadType;
  final double nearestRoadDistance;
  final String nearestRoadFullAddress;

  LocationAccuracyModel({
    required this.timestamp,
    required this.latitude,
    required this.longitude,
    required this.accuracyM,
    required this.speedMS,
    required this.headingDeg,
    required this.isDeadReckoned,
    required this.hmmAcc,

    // Snap-to-Roads
    this.snapEnabled = false,
    required this.snapLat,
    required this.snapLon,
    this.snapConfidence = 0.0,
    this.snapDistance = 0.0,
    this.snapRoadId = -1,
    this.snapRoadType = '',
    this.snapApplied = false,

    // Kalman
    required this.kalmanLat,
    required this.kalmanLon,

    // Final
    required this.finalLat,
    required this.finalLon,

    // Nearest road data
    this.nearestRoadId = -1,
    this.nearestRoadName = '',
    this.nearestRoadType = '',
    this.nearestRoadDistance = 0.0,
    this.nearestRoadFullAddress = '',
  });

  factory LocationAccuracyModel.fromMap(Map<dynamic, dynamic> m) {
    return LocationAccuracyModel(
      timestamp: DateTime.fromMillisecondsSinceEpoch((m['ts'] as int)),
      latitude: (m['lat'] as num).toDouble(),
      longitude: (m['lon'] as num).toDouble(),
      accuracyM: (m['acc'] as num).toDouble(),
      speedMS: (m['spd'] as num?)?.toDouble() ?? 0.0,
      headingDeg: (m['hdg'] as num?)?.toDouble() ?? 0.0,
      isDeadReckoned: (m['dr'] as bool?) ?? false,
      hmmAcc: (m['hmmAcc'] as num?)?.toDouble() ?? 0.0,

      // Snap-to-Roads data
      snapEnabled: (m['snapEnabled'] as bool?) ?? false,
      snapLat: (m['snapLat'] as num?)?.toDouble() ?? (m['lat'] as num).toDouble(),
      snapLon: (m['snapLon'] as num?)?.toDouble() ?? (m['lon'] as num).toDouble(),
      snapConfidence: (m['snapConfidence'] as num?)?.toDouble() ?? 0.0,
      snapDistance: (m['snapDistance'] as num?)?.toDouble() ?? 0.0,
      snapRoadId: (m['snapRoadId'] as int?) ?? -1,
      snapRoadType: (m['snapRoadType'] as String?) ?? '',
      snapApplied: (m['snapApplied'] as bool?) ?? false,

      // Kalman filter data
      kalmanLat: (m['kalmanLat'] as num?)?.toDouble() ?? (m['lat'] as num).toDouble(),
      kalmanLon: (m['kalmanLon'] as num?)?.toDouble() ?? (m['lon'] as num).toDouble(),

      // Final processed coordinates
      finalLat: (m['finalLat'] as num?)?.toDouble() ?? (m['lat'] as num).toDouble(),
      finalLon: (m['finalLon'] as num?)?.toDouble() ?? (m['lon'] as num).toDouble(),

      // Nearest road data (ADD THESE MAPPINGS)
      nearestRoadId: (m['nearestRoadId'] as int?) ?? -1,
      nearestRoadName: (m['nearestRoadName'] as String?) ?? '',
      nearestRoadType: (m['nearestRoadType'] as String?) ?? '',
      nearestRoadDistance: (m['nearestRoadDistance'] as num?)?.toDouble() ?? 0.0,
      nearestRoadFullAddress: (m['nearestRoadFullAddress'] as String?) ?? '',
    );
  }

  @override
  String toString() {
    return 'LocationAccuracyModel('
        'ts: $timestamp, '
        'raw: ($latitude, $longitude), '
        'final: ($finalLat, $finalLon), '
        'acc: ${accuracyM.toStringAsFixed(1)}m, '
        'snap: ${snapApplied ? 'Road $snapRoadId (${snapConfidence.toStringAsFixed(3)})' : 'None'}, '
        'nearest: ${hasNearestRoad ? '$nearestRoadName (${nearestRoadDistance.toStringAsFixed(1)}m)' : 'None'}'
        ')';
  }

  // Helper methods
  bool get hasSnapData => snapApplied && snapRoadId != -1;

  // ADD THIS MISSING GETTER
  bool get hasNearestRoad => nearestRoadId != -1 && nearestRoadName.isNotEmpty;

  /// Get the best available coordinates (prioritizes final > snap > kalman > raw)
  (double lat, double lon) get bestCoordinates {
    if (finalLat != 0 && finalLon != 0) return (finalLat, finalLon);
    if (snapApplied) return (snapLat, snapLon);
    if (kalmanLat != 0 && kalmanLon != 0) return (kalmanLat, kalmanLon);
    return (latitude, longitude);
  }

  /// Get coordinates for map display (uses snapped if available and confident)
  (double lat, double lon) get displayCoordinates {
    if (snapApplied && snapConfidence > 0.5) {
      return (snapLat, snapLon);
    }
    return (finalLat, finalLon);
  }
}

class RoadCoordinate {
  final double latitude;
  final double longitude;

  RoadCoordinate({required this.latitude, required this.longitude});

  Map<String, dynamic> toMap() {
    return {'latitude': latitude, 'longitude': longitude};
  }
}

class RoadSegment {
  final int id;
  final List<RoadCoordinate> coordinates;
  final String roadType;
  final int maxSpeed;
  final bool isOneWay;
  // Add road name fields
  final String name;
  final String ref;
  final String streetNumber;
  final String locality;
  final String adminArea;

  RoadSegment({
    required this.id,
    required this.coordinates,
    this.roadType = 'unknown',
    this.maxSpeed = 50,
    this.isOneWay = false,
    this.name = '',
    this.ref = '',
    this.streetNumber = '',
    this.locality = '',
    this.adminArea = '',
  });

  String get displayName {
    if (name.isNotEmpty) return name;
    if (ref.isNotEmpty) return ref;
    return 'Road #$id';
  }

  String get fullAddress {
    final parts = <String>[];
    if (name.isNotEmpty) parts.add(name);
    if (ref.isNotEmpty) parts.add(ref);
    if (locality.isNotEmpty) parts.add(locality);
    if (adminArea.isNotEmpty) parts.add(adminArea);
    return parts.join(', ');
  }

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'coordinates': coordinates.map((c) => c.toMap()).toList(),
      'roadType': roadType,
      'maxSpeed': maxSpeed,
      'isOneWay': isOneWay,
      'name': name,
      'ref': ref,
      'streetNumber': streetNumber,
      'locality': locality,
      'adminArea': adminArea,
    };
  }
}

class ImuModel {
  final DateTime timestamp;
  final List<double> accelMS2; // ax, ay, az (device frame)
  final List<double> gyroRadS; // gx, gy, gz (device frame)

  ImuModel({required this.timestamp, required this.accelMS2, required this.gyroRadS});

  factory ImuModel.fromMap(Map<dynamic, dynamic> m) {
    double safeDouble(dynamic value) {
      if (value == null) return 0.0;
      if (value is num) return value.toDouble();
      return double.tryParse(value.toString()) ?? 0.0;
    }

    return ImuModel(
      timestamp: DateTime.fromMillisecondsSinceEpoch(m['ts'] as int),
      accelMS2: [safeDouble(m['ax']), safeDouble(m['ay']), safeDouble(m['az'])],
      gyroRadS: [safeDouble(m['gx']), safeDouble(m['gy']), safeDouble(m['gz'])],
    );
  }
}

class LocationAccuracyPlugin {
  static const MethodChannel _method = MethodChannel('sensor_fused_location/methods');
  static const EventChannel _gpsChannel = EventChannel('sensor_fused_location/gps');
  static const EventChannel _imuChannel = EventChannel('sensor_fused_location/imu');

  // Public fused stream
  static Stream<LocationAccuracyModel>? _fusedStream;
  static StreamSubscription? _gpsSub;
  static StreamSubscription? _imuSub;

  // Internal state for simple fusion
  static LocationAccuracyModel? _lastGps;
  static ImuModel? _lastImu;
  static DateTime? _lastUpdateTs;
  static double _estLat = 0.0;
  static double _estLon = 0.0;
  static double _estSpd = 0.0;
  static double _estHdgDeg = 0.0;
  static bool _initialized = false;

  // Tuning
  static double gpsTrust = 0.7;
  static double imuDtMaxSec = 2.0;
  static double accelNoiseGate = 0.15;
  static const double earthRadiusM = 6378137.0;

  // Snap-to-roads state
  static bool _snapToRoadsEnabled = false;
  static int _loadedRoadSegments = 0;

  static Future<void> initialize({
    bool highAccuracy = true,
    int gpsIntervalMs = 1000,
    int imuHz = 50,
    // Snap-to-roads parameters
    bool enableSnapToRoads = false,
    double snapConfidenceThreshold = 0.3,
    double maxSnapDistance = 50.0,
    // Other existing parameters
    double? targetAccuracyM,
    double? discardAccuracyAboveM,
    int? settleSamples,
    double? deadbandMeters,
    int? goodHoldTimeoutMs,
  }) async {
    final params = <String, dynamic>{
      'highAccuracy': highAccuracy,
      'gpsIntervalMs': gpsIntervalMs,
      'imuHz': imuHz,

      // Snap-to-roads configuration
      'enableSnapToRoads': enableSnapToRoads,
      'snapConfidenceThreshold': snapConfidenceThreshold,
      'maxSnapDistance': maxSnapDistance,
    };

    // Add optional parameters if provided
    if (targetAccuracyM != null) params['targetAccuracyM'] = targetAccuracyM;
    if (discardAccuracyAboveM != null) params['discardAccuracyAboveM'] = discardAccuracyAboveM;
    if (settleSamples != null) params['settleSamples'] = settleSamples;
    if (deadbandMeters != null) params['deadbandMeters'] = deadbandMeters;
    if (goodHoldTimeoutMs != null) params['goodHoldTimeoutMs'] = goodHoldTimeoutMs;

    await _method.invokeMethod('initialize', params);

    _snapToRoadsEnabled = enableSnapToRoads;
    print('LocationAccuracyPlugin initialized: snap-to-roads = $enableSnapToRoads');
  }

  /// Load road segments for snap-to-roads functionality
  static Future<Map<String, dynamic>> loadRoadSegments(List<RoadSegment> roads) async {
    try {
      if (!_snapToRoadsEnabled) {
        throw Exception('Snap-to-roads not enabled. Call initialize() with enableSnapToRoads: true first.');
      }

      final roadData = roads.map((road) => road.toMap()).toList();

      final result = await _method.invokeMethod('loadRoadData', {'roads': roadData});

      _loadedRoadSegments = result['loaded'] ?? 0;
      print('Loaded $_loadedRoadSegments road segments for snap-to-roads');

      return Map<String, dynamic>.from(result);
    } catch (e) {
      print('Error loading road segments: $e');
      rethrow;
    }
  }

  /// Load road data from GeoJSON or other formats
  static Future<Map<String, dynamic>> loadRoadDataFromGeoJson(String geoJsonData) async {
    try {
      // Parse GeoJSON and convert to road segments
      // This is a simplified example - you'd need proper GeoJSON parsing
      final List<RoadSegment> roads = _parseGeoJsonToRoads(geoJsonData);
      return await loadRoadSegments(roads);
    } catch (e) {
      print('Error loading road data from GeoJSON: $e');
      rethrow;
    }
  }

  /// Create sample road data for testing (San Francisco area)
  static Future<Map<String, dynamic>> loadSampleRoadData(double lat, double lon, {double radiusKm = 1.0}) async {
    final roads = <RoadSegment>[];
    final step = 0.005; // ~500m steps
    int roadId = 10000;

    // Create a grid of roads around the location
    for (double latOffset = -radiusKm / 111; latOffset <= radiusKm / 111; latOffset += step) {
      for (double lonOffset = -radiusKm / 85; lonOffset <= radiusKm / 85; lonOffset += step) {
        // Horizontal road
        roads.add(
          RoadSegment(
            id: roadId++,
            coordinates: [
              RoadCoordinate(latitude: lat + latOffset, longitude: lon + lonOffset - step),
              RoadCoordinate(latitude: lat + latOffset, longitude: lon + lonOffset + step),
            ],
            roadType: 'local',
            maxSpeed: 30,
          ),
        );

        // Vertical road
        roads.add(
          RoadSegment(
            id: roadId++,
            coordinates: [
              RoadCoordinate(latitude: lat + latOffset - step, longitude: lon + lonOffset),
              RoadCoordinate(latitude: lat + latOffset + step, longitude: lon + lonOffset),
            ],
            roadType: 'local',
            maxSpeed: 30,
          ),
        );
      }
    }

    print('Generated ${roads.length} roads around location ($lat, $lon)');
    return await loadRoadSegments(roads);
  }

  /// Clear all loaded road data
  static Future<void> clearRoadData() async {
    await _method.invokeMethod('clearRoadData');
    _loadedRoadSegments = 0;
    print('Cleared road data');
  }

  /// Get snap-to-roads status
  static bool get isSnapToRoadsEnabled => _snapToRoadsEnabled;
  static int get loadedRoadSegmentCount => _loadedRoadSegments;

  static Future<void> requestPermissions() async {
    await _method.invokeMethod('requestPermissions');
  }

  static Stream<LocationAccuracyModel> fusedLocationStream() {
    _fusedStream ??= _buildFusedStream().asBroadcastStream();
    return _fusedStream!;
  }

  static Stream<LocationAccuracyModel> _buildFusedStream() async* {
    final gpsStream = _gpsChannel.receiveBroadcastStream("gps").map((e) {
      final data = Map<dynamic, dynamic>.from(e);
      final model = LocationAccuracyModel.fromMap(data);

      // Debug logging for snap-to-roads
      if (_snapToRoadsEnabled) {
        print('GPS Update: ${model.snapApplied ? 'Snapped to road ${model.snapRoadId} (conf: ${model.snapConfidence.toStringAsFixed(3)})' : 'No snap applied'}');
      }

      return model;
    });

    final imuStream = _imuChannel.receiveBroadcastStream("imu").map((e) => ImuModel.fromMap(Map<dynamic, dynamic>.from(e)));

    _gpsSub?.cancel();
    _imuSub?.cancel();

    final controller = StreamController<LocationAccuracyModel>();

    _gpsSub = gpsStream.listen((gps) {
      _lastGps = gps;
      _lastUpdateTs = gps.timestamp;

      if (!_initialized) {
        // Use best coordinates for initialization
        final (lat, lon) = gps.bestCoordinates;
        _estLat = lat;
        _estLon = lon;
        _estSpd = gps.speedMS;
        _estHdgDeg = gps.headingDeg;
        _initialized = true;
      } else {
        // Simple blend: push estimate toward GPS (use best coordinates)
        final (gpsLat, gpsLon) = gps.bestCoordinates;
        _estLat = gpsTrust * gpsLat + (1 - gpsTrust) * _estLat;
        _estLon = gpsTrust * gpsLon + (1 - gpsTrust) * _estLon;
        _estSpd = gpsTrust * gps.speedMS + (1 - gpsTrust) * _estSpd;
        _estHdgDeg = _blendHeading(_estHdgDeg, gps.headingDeg, gpsTrust);
      }

      // Create fused location model with all the data
      final fusedModel = LocationAccuracyModel(
        timestamp: gps.timestamp,
        latitude: gps.latitude, // Keep original raw GPS
        longitude: gps.longitude,
        accuracyM: gps.accuracyM,
        speedMS: _estSpd,
        headingDeg: _estHdgDeg,
        isDeadReckoned: false,
        hmmAcc: gps.hmmAcc,

        // Pass through snap-to-roads data
        snapEnabled: gps.snapEnabled,
        snapLat: gps.snapLat,
        snapLon: gps.snapLon,
        snapConfidence: gps.snapConfidence,
        snapDistance: gps.snapDistance,
        snapRoadId: gps.snapRoadId,
        snapRoadType: gps.snapRoadType,
        snapApplied: gps.snapApplied,

        // Kalman data
        kalmanLat: gps.kalmanLat,
        kalmanLon: gps.kalmanLon,

        // Final coordinates (use for display)
        finalLat: _estLat,
        finalLon: _estLon,
      );

      controller.add(fusedModel);
    });

    _imuSub = imuStream.listen((imu) {
      if (!_initialized) return;
      if (_lastUpdateTs == null) _lastUpdateTs = imu.timestamp;

      final dt = (imu.timestamp.millisecondsSinceEpoch - _lastUpdateTs!.millisecondsSinceEpoch) / 1000.0;
      if (dt <= 0 || dt > imuDtMaxSec) {
        _lastUpdateTs = imu.timestamp;
        return;
      }

      // Dead reckoning step (same as before but with enhanced coordinates)
      final gz = imu.gyroRadS[2];
      _estHdgDeg += (gz * dt) * 180.0 / math.pi;
      _estHdgDeg = _normalizeDeg(_estHdgDeg);

      final ax = imu.accelMS2[0];
      final ay = imu.accelMS2[1];
      double aHoriz = math.sqrt(ax * ax + ay * ay);
      if (aHoriz.abs() < accelNoiseGate) aHoriz = 0.0;

      _estSpd = math.max(0.0, _estSpd + aHoriz * dt);

      final headingRad = _estHdgDeg * math.pi / 180.0;
      final dx = _estSpd * dt * math.cos(headingRad);
      final dy = _estSpd * dt * math.sin(headingRad);

      final dLat = (dy / earthRadiusM) * (180.0 / math.pi);
      final dLon = (dx / (earthRadiusM * math.cos(_estLat * math.pi / 180.0))) * (180.0 / math.pi);

      _estLat += dLat;
      _estLon += dLon;
      _lastUpdateTs = imu.timestamp;

      // Create dead-reckoned model with last known snap data
      final drModel = LocationAccuracyModel(
        timestamp: imu.timestamp,
        latitude: _estLat,
        longitude: _estLon,
        accuracyM: (_lastGps?.accuracyM ?? 10.0) + 2.0,
        speedMS: _estSpd,
        headingDeg: _estHdgDeg,
        isDeadReckoned: true,
        hmmAcc: _lastGps?.hmmAcc ?? 0.0,

        // Keep last known snap data (but mark as not current)
        snapEnabled: _lastGps?.snapEnabled ?? false,
        snapLat: _lastGps?.snapLat ?? _estLat,
        snapLon: _lastGps?.snapLon ?? _estLon,
        snapConfidence: 0.0, // Reset confidence during DR
        snapDistance: 0.0,
        snapRoadId: _lastGps?.snapRoadId ?? -1,
        snapRoadType: _lastGps?.snapRoadType ?? '',
        snapApplied: false, // Not applied during DR

        kalmanLat: _estLat,
        kalmanLon: _estLon,
        finalLat: _estLat,
        finalLon: _estLon,
      );

      controller.add(drModel);
    });

    yield* controller.stream;
  }

  // Helper method to parse GeoJSON (simplified example)
  static List<RoadSegment> _parseGeoJsonToRoads(String geoJsonData) {
    // This is a placeholder - implement proper GeoJSON parsing
    // You might want to use a package like dart_geojson
    return [];
  }

  static double _normalizeDeg(double deg) {
    var d = deg % 360.0;
    if (d < 0) d += 360.0;
    return d;
  }

  static double _blendHeading(double baseDeg, double measDeg, double w) {
    final a = baseDeg * math.pi / 180.0;
    final b = measDeg * math.pi / 180.0;
    final x = (1 - w) * math.cos(a) + w * math.cos(b);
    final y = (1 - w) * math.sin(a) + w * math.sin(b);
    return _normalizeDeg(math.atan2(y, x) * 180.0 / math.pi);
  }

  static Future<void> dispose() async {
    await _gpsSub?.cancel();
    await _imuSub?.cancel();
    _fusedStream = null;
  }
}

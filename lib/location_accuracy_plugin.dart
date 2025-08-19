
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

  LocationAccuracyModel({
    required this.timestamp,
    required this.latitude,
    required this.longitude,
    required this.accuracyM,
    required this.speedMS,
    required this.headingDeg,
    required this.isDeadReckoned,
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
    );
  }
}

class ImuModel {
  final DateTime timestamp;
  final List<double> accelMS2; // ax, ay, az (device frame)
  final List<double> gyroRadS; // gx, gy, gz (device frame)

  ImuModel({
    required this.timestamp,
    required this.accelMS2,
    required this.gyroRadS,
  });

  factory ImuModel.fromMap(Map<dynamic, dynamic> m) {
  double safeDouble(dynamic value) {
    if (value == null) return 0.0;
    if (value is num) return value.toDouble();
    return double.tryParse(value.toString()) ?? 0.0;
  }

  return ImuModel(
    timestamp: DateTime.fromMillisecondsSinceEpoch(m['ts'] as int),
    accelMS2: [
      safeDouble(m['ax']),
      safeDouble(m['ay']),
      safeDouble(m['az']),
    ],
    gyroRadS: [
      safeDouble(m['gx']),
      safeDouble(m['gy']),
      safeDouble(m['gz']),
    ],
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
  static double gpsTrust = 0.7; // 0..1 weight toward GPS on update
  static double imuDtMaxSec = 2.0; // max dead-reckon window between GPS fixes
  static double accelNoiseGate = 0.15; // m/s^2 gate for tiny accelerations
  static const double earthRadiusM = 6378137.0;

  static Future<void> initialize({
    bool highAccuracy = true,
    int gpsIntervalMs = 1000,
    int imuHz = 50,
  }) async {
    await _method.invokeMethod('initialize', {
      'highAccuracy': highAccuracy,
      'gpsIntervalMs': gpsIntervalMs,
      'imuHz': imuHz,
    });
  }

  static Future<void> requestPermissions() async {
    await _method.invokeMethod('requestPermissions');
  }

  static Stream<LocationAccuracyModel> fusedLocationStream() {
    _fusedStream ??= _buildFusedStream().asBroadcastStream();
    return _fusedStream!;
  }

  static Stream<LocationAccuracyModel> _buildFusedStream() async* {
    final gpsStream = _gpsChannel.receiveBroadcastStream("gps").map((e) => LocationAccuracyModel.fromMap(Map<dynamic, dynamic>.from(e)));
    final imuStream = _imuChannel.receiveBroadcastStream("imu").map((e) => ImuModel.fromMap(Map<dynamic, dynamic>.from(e)));

    _gpsSub?.cancel();
    _imuSub?.cancel();

    final controller = StreamController<LocationAccuracyModel>();

    _gpsSub = gpsStream.listen((gps) {
      _lastGps = gps;
      _lastUpdateTs = gps.timestamp;

      if (!_initialized) {
        _estLat = gps.latitude;
        _estLon = gps.longitude;
        _estSpd = gps.speedMS;
        _estHdgDeg = gps.headingDeg;
        _initialized = true;
      } else {
        // Simple blend: push estimate toward GPS
        _estLat = gpsTrust * gps.latitude + (1 - gpsTrust) * _estLat;
        _estLon = gpsTrust * gps.longitude + (1 - gpsTrust) * _estLon;
        _estSpd = gpsTrust * gps.speedMS + (1 - gpsTrust) * _estSpd;
        // Heading: wrap properly
        _estHdgDeg = _blendHeading(_estHdgDeg, gps.headingDeg, gpsTrust);
      }

      controller.add(LocationAccuracyModel(
        timestamp: gps.timestamp,
        latitude: _estLat,
        longitude: _estLon,
        accuracyM: gps.accuracyM,
        speedMS: _estSpd,
        headingDeg: _estHdgDeg,
        isDeadReckoned: false,
      ));
    });

    _imuSub = imuStream.listen((imu) {
      if (!_initialized) return;
      if (_lastUpdateTs == null) _lastUpdateTs = imu.timestamp;

      final dt = (imu.timestamp.millisecondsSinceEpoch - _lastUpdateTs!.millisecondsSinceEpoch) / 1000.0;
      if (dt <= 0 || dt > imuDtMaxSec) {
        _lastUpdateTs = imu.timestamp;
        return;
      }

      // Dead reckoning step:
      // 1) Update heading from gyro z (very rough; for portrait-up device; replace with fused orientation)
      final gz = imu.gyroRadS[2];
      _estHdgDeg += (gz * dt) * 180.0 / math.pi;
      _estHdgDeg = _normalizeDeg(_estHdgDeg);

      // 2) Estimate forward acceleration magnitude in device frame.
      // This is oversimplified. Proper implementation should transform to world frame and subtract gravity.
      final ax = imu.accelMS2;
      final ay = imu.accelMS2[1];
      final az = imu.accelMS2[2];

      double aHoriz = math.sqrt(ax[0] * ax[0] + ay * ay);
      if (aHoriz.abs() < accelNoiseGate) aHoriz = 0.0;

      // 3) Integrate to speed and displacement
      _estSpd = math.max(0.0, _estSpd + aHoriz * dt);

      final headingRad = _estHdgDeg * math.pi / 180.0;
      final dx = _estSpd * dt * math.cos(headingRad);
      final dy = _estSpd * dt * math.sin(headingRad);

      // 4) Project meters to lat/lon (equirectangular approx; OK for small steps)
      final dLat = (dy / earthRadiusM) * (180.0 / math.pi);
      final dLon = (dx / (earthRadiusM * math.cos(_estLat * math.pi / 180.0))) * (180.0 / math.pi);

      _estLat += dLat;
      _estLon += dLon;

      _lastUpdateTs = imu.timestamp;

      controller.add(LocationAccuracyModel(
        timestamp: imu.timestamp,
        latitude: _estLat,
        longitude: _estLon,
        accuracyM: (_lastGps?.accuracyM ?? 10.0) + 2.0, // grow slightly during DR
        speedMS: _estSpd,
        headingDeg: _estHdgDeg,
        isDeadReckoned: true,
      ));
    });

    yield* controller.stream;
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

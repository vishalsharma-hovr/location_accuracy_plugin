import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:h3_flutter/h3_flutter.dart';
import 'package:location_accuracy_plugin/location_accuracy_plugin.dart';
import 'package:location_accuracy_plugin_example/road_name_service.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Location with Snap-to-Roads',
      theme: ThemeData(primarySwatch: Colors.blue, useMaterial3: true),
      home: const LocationScreen(),
    );
  }
}

class LocationScreen extends StatefulWidget {
  const LocationScreen({super.key});

  @override
  State<LocationScreen> createState() => _LocationScreenState();
}

class _LocationScreenState extends State<LocationScreen> {
  StreamSubscription<LocationAccuracyModel>? _locationSub;
  LocationAccuracyModel? _currentLocation;

  // H3 related variables
  final h3Factory = const H3Factory().load();
  String h3Index = "";
  String geoJson2H3String = "";
  String resolution = "";
  String resolutionOfCell = "";

  // Initialization state
  bool _isInitializing = true;
  bool _isInitialized = false;
  String _initializationError = "";

  @override
  void initState() {
    super.initState();
    _initializeLocation();
  }

  Future<void> _initializeLocation() async {
    try {
      setState(() {
        _isInitializing = true;
        _initializationError = "";
      });

      print('🚀 Starting location initialization...');

      // Request permissions first
      await LocationAccuracyPlugin.requestPermissions();
      print('✅ Permissions requested');

      // Initialize with snap-to-roads enabled
      await LocationAccuracyPlugin.initialize(
        highAccuracy: true,
        gpsIntervalMs: 1000,
        imuHz: 50,
        enableSnapToRoads: true,
        snapConfidenceThreshold: 0.3,
        maxSnapDistance: 50.0,
        targetAccuracyM: 10.0,
        discardAccuracyAboveM: 30.0,
        settleSamples: 3,
        deadbandMeters: 1.5,
        goodHoldTimeoutMs: 10000,
      );
      print('✅ LocationAccuracyPlugin initialized');

      // Load sample road data for testing
      try {
        final result = await LocationAccuracyPlugin.loadSampleRoadData(_currentLocation!.finalLat, _currentLocation!.finalLon);
        RoadNameService.getRoadNameFromCoordinates(_currentLocation!.finalLat, _currentLocation!.finalLon);
        print('✅ Loaded road  $result');
      } catch (e) {
        print('⚠️ Failed to load road  $e');
        // Continue anyway - snap-to-roads just won't work
      }

      // Start listening to location updates
      _locationSub = LocationAccuracyPlugin.fusedLocationStream().listen(
        (location) {
          setState(() {
            _currentLocation = location;
          });

          // Calculate H3 index
          _updateH3Data(location);

          // Log snap-to-roads information
          if (location.snapApplied) {
            print(
              '📍 Snapped to road ${location.snapRoadId} '
              '(${location.snapRoadType}): '
              'confidence=${location.snapConfidence.toStringAsFixed(3)}',
            );
          }

          // Debug log
          // print('Location update: ${location.latitude.toStringAsFixed(6)}, ${location.longitude.toStringAsFixed(6)} | Snap: ${location.snapApplied}');
        },
        onError: (error) {
          print('❌ Location error: $error');
          setState(() {
            _initializationError = 'Location stream error: $error';
          });
        },
      );

      setState(() {
        _isInitializing = false;
        _isInitialized = true;
      });

      print('✅ Location initialization completed');
    } catch (e) {
      print('❌ Error initializing location: $e');
      setState(() {
        _isInitializing = false;
        _initializationError = 'Initialization failed: $e';
      });
    }
  }

  void _updateH3Data(LocationAccuracyModel location) {
    try {
      // Use best coordinates for H3 calculation
      final (lat, lon) = location.displayCoordinates;

      final res = _h3ResolutionForAccuracy(location.accuracyM);
      final h3Cell = h3Factory.geoToCell(GeoCoord(lon: lon, lat: lat), res);
      final geojson2h3 = Geojson2H3(h3Factory);

      setState(() {
        h3Index = h3Cell.toString();
        geoJson2H3String = jsonEncode(geojson2h3.h3ToFeature(h3Cell));
        resolution = res.toString();
        resolutionOfCell = h3Factory.getResolution(h3Cell).toString();
      });

      // print('H3 Index: $h3Index at resolution $res');
    } catch (e) {
      print('Error calculating H3: $e');
    }
  }

  // Heuristic: map reported horizontal accuracy to an H3 resolution
  int _h3ResolutionForAccuracy(double? accM) {
    final acc = accM ?? 15.0;
    if (acc <= 8.0) return 12;
    if (acc <= 20.0) return 11;
    if (acc <= 35.0) return 10;
    if (acc <= 70.0) return 9;
    return 8; // coarse when accuracy is poor
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Location with Snap-to-Roads'), backgroundColor: Theme.of(context).colorScheme.inversePrimary),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_isInitializing) {
      return const Center(
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [CircularProgressIndicator(), SizedBox(height: 16), Text('Initializing location services...')]),
      );
    }

    if (_initializationError.isNotEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.error, color: Colors.red, size: 48),
            const SizedBox(height: 16),
            Text('Initialization Error', style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 8),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Text(
                _initializationError,
                textAlign: TextAlign.center,
                style: const TextStyle(color: Colors.red),
              ),
            ),
            const SizedBox(height: 16),
            ElevatedButton(onPressed: _initializeLocation, child: const Text('Retry')),
          ],
        ),
      );
    }

    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildLocationInfo(),
          const SizedBox(height: 16),
          _buildNearestRoadInfo(), // Add this new widget
          const SizedBox(height: 16),
          _buildSnapToRoadsInfo(),
          const SizedBox(height: 16),
          _buildH3Info(),
          const SizedBox(height: 16),
          _buildActionButtons(),
        ],
      ),
    );
  }

  Widget _buildNearestRoadInfo() {
    if (_currentLocation == null) return Container();

    final loc = _currentLocation!;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('🗺️ Nearest Road', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
            const SizedBox(height: 12),

            if (loc.hasNearestRoad) ...[
              _buildInfoRow('Road Name', loc.nearestRoadName),
              _buildInfoRow('Distance', '${loc.nearestRoadDistance.toStringAsFixed(1)}m'),
              _buildInfoRow('Road Type', loc.nearestRoadType),
              if (loc.nearestRoadFullAddress.isNotEmpty) _buildInfoRow('Full Address', loc.nearestRoadFullAddress),
              _buildInfoRow('Road ID', loc.nearestRoadId.toString()),

              // Visual indicator based on distance
              const SizedBox(height: 8),
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(color: _getRoadProximityColor(loc.nearestRoadDistance), borderRadius: BorderRadius.circular(4)),
                child: Row(
                  children: [
                    Icon(_getRoadProximityIcon(loc.nearestRoadDistance), color: Colors.white, size: 16),
                    const SizedBox(width: 8),
                    Text(
                      _getRoadProximityText(loc.nearestRoadDistance),
                      style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w500),
                    ),
                  ],
                ),
              ),
            ] else ...[
              const Text('❌ No roads found nearby', style: TextStyle(color: Colors.grey)),
            ],
          ],
        ),
      ),
    );
  }

  Color _getRoadProximityColor(double distance) {
    if (distance <= 10) return Colors.green;
    if (distance <= 25) return Colors.orange;
    if (distance <= 50) return Colors.red;
    return Colors.grey;
  }

  IconData _getRoadProximityIcon(double distance) {
    if (distance <= 10) return Icons.gps_fixed;
    if (distance <= 25) return Icons.gps_fixed_outlined;
    if (distance <= 50) return Icons.gps_not_fixed;
    return Icons.gps_off;
  }

  String _getRoadProximityText(double distance) {
    if (distance <= 10) return 'Very Close';
    if (distance <= 25) return 'Close';
    if (distance <= 50) return 'Nearby';
    return 'Far';
  }

  Widget _buildLocationInfo() {
    if (_currentLocation == null) {
      return const Card(
        child: Padding(
          padding: EdgeInsets.all(16),
          child: Row(children: [CircularProgressIndicator(), SizedBox(width: 16), Text('Waiting for location...')]),
        ),
      );
    }

    final loc = _currentLocation!;
    final (displayLat, displayLon) = loc.displayCoordinates;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('📍 Current Location', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
            const SizedBox(height: 12),
            _buildInfoRow('Display', '${displayLat.toStringAsFixed(6)}, ${displayLon.toStringAsFixed(6)}'),
            _buildInfoRow('Raw GPS', '${loc.latitude.toStringAsFixed(6)}, ${loc.longitude.toStringAsFixed(6)}'),
            _buildInfoRow('Final', '${loc.finalLat.toStringAsFixed(6)}, ${loc.finalLon.toStringAsFixed(6)}'),
            _buildInfoRow('Kalman', '${loc.kalmanLat.toStringAsFixed(6)}, ${loc.kalmanLon.toStringAsFixed(6)}'),
            const Divider(),
            _buildInfoRow('Accuracy', '${loc.accuracyM.toStringAsFixed(1)}m'),
            _buildInfoRow('HMM Accuracy', '${loc.hmmAcc.toStringAsFixed(1)}m'),
            _buildInfoRow('Speed', '${(loc.speedMS * 3.6).toStringAsFixed(1)} km/h'),
            _buildInfoRow('Heading', '${loc.headingDeg.toStringAsFixed(1)}°'),
            _buildInfoRow('Dead Reckoning', loc.isDeadReckoned ? 'Yes' : 'No'),
            _buildInfoRow('Timestamp', loc.timestamp.toString().substring(11, 19)),
          ],
        ),
      ),
    );
  }

  Widget _buildSnapToRoadsInfo() {
    if (_currentLocation == null) return Container();

    final loc = _currentLocation!;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Text('🛣️ Snap-to-Roads', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                const Spacer(),
                Icon(loc.snapApplied ? Icons.check_circle : Icons.cancel, color: loc.snapApplied ? Colors.green : Colors.grey),
              ],
            ),
            const SizedBox(height: 12),
            _buildInfoRow('Status', LocationAccuracyPlugin.isSnapToRoadsEnabled ? 'Enabled' : 'Disabled'),
            _buildInfoRow('Roads Loaded', LocationAccuracyPlugin.loadedRoadSegmentCount.toString()),

            if (loc.snapApplied) ...[
              const Divider(),
              const Text('🎯 Snapped Location:', style: TextStyle(fontWeight: FontWeight.w500)),
              const SizedBox(height: 8),
              _buildInfoRow('  Coordinates', '${loc.snapLat.toStringAsFixed(6)}, ${loc.snapLon.toStringAsFixed(6)}'),
              _buildInfoRow('  Road ID', loc.snapRoadId.toString()),
              _buildInfoRow('  Road Type', loc.snapRoadType),
              _buildInfoRow('  Confidence', loc.snapConfidence.toStringAsFixed(3)),
              _buildInfoRow('  Distance', '${loc.snapDistance.toStringAsFixed(2)}m'),
            ] else ...[
              const Divider(),
              Text('❌ Not snapped to road', style: TextStyle(color: Colors.grey[600])),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildH3Info() {
    if (_currentLocation == null || h3Index.isEmpty) return Container();

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('🗺️ H3 Geospatial Index', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
            const SizedBox(height: 12),
            _buildInfoRow('H3 Index', h3Index),
            _buildInfoRow('Resolution', resolution),
            _buildInfoRow('Cell Resolution', resolutionOfCell),
            if (geoJson2H3String.isNotEmpty) ...[
              const SizedBox(height: 8),
              const Text('GeoJSON:', style: TextStyle(fontWeight: FontWeight.w500)),
              const SizedBox(height: 4),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(color: Colors.grey[100], borderRadius: BorderRadius.circular(4)),
                child: Text(
                  geoJson2H3String,
                  style: const TextStyle(fontSize: 12, fontFamily: 'monospace'),
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildInfoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 100,
            child: Text('$label:', style: const TextStyle(fontWeight: FontWeight.w500)),
          ),
          Expanded(
            child: Text(value, style: const TextStyle(fontFamily: 'monospace')),
          ),
        ],
      ),
    );
  }

  Widget _buildActionButtons() {
    return Column(
      children: [
        SizedBox(
          width: double.infinity,
          child: ElevatedButton(
            onPressed: _isInitialized
                ? () async {
                    try {
                      final result = await LocationAccuracyPlugin.loadSampleRoadData(_currentLocation!.finalLat, _currentLocation!.finalLon);
                      if (mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Sample road data loaded: $result')));
                      }
                    } catch (e) {
                      if (mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Error loading road  $e'), backgroundColor: Colors.red));
                      }
                    }
                  }
                : null,
            child: const Text('Load Sample Road Data'),
          ),
        ),
        const SizedBox(height: 8),
        SizedBox(
          width: double.infinity,
          child: ElevatedButton(
            onPressed: _isInitialized
                ? () async {
                    try {
                      await LocationAccuracyPlugin.clearRoadData();
                      if (mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Road data cleared')));
                      }
                    } catch (e) {
                      if (mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Error clearing road  $e'), backgroundColor: Colors.red));
                      }
                    }
                  }
                : null,
            child: const Text('Clear Road Data'),
          ),
        ),
        const SizedBox(height: 8),
        SizedBox(
          width: double.infinity,
          child: ElevatedButton(onPressed: () => _initializeLocation(), child: const Text('Reinitialize Location')),
        ),
        SizedBox(
          width: double.infinity,
          child: ElevatedButton(
            onPressed: _currentLocation != null
                ? () async {
                    try {
                      final (lat, lon) = _currentLocation!.bestCoordinates;

                      // Show loading
                      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Getting real road name...')));

                      final roadName = await RoadNameService.getRoadNameFromCoordinates(lat, lon);
                      final detailedAddress = await RoadNameService.getDetailedAddressFromCoordinates(lat, lon);

                      if (mounted) {
                        showDialog(
                          context: context,
                          builder: (context) => AlertDialog(
                            title: Text('Real Road Information'),
                            content: Column(
                              mainAxisSize: MainAxisSize.min,
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('Road: ${detailedAddress['road']}'),
                                Text('Area: ${detailedAddress['suburb']}'),
                                Text('City: ${detailedAddress['city']}'),
                                Text('State: ${detailedAddress['state']}'),
                                Text('Country: ${detailedAddress['country']}'),
                                if (detailedAddress['postcode']!.isNotEmpty) Text('Postcode: ${detailedAddress['postcode']}'),
                              ],
                            ),
                            actions: [TextButton(onPressed: () => Navigator.of(context).pop(), child: Text('OK'))],
                          ),
                        );
                      }
                    } catch (e) {
                      if (mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Error getting road name: $e'), backgroundColor: Colors.red));
                      }
                    }
                  }
                : null,
            child: const Text('Get Real Road Name'),
          ),
        ),
      ],
    );
  }

  @override
  void dispose() {
    _locationSub?.cancel();
    LocationAccuracyPlugin.dispose();
    super.dispose();
  }
}

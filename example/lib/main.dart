import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:h3_flutter/h3_flutter.dart';
import 'package:location_accuracy_plugin/location_accuracy_plugin.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await LocationAccuracyPlugin.requestPermissions();
  await LocationAccuracyPlugin.initialize(
    highAccuracy: true,
    gpsIntervalMs: 1000,
    imuHz: 50,
  );
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});
  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  LocationAccuracyModel? _last;
  final h3Factory = const H3Factory().load();
  String h3Index = "";
  String geoJson2H3String = "";
  @override
  void initState() {
    super.initState();
    LocationAccuracyPlugin.fusedLocationStream().listen((s) {
      debugPrint("LocationAccuracy : sss $s");
      setState(() => _last = s);
      final res = _h3ResolutionForAccuracy(s.accuracyM);
      var a  = h3Factory.geoToCell(GeoCoord(lon: s.longitude, lat: s.latitude),res);
      // final res = h3.cellToGeo(s.latitude, s.longitude, s.accuracyM);
      //  final h3Index = h3.geoToH3(s.latitude, s.longitude, res);
      final geojson2h3 = Geojson2H3(h3Factory);
      debugPrint("LocationAccuracy : H3 Index $a");
      setState(() {
        h3Index = a.toString();
        geoJson2H3String = geojson2h3.h3ToFeature(a).toString();
      });
      debugPrint("LocationAccuracy : Res $res");
      debugPrint("LocationAccuracy : geojson2h3 ${geojson2h3.h3ToFeature(a)}");
      // final cell = h3Factory.getBaseCellNumber(a);
      // debugPrint("LocationAccuracy : Cell ${cell}");
    });
  }
  // Heuristic: map reported horizontal accuracy to an H3 resolution
  int _h3ResolutionForAccuracy(double? accM) {
    final acc = accM ?? 15.0;
    if (acc <= 8.0) return 12;
    if (acc <= 20.0) return 11;
    if (acc <= 35.0) return 10;
    if (acc <= 70.0) return 9;
    return 8; // coarse when accuracy is poor
    // Adjust to your needs; valid H3 resolutions: 0..15
  }


  @override
  Widget build(BuildContext context) {
    final s = _last;
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('Sensor Fused Location Demo')),
        body: Padding(
          padding: const EdgeInsets.all(16),
          child: s == null
              ? const Text('Waiting for data...')
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Lat: ${s.latitude.toStringAsFixed(6)}'),
                    Text('Lon: ${s.longitude.toStringAsFixed(6)}'),
                    Text('Acc: ${s.accuracyM.toStringAsFixed(1)} m'),
                    Text('Speed: ${s.speedMS.toStringAsFixed(2)} m/s'),
                    Text('Heading: ${s.headingDeg.toStringAsFixed(1)}°'),
                    // Text('Dead-reckoned: ${s.isDeadReckoned}'),
                    Text('H3 Index: $h3Index'),
                    Divider(),
                    Text('GEO JSON H3: ${jsonEncode(geoJson2H3String)}'),
                  ],
                ),
        ),
      ),
    );
  }
}

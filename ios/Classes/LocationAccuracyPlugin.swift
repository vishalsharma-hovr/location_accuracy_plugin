import Flutter
import UIKit
import CoreLocation
import CoreMotion

public class LocationAccuracyPlugin: NSObject, FlutterPlugin, CLLocationManagerDelegate, FlutterStreamHandler {
    private let TAG = "LocationAccuracy"
        // Flutter channels
    private var methodChannel: FlutterMethodChannel!
    private var gpsEventChannel: FlutterEventChannel!
    private var imuEventChannel: FlutterEventChannel!
    
    private var gpsEventSink: FlutterEventSink?
    private var imuEventSink: FlutterEventSink?
    
        // CoreLocation manager
    private let locationManager = CLLocationManager()
    
        // Configuration parameters with defaults
    private var gpsIntervalMs: Int = 1000
    private var fastestIntervalMs: Int = 250
    private var imuHz: Int = 50
    private var currentAccuracy: CLLocationAccuracy = kCLLocationAccuracyBest
    
    private var promoteHighAboveM: Double = 15.0
    private var demoteBalancedBelowM: Double = 12.0
    private var lastPrioritySwitchTs: TimeInterval = 0
    private var minSwitchIntervalMs: TimeInterval = 5000
    
    private var targetAccuracyM: Double = 10.0
    private var discardAccuracyAboveM: Double = 30.0
    private var settleSamples: Int = 3
    private var goodFixCount: Int = 0
    
        // Smoothing & Deadband
    private var smoothLat: Double?
    private var smoothLon: Double?
    private var lastEmittedLat: Double?
    private var lastEmittedLon: Double?
    private var deadbandMeters: Double = 1.5
    
        // Last good fix holding
    private var lastGoodLat: Double?
    private var lastGoodLon: Double?
    private var lastGoodTs: TimeInterval = 0
    private var goodHoldTimeoutMs: TimeInterval = 10000
    
        // Observation array for weighted smoothing
    private var observations = [CLLocation]()
    private let stateCountToKeep = 10
    
        // Motion manager for IMU data
    private var motionManager: CMMotionManager!
    
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = LocationAccuracyPlugin()
        
        instance.methodChannel = FlutterMethodChannel(name: "sensor_fused_location/methods", binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(instance, channel: instance.methodChannel)
        
        instance.gpsEventChannel = FlutterEventChannel(name: "sensor_fused_location/gps", binaryMessenger: registrar.messenger())
        instance.gpsEventChannel.setStreamHandler(instance)
        
        instance.imuEventChannel = FlutterEventChannel(name: "sensor_fused_location/imu", binaryMessenger: registrar.messenger())
        instance.imuEventChannel.setStreamHandler(instance)
        
        instance.locationManager.delegate = instance
        instance.locationManager.desiredAccuracy = kCLLocationAccuracyBest
        instance.motionManager = CMMotionManager()
    }
    
        // Method Call Handler
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
            case "initialize":
                let args = call.arguments as? [String: Any] ?? [:]
                currentAccuracy = (args["highAccuracy"] as? Bool ?? true) ? kCLLocationAccuracyBest : kCLLocationAccuracyHundredMeters
                gpsIntervalMs = args["gpsIntervalMs"] as? Int ?? 1000
                fastestIntervalMs = max(250, args["fastestIntervalMs"] as? Int ?? gpsIntervalMs / 2)
                imuHz = args["imuHz"] as? Int ?? 50
                targetAccuracyM = max(5.0, args["targetAccuracyM"] as? Double ?? 10.0)
                discardAccuracyAboveM = args["discardAccuracyAboveM"] as? Double ?? 30.0
                deadbandMeters = args["deadbandMeters"] as? Double ?? 1.5
                settleSamples = args["settleSamples"] as? Int ?? 3
                goodHoldTimeoutMs = TimeInterval(args["goodHoldTimeoutMs"] as? Int ?? 10_000)
                promoteHighAboveM = args["promoteHighAboveM"] as? Double ?? 15.0
                demoteBalancedBelowM = args["demoteBalancedBelowM"] as? Double ?? 12.0
                minSwitchIntervalMs = TimeInterval(args["minSwitchIntervalMs"] as? Int ?? 5000)
                
                locationManager.desiredAccuracy = currentAccuracy
                result(nil)
                
            case "requestPermissions":
                locationManager.requestWhenInUseAuthorization()
                result(nil)
                
            default:
                result(FlutterMethodNotImplemented)
        }
    }
    
        // MARK: - Flutter Stream Handler
    
    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        let channelName = (arguments as? String) ?? ""
        print("\(TAG): CHANNEL NAME -> \(channelName)")
        if channelName == "gps"  {
            gpsEventSink = events
            startLocationUpdates()
        }
        else if channelName == "imu" {
            imuEventSink = events
            print("\(TAG): IMU onListen -> starting IMU")
            startImu()
        }
        return nil
    }
    
    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        let channelName = (arguments as? String) ?? ""
        if channelName == "gps" {
            stopLocationUpdates()
            gpsEventSink = nil
        }
        else if channelName == "imu" {
            imuEventSink = nil
            print("\(TAG): IMU onCancel -> Stopping IMU")
            stopImu()
        }
        return nil
    }
    
        // MARK: - Location Management
    
    private func startLocationUpdates() {
        if CLLocationManager.locationServicesEnabled() {
            locationManager.startUpdatingLocation()
        } else {
            NSLog("Location services not enabled")
        }
    }
        // MARK: - IMU Handling
    private func startImu() {
        guard imuEventSink != nil else { return }
        
        if motionManager.isDeviceMotionAvailable {
            motionManager.deviceMotionUpdateInterval = 1.0 / Double(imuHz)
            motionManager.startDeviceMotionUpdates(to: OperationQueue.main) { [weak self] motion, error in
                guard let self = self, let motion = motion else { return }
                let ts = Int(Date().timeIntervalSince1970 * 1000)
                let map: [String: Any] = [
                    "ts": ts,
                    "ax": motion.userAcceleration.x,
                    "ay": motion.userAcceleration.y,
                    "az": motion.userAcceleration.z,
                    "gx": motion.rotationRate.x,
                    "gy": motion.rotationRate.y,
                    "gz": motion.rotationRate.z
                ]
                self.imuEventSink?(map)
            }
            print("\(TAG): Started IMU updates at \(imuHz)Hz")
        }
    }
    
    private func stopImu() {
        if motionManager.isDeviceMotionActive {
            motionManager.stopDeviceMotionUpdates()
        }
        imuEventSink = nil
        print("\(TAG): Stopped IMU updates")
    }
    private func stopLocationUpdates() {
        locationManager.stopUpdatingLocation()
        observations.removeAll()
        smoothLat = nil
        smoothLon = nil
        lastEmittedLat = nil
        lastEmittedLon = nil
        goodFixCount = 0
    }
    
        // MARK: - CLLocationManagerDelegate
    
    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        
        let acc = loc.horizontalAccuracy
        
            // Discard bad fixes
        guard acc.isFinite, acc > 0, acc <= discardAccuracyAboveM else {
            NSLog("Discarding fix: accuracy \(acc)")
            return
        }
        
            // Update goodFixCount for settling
        if acc <= targetAccuracyM {
            goodFixCount += 1
            lastGoodLat = loc.coordinate.latitude
            lastGoodLon = loc.coordinate.longitude
            lastGoodTs = loc.timestamp.timeIntervalSince1970 * 1000
        } else {
            goodFixCount = 0
        }
        
        let isGood = acc <= targetAccuracyM && goodFixCount >= settleSamples
        
            // Accuracy-weighted smoothing
        if smoothLat == nil || smoothLon == nil {
            smoothLat = loc.coordinate.latitude
            smoothLon = loc.coordinate.longitude
        } else {
            let weight: Double
            if acc <= 5.0 {
                weight = 0.6
            } else if acc <= 10.0 {
                weight = 0.4
            } else if acc <= 20.0 {
                weight = 0.25
            } else {
                weight = 0.15
            }
            smoothLat = (1.0 - weight) * smoothLat! + weight * loc.coordinate.latitude
            smoothLon = (1.0 - weight) * smoothLon! + weight * loc.coordinate.longitude
        }
        
            // Add to observations buffer
        observations.append(loc)
        if observations.count > stateCountToKeep {
            observations.removeFirst()
        }
        
        let filteredLocation = simpleHMMFilter(observations)
        
            // Hold last good fix if needed
        let now = loc.timestamp.timeIntervalSince1970 * 1000
        let canHoldGood = (lastGoodLat != nil) && (now - lastGoodTs) <= goodHoldTimeoutMs
        let outputLocation: (lat: Double, lon: Double) = !isGood && canHoldGood ?
        (lastGoodLat!, lastGoodLon!) :
        filteredLocation
        
        if let lastLat = lastEmittedLat, let lastLon = lastEmittedLon {
            let distance = distanceMeters(lat1: lastLat, lon1: lastLon, lat2: outputLocation.lat, lon2: outputLocation.lon)
            if distance < deadbandMeters {
                    // Ignore tiny movement
                return
            }
        }
        
        lastEmittedLat = outputLocation.lat
        lastEmittedLon = outputLocation.lon
        
        if let sink = gpsEventSink {
            let mapData: [String: Any] = [
                "lat": outputLocation.lat,
                "lon": outputLocation.lon,
                "acc": acc,
                "ts": Int(now),
                "isGood": isGood,
                "usingLastGood": !isGood && canHoldGood
            ]
            sink(mapData)
        }
    }
    
    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        NSLog("Location Manager failed: \(error.localizedDescription)")
    }
    
        // MARK: - Helpers
    
    private func simpleHMMFilter(_ locations: [CLLocation]) -> (Double, Double) {
        if locations.isEmpty {
            return (smoothLat ?? 0.0, smoothLon ?? 0.0)
        }
        
        var weightedLatSum = 0.0
        var weightedLonSum = 0.0
        var totalWeight = 0.0
        
        for loc in locations {
            let weight = loc.horizontalAccuracy > 0 ? 1.0 / loc.horizontalAccuracy : 1.0
            weightedLatSum += loc.coordinate.latitude * weight
            weightedLonSum += loc.coordinate.longitude * weight
            totalWeight += weight
        }
        
        let avgLat = weightedLatSum / totalWeight
        let avgLon = weightedLonSum / totalWeight
        
        NSLog("HMM filter: weighted average location = (\(avgLat), \(avgLon)) based on \(locations.count) points")
        return (avgLat, avgLon)
    }
    
    private func distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double) -> Double {
        let earthRadius = 6378137.0
        let dLat = (lat2 - lat1) * Double.pi / 180
        let dLon = (lon2 - lon1) * Double.pi / 180
        let a = sin(dLat/2) * sin(dLat/2) +
        cos(lat1 * Double.pi / 180) * cos(lat2 * Double.pi / 180) *
        sin(dLon/2) * sin(dLon/2)
        let c = 2 * atan2(sqrt(a), sqrt(1-a))
        return earthRadius * c
    }
}


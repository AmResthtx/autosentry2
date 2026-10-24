import CoreLocation
import Foundation

/// The backup way to wake up: iOS relaunches an app that watches for
/// "significant location changes" when the phone has moved a few hundred
/// meters, even after the app was closed. During a trip, regular updates keep
/// the app running so it can keep reading the truck. Main thread only.
final class LocationWake: NSObject, CLLocationManagerDelegate {
    static let shared = LocationWake()

    /// Called on every location update; TruckMonitor uses it to look for the adapter.
    var onMoved: (() -> Void)?

    private let manager = CLLocationManager()
    private var tripUpdatesOn = false

    private override init() {
        super.init()
        manager.delegate = self
        manager.pausesLocationUpdatesAutomatically = false
    }

    func requestPermission() {
        manager.requestAlwaysAuthorization()
    }

    /// Needs "Always" location to relaunch the app; harmless without it.
    func startWakeMonitoring() {
        guard CLLocationManager.significantLocationChangeMonitoringAvailable() else { return }
        manager.startMonitoringSignificantLocationChanges()
    }

    func startTripUpdates() {
        guard !tripUpdatesOn else { return }
        tripUpdatesOn = true
        manager.allowsBackgroundLocationUpdates = true
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        manager.startUpdatingLocation()
    }

    func stopTripUpdates() {
        guard tripUpdatesOn else { return }
        tripUpdatesOn = false
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        onMoved?()
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Usually a momentary lack of a fix; nothing to do.
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status: String
        switch manager.authorizationStatus {
        case .authorizedAlways: status = "Always (can wake the app)"
        case .authorizedWhenInUse: status = "While using (can't wake the app)"
        case .denied, .restricted: status = "Not allowed"
        case .notDetermined: return
        @unknown default: status = "Unknown"
        }
        EventLog.shared.add("Location permission", status)
    }
}

import ExternalAccessory
import Foundation
import UIKit

/// Watches for the MX+ to connect, sets it up, polls RPM once a second, and
/// logs every step, so the test shows exactly how far iOS let the app get
/// while it was in the background. Main thread only.
final class TruckMonitor: ObservableObject {
    static let shared = TruckMonitor()

    @Published private(set) var adapterName = "Not connected"
    @Published private(set) var state: TruckState = .unknown
    @Published private(set) var lastRpm: Double?

    private let link = AccessoryLink()
    private let log = EventLog.shared
    private var detector = KeyOnDetector()
    private var pollTimer: Timer?
    private var polling = false
    private var connectionID: Int?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var started = false

    func start() {
        guard !started else { return }
        started = true
        EAAccessoryManager.shared().registerForLocalNotifications()
        NotificationCenter.default.addObserver(self, selector: #selector(accessoryConnected(_:)),
                                               name: .EAAccessoryDidConnect, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(accessoryDisconnected(_:)),
                                               name: .EAAccessoryDidDisconnect, object: nil)
        link.onLost = { [weak self] in self?.linkLost("Lost the link to the adapter") }
        checkForAdapter(reason: "Adapter already connected")
    }

    /// Connects if the MX+ is there and no session is open yet.
    func checkForAdapter(reason: String) {
        guard !link.isOpen else { return }
        if let adapter = AccessoryLink.connectedAdapter() {
            connect(adapter, reason: reason)
        } else if reason == "Connect tapped" {
            log.add("No MX+ found", "Pair it in Settings > Bluetooth and turn the key on (press its button if it sleeps).")
        }
    }

    @objc private func accessoryConnected(_ note: Notification) {
        guard let accessory = note.userInfo?[EAAccessoryKey] as? EAAccessory,
              accessory.protocolStrings.contains(AccessoryLink.protocolString) else { return }
        connect(accessory, reason: "Adapter connected")
    }

    @objc private func accessoryDisconnected(_ note: Notification) {
        guard let accessory = note.userInfo?[EAAccessoryKey] as? EAAccessory,
              accessory.connectionID == connectionID else { return }
        linkLost("Adapter disconnected")
    }

    private func connect(_ accessory: EAAccessory, reason: String) {
        beginBackgroundTime()
        guard link.open(accessory) else {
            log.add("iOS wouldn't open the adapter",
                    "No session for \(AccessoryLink.protocolString). The app may need OBDLink's approval.")
            return
        }
        connectionID = accessory.connectionID
        adapterName = accessory.name
        detector = KeyOnDetector()
        state = .unknown
        log.add(reason, "\(accessory.name), firmware \(accessory.firmwareRevision)")
        initializeAdapter()
    }

    private func initializeAdapter() {
        var remaining = ELM327.initCommands
        func sendNext() {
            guard !remaining.isEmpty else {
                log.add("Adapter ready", "Reading RPM once a second")
                startPolling()
                return
            }
            let command = remaining.removeFirst()
            link.send(command, timeout: command == "ATZ" ? 3 : 1.5) { _ in sendNext() }
        }
        sendNext()
    }

    private func startPolling() {
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.poll()
        }
    }

    private func poll() {
        guard link.isOpen, !polling else { return }
        polling = true
        // The first request makes the adapter search for the 7.3L's protocol, which takes a few seconds.
        let timeout: TimeInterval = state == .unknown ? 10 : 2
        link.send(ELM327.rpmCommand, timeout: timeout) { [weak self] reply in
            guard let self = self else { return }
            self.polling = false
            let rpm = reply.flatMap(ELM327.rpm(from:))
            self.lastRpm = rpm
            if let change = self.detector.update(rpm: rpm) {
                self.report(change)
            }
            self.state = self.detector.state
        }
    }

    private func report(_ change: KeyOnDetector.Change) {
        switch change {
        case .keyOn:
            log.add("Key on", "The truck's computer answered")
            Notifier.post("Key on", "AutoSentry caught the key turning on.")
            LocationWake.shared.startTripUpdates()
        case .engineStarted(let rpm):
            log.add("Engine running", String(format: "%.0f rpm", rpm))
        case .engineStopped:
            log.add("Engine off, key still on")
        case .keyOff:
            log.add("Key off", "No answer from the truck 3 times in a row")
            LocationWake.shared.stopTripUpdates()
            endBackgroundTime()
        }
    }

    private func linkLost(_ why: String) {
        pollTimer?.invalidate()
        pollTimer = nil
        polling = false
        link.close()
        connectionID = nil
        adapterName = "Not connected"
        lastRpm = nil
        if state == .keyOn || state == .running {
            LocationWake.shared.stopTripUpdates()
        }
        state = .unknown
        log.add(why)
        endBackgroundTime()
    }

    /// Asks iOS for extra time when woken, so setup can finish before the app is suspended.
    private func beginBackgroundTime() {
        guard backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Connect to adapter") { [weak self] in
            self?.log.add("iOS ended the app's background time")
            self?.endBackgroundTime()
        }
    }

    private func endBackgroundTime() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }
}

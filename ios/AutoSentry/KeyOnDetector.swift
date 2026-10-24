import Foundation

enum TruckState: String {
    case unknown, keyOff, keyOn, running

    var label: String {
        switch self {
        case .unknown: return "Waiting for the truck"
        case .keyOff: return "Key off"
        case .keyOn: return "Key on, engine off"
        case .running: return "Engine running"
        }
    }
}

/// Tells key off, key on and engine running apart from RPM alone, the same
/// way the Android app's EngineWatch does: with the key on the truck's
/// computer answers (RPM 0 before cranking), and no answer means key off.
struct KeyOnDetector {
    static let runningRpm = 500.0
    /// Missed answers in a row before it counts as key off; one miss is often just noise.
    static let missesForKeyOff = 3

    enum Change: Equatable {
        case keyOn
        case engineStarted(rpm: Double)
        case engineStopped
        case keyOff
    }

    private(set) var state: TruckState = .unknown
    private var misses = 0

    /// - Parameter rpm: nil when the truck didn't answer.
    /// - Returns: what changed, if anything.
    mutating func update(rpm: Double?) -> Change? {
        guard let rpm = rpm else {
            misses += 1
            guard misses >= Self.missesForKeyOff else { return nil }
            let wasOn = state == .keyOn || state == .running
            state = .keyOff
            return wasOn ? .keyOff : nil
        }
        misses = 0
        let next: TruckState = rpm >= Self.runningRpm ? .running : .keyOn
        let previous = state
        state = next
        switch (previous, next) {
        case (.unknown, .keyOn), (.keyOff, .keyOn):
            return .keyOn
        case (.unknown, .running), (.keyOff, .running), (.keyOn, .running):
            return .engineStarted(rpm: rpm)
        case (.running, .keyOn):
            return .engineStopped
        default:
            return nil
        }
    }
}

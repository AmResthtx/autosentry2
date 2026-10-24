import Foundation
import UIKit

struct LoggedEvent: Codable, Identifiable {
    var id = UUID()
    let date: Date
    let title: String
    let detail: String
    /// True when iOS had the app in the background, the case this test is about.
    let inBackground: Bool

    var subtitle: String {
        var parts = [LoggedEvent.timeFormat.string(from: date)]
        if inBackground { parts.append("in background") }
        if !detail.isEmpty { parts.append(detail) }
        return parts.joined(separator: " · ")
    }

    static let timeFormat: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMM d, h:mm:ss a"
        return f
    }()
}

/// Every event with a timestamp, saved on the phone so a background wake-up
/// that nobody watched still shows up the next time the app is opened.
/// Used from the main thread only.
final class EventLog: ObservableObject {
    static let shared = EventLog()

    @Published private(set) var events: [LoggedEvent] = []

    private let fileURL: URL
    private let maxEvents = 500

    private init() {
        fileURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("wake-test-log.json")
        if let data = try? Data(contentsOf: fileURL),
           let saved = try? JSONDecoder().decode([LoggedEvent].self, from: data) {
            events = saved
        }
    }

    func add(_ title: String, _ detail: String = "") {
        let inBackground = UIApplication.shared.applicationState != .active
        events.insert(LoggedEvent(date: Date(), title: title, detail: detail, inBackground: inBackground), at: 0)
        if events.count > maxEvents {
            events.removeLast(events.count - maxEvents)
        }
        save()
    }

    func clear() {
        events = []
        save()
    }

    /// Oldest first, for sharing the results.
    var asText: String {
        events.reversed().map { "\($0.title) — \($0.subtitle)" }.joined(separator: "\n")
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(events) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }
}

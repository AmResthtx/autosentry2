import SwiftUI

/// The wake test's one screen: what the truck is doing now, the setup
/// buttons, and every logged event, newest first.
struct ContentView: View {
    @ObservedObject private var monitor = TruckMonitor.shared
    @ObservedObject private var log = EventLog.shared

    var body: some View {
        NavigationStack {
            List {
                Section("Truck") {
                    LabeledContent("Adapter", value: monitor.adapterName)
                    LabeledContent("State", value: monitor.state.label)
                    LabeledContent("RPM", value: monitor.lastRpm.map { String(format: "%.0f", $0) } ?? "—")
                }

                Section {
                    Button("Allow notifications") { Notifier.requestPermission() }
                    Button("Allow location") { LocationWake.shared.requestPermission() }
                    Button("Connect now") { monitor.checkForAdapter(reason: "Connect tapped") }
                } header: {
                    Text("Setup")
                } footer: {
                    Text("Pair the OBDLink MX+ in Settings > Bluetooth first. For the test, set location to "
                         + "Always, lock the phone, then turn the key on. Don't swipe the app closed: iOS "
                         + "won't wake an app you've force-quit.")
                }

                Section("Events, newest first") {
                    if log.events.isEmpty {
                        Text("Nothing yet").foregroundStyle(.secondary)
                    }
                    ForEach(log.events) { event in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(event.title).font(.headline)
                            Text(event.subtitle).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("Key-on wake test")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Clear") { log.clear() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    ShareLink(item: log.asText) {
                        Label("Share log", systemImage: "square.and.arrow.up")
                    }
                }
            }
        }
    }
}

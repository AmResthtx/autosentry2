import SwiftUI
import UIKit
import UserNotifications

@main
struct AutoSentryApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

/// Runs for every launch, including the ones iOS starts in the background,
/// which is what the wake test is measuring.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        let log = EventLog.shared
        if launchOptions?[.location] != nil {
            log.add("iOS started AutoSentry in the background", "Because the phone moved")
        } else if application.applicationState == .background {
            log.add("iOS started AutoSentry in the background")
        } else {
            log.add("App opened")
        }

        UNUserNotificationCenter.current().delegate = self
        LocationWake.shared.onMoved = {
            TruckMonitor.shared.checkForAdapter(reason: "Found the adapter after the truck moved")
        }
        LocationWake.shared.startWakeMonitoring()
        TruckMonitor.shared.start()
        return true
    }

    // Show the "Key on" notification even when the app is open.
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}

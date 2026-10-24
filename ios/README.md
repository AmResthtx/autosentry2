# AutoSentry for iPhone: key-on wake test

The first step of the iPhone version. Before building the whole app, this answers the one question that decides whether an iPhone version can work like the Android one:

**Will iOS wake AutoSentry by itself when you turn the key, with the phone locked and the app not open?**

The app connects to the OBDLink MX+, reads RPM once a second, and logs every step with a timestamp. When it sees the key come on, it sends a notification, so you know it worked without opening it.

## Why the MX+, and what's unknown

- On an iPhone, the MX+ connects as an Apple-certified (MFi) accessory under the protocol `com.obdlink`. The app talks to it the same way the Android app does: plain ELM327 commands, protocol auto-detect (the 7.3L is J1850 PWM), then RPM.
- The OBDLink CX would be easier on an iPhone (Bluetooth LE), but OBDLink says it doesn't work on Ford vehicles before 2008, so it can't read the 7.3L.
- **Unknown 1:** whether a personal build can open the MX+ at all. If the log says "iOS wouldn't open the adapter", the app needs OBDLink's approval for `com.obdlink`. That approval is needed for the App Store anyway.
- **Unknown 2:** whether iOS wakes the app when the MX+ connects at key-on. That's the main test.
- **Backup:** if it doesn't wake on key-on, the app also asks iOS to wake it when the truck starts moving (location set to "Always"). That would miss the crank but still catch the trip.

## Build it on the Mac (once)

1. Install **Xcode** from the Mac App Store and open it once so it finishes installing.
2. Install Homebrew if the Mac doesn't have it (see brew.sh), then in Terminal:
   ```bash
   brew install xcodegen
   ```
3. Get the code: clone the repository and switch to the `feat/ios-wake-test` branch.
4. Generate the Xcode project and open it:
   ```bash
   cd ios && xcodegen && open AutoSentry.xcodeproj
   ```
5. In Xcode: **Settings > Accounts**, add your Apple ID (a free one works for your own phone).
6. Click the **AutoSentry** project, then the **AutoSentry** target, then **Signing & Capabilities**. Set **Team** to your Personal Team. If Xcode says the bundle ID is taken, change it to something like `com.yourname.autosentry`.
7. Plug in the iPhone 16 and tap **Trust** on the phone. On the phone, turn on **Settings > Privacy & Security > Developer Mode** (it restarts).
8. Pick the iPhone at the top of Xcode and press **Run** (the play button).
9. The first time, iOS blocks the app: on the phone go to **Settings > General > VPN & Device Management**, tap your Apple ID and **Trust**.

With a free Apple ID the app stops opening after 7 days. Plug the phone into the Mac and press Run again to renew it. A paid Apple Developer account ($99/year) removes that limit and allows TestFlight; it isn't needed for this test.

Tests: in Xcode press **Cmd+U**. They check reading the adapter's replies and telling key off, key on and running apart.

## Run the test in the truck

1. **Settings > Bluetooth**: pair the MX+ (press its button so it shows up).
2. Open AutoSentry. Tap **Allow notifications**, then **Allow location** and choose **Always** when iOS asks (it may ask a second time later; pick "Change to Always Allow").
3. Key on: the app should show **Adapter connected**, **Adapter ready**, then **Key on**. Start the engine: **Engine running**. That proves the connection works.
4. **The real test.** Key off, wait until the log says **Key off** or **Adapter disconnected**, press the side button to lock the phone, and leave it a few minutes. Don't swipe the app closed: iOS won't wake an app you've force-quit.
5. Turn the key on and wait about 30 seconds without touching the phone. A **Key on** notification means iOS woke the app at key-on.
6. Also try: driving off without touching the phone, and the same test after the phone has been locked overnight.
7. Open the app and tap **Share log** (top right) to send the results.

What the log tells you:

| Log line | Meaning |
|---|---|
| "in background" next to an event | It happened while the phone was locked |
| iOS started AutoSentry in the background | iOS relaunched the app by itself |
| Because the phone moved | The location backup did the waking |
| iOS wouldn't open the adapter | The app needs OBDLink's approval for `com.obdlink` |
| iOS ended the app's background time | iOS suspended the app before it finished |

## What's in here

| File | What it does |
|---|---|
| `project.yml` | The project settings XcodeGen turns into `AutoSentry.xcodeproj` (not checked in) |
| `AutoSentry/AccessoryLink.swift` | Talks to the MX+ over `com.obdlink`, one ELM327 command at a time |
| `AutoSentry/ELM327.swift` | The same commands and reply parsing as the Android app |
| `AutoSentry/KeyOnDetector.swift` | Key off / key on / running from RPM, as the Android app's EngineWatch does |
| `AutoSentry/TruckMonitor.swift` | Connects when the MX+ appears, polls RPM, logs and notifies |
| `AutoSentry/LocationWake.swift` | Wakes the app when the truck moves; keeps it awake during a trip |
| `AutoSentry/EventLog.swift` | The timestamped log, saved on the phone |
| `AutoSentry/ContentView.swift` | The one screen |

Nothing leaves the phone: no account, no server. Location and the log stay on the iPhone unless you share the log yourself.

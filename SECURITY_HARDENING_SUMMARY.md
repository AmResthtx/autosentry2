# Security Hardening Summary

`docs/RED_TEAM_ANALYSIS.md` covers diagnostic-logic accuracy (thresholds, confidence scoring), not app security, so it has no security findings to close here. Its items remain product follow-ups.

## Fixed
| Change | Risk addressed |
|---|---|
| `allowBackup="false"` | Prevents `adb backup`/cloud backup extraction of trip, location-derived and adapter data |
| Removed unused `INTERNET` permission; `usesCleartextTraffic="false"` + `network_security_config.xml` | App has no network code; permission removal shrinks attack surface and Data Safety scope. Cleartext is blocked if networking is added later |
| `BLUETOOTH`/`BLUETOOTH_ADMIN` limited to `maxSdkVersion="30"` | Legacy permissions not needed on API 31+ (`BLUETOOTH_CONNECT/SCAN` used) |
| FileProvider paths narrowed from all of `filesDir` (`.`) to `logs/` and `maintenance_photos/`; debug log moved to `files/logs/` | A granted URI could previously expose any private file in app storage (e.g. reports) |
| `BootReceiver` validates the intent action | Exported receiver could be started with arbitrary/spoofed intents |
| Release: `isDebuggable=false`, minify + resource shrinking, R8 strips `Log.v/d/i` | Non-debuggable release, reduced reverse-engineering and logcat exposure |

## Audit results (no change needed)
- No hardcoded keys/tokens/secrets found.
- All activities/service/provider are `exported="false"` except the launcher. The two receivers must stay exported to get system broadcasts; `BluetoothConnectionReceiver` already only acts on the saved adapter address.
- No deep links or WebViews.

## Remaining risks / TODO
- Release is still signed with the **debug** key; create a real upload key (Play App Signing) before closed testing.
- `AppSettings` uses plain SharedPreferences for the adapter MAC/name. With backup disabled this is private to the app; migrating to Keystore-backed `EncryptedSharedPreferences` (androidx.security-crypto) is a follow-up.
- `AppLog` still writes the adapter name/error messages to an app-private file; the debug log can be shared by the user via FileProvider, so review its content before release.
- `SCHEDULE_EXACT_ALARM`, `ACCESS_FINE_LOCATION`/background services need Play permission declarations.
- Minification has not been exercised on a device; test the release build (Room, reflection).
- Dependency versions are current as of the repo; run a dependency scanner (e.g. Dependabot) in CI.

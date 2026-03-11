# FieldScan — Project Rules

## What This Is
Fork of WiGLE WiFi Wardriving (BSD-3 license). Scan engine, DB, map, and CSV export retained.
WiGLE upload/auth removed. Auto-sync to PC on home WiFi added. Custom dark + heatmap theme.

## Package
- Original: `net.wigle.wigleandroid`
- FieldScan: `com.fieldscan.app`
- App ID: `com.fieldscan.app`
- Build flavor: `foss` (NOT `google`)

## Files Deleted (Do Not Re-Add)
- `ObservationUploader.java` — replaced by AutoSyncManager
- `WiGLEApiManager.java`
- `BasicAuthInterceptor.java`
- `ActivateActivity.java`
- `RegistrationActivity.java`

## Build Rules
- Always use `foss` flavor: `./gradlew assembleFossDebug`
- APK output: `app/build/outputs/apk/foss/debug/app-foss-debug.apk`
- Java 17 (bundled with Android Studio — do NOT use system Java)
- Min SDK: 24, Target SDK: 36

## Map
- Use `FossMappingFragment` only — NOT `MappingFragment` (requires Google Maps API)
- MapLibre tiles: OSM standard or demotiles.maplibre.org

## Auto-Sync (AutoSyncManager.java)
- BroadcastReceiver on `WifiManager.NETWORK_STATE_CHANGED_ACTION`
- Compare SSID to SharedPreferences key `home_wifi_ssid`
- POST files to `http://[server_ip]:[server_port]/upload`
- Track synced files in SharedPreferences (filename → timestamp)
- Default port: 8765

## PC Receiver
- Script: `G:/ai/wardriving/receiver.py`
- Incoming files: `G:/ai/wardriving/incoming/`
- Start: `python G:/ai/wardriving/receiver.py`

## Theme
- Force dark: `Theme.MaterialComponents.DayNight.NoActionBar`
- Accent: `#00BCD4` cyan
- Background: `#121212`
- Network colors: open=`#00E676`, WEP=`#FFEB3B`, WPA/WPA2=`#FF7043`, WPA3=`#F44336`

## Tab S7 Setup (One-Time)
1. Enable Developer Options (tap Build Number 7x)
2. Developer Options → disable "Wi-Fi scan throttling"
3. Battery → FieldScan → Unrestricted
4. Permissions → Location → Allow all the time
5. Battery → Background usage limits → FieldScan → Never sleeping

## CSV Export Format
`MAC, SSID, AuthMode, FirstSeen, Channel, RSSI, CurrentLatitude, CurrentLongitude, AltitudeMeters, AccuracyMeters, Type`
Transform in receiver.py if schema change needed (no app rebuild required).

# FieldScan - Session Context

## Last Updated: 2026-03-11

## Current State
- Fork of WiGLE WiFi Wardriving, rebranded to FieldScan
- Target devices: Samsung Tab S7 (USB adb, serial R9TR30AEGAJ) + phone (WiFi adb 192.168.1.116:39885)
- FOSS map (MapLibre/OSM) working — crash fix deployed (Chip→ToggleButton)
- Auto-sync to PC on home WiFi: WORKING (tablet + phone both confirmed syncing)
- SharedPreferences: renamed "WiglePrefs" → "FieldScanPrefs" with migration
- Cleartext HTTP enabled in manifest (`android:usesCleartextTraffic="true"`)
- APK built and installed on both devices as of this session (19:xx build, crash-fixed)

## Active Work
This session: fixed map crash (Chip widgets require MaterialComponents theme, app uses AppCompat).
Fix: replaced both `com.google.android.material.chip.Chip` in foss_map.xml with `ToggleButton`.
Updated FossMappingFragment.java import + type. Committed `a1795205`, built via gradlew, installed both devices.

Also: merged new large scan file (7,039 rows, 4,789 new). Master now 7,491 unique observations.

## Key Design Decisions
- **SilentExporter**: Bypass ObservationUploader entirely — no BackgroundGuiHandler, no share sheet
- **Export path**: `FileUtility.getUploadFilePath(context)` — NOT getExternalFilesDir() (hasSD() false on Android 11+)
- **Chip→ToggleButton**: App uses Theme.AppCompat.DayNight — MaterialComponents Chip widgets crash. Use plain ToggleButton for filter chips.
- **Gradlew build**: Run from `G:/ai/fieldscan/` with `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`. Android Studio incremental build sometimes skips recompile — use `./gradlew clean assembleDebug` from bash.
- **APK path**: `wiglewifiwardriving/build/outputs/apk/debug/wiglewifiwardriving-debug.apk`
- **Dedup key**: `(MAC, FirstSeen, CurrentLatitude, CurrentLongitude)` — preserves multi-location observations
- **Master CSV**: `G:/ai/wardriving/merged/merged_master.csv` — persistent, append-only across runs

## Recent Changes (this session)
- `foss_map.xml` — Chip → ToggleButton for both filter chips (TD-20 fix)
- `FossMappingFragment.java` — import + type changed to ToggleButton
- `G:/ai/wardriving/merge.py` — rewritten: persistent append to merged_master.csv, NUL-tolerant
- `G:/ai/wardriving/receiver.py` — auto-deduplicates into merged_master.csv on every upload
- `G:/ai/wardriving/map.html` — regenerated with 7,490 points, color-coded by crypto type
- Committed: a1795205 (crash fix), pushed to foss-main

## Blockers / Open Questions
- **Cell tower data**: Phone needs "Allow all the time" location permission in Settings → Apps → FieldScan → Permissions → Location. Zero cell towers in data so far.
- **TD-25 (IMSI catcher)**: Requires OpenCelliD API key (free). Not started.

## Next Steps
1. Grant "Allow all the time" location on phone — confirm cell towers appear in scans
2. Pick next TD: TD-25 (IMSI catcher), TD-26 (BLE deep decode), TD-27 (WiFi RTT), TD-28 (cell deep data), TD-29 (GPS track overlay), TD-10 (settings UI)
3. TD-29 is pure PC Python — no rebuild needed, good quick win

## Environment
- Working directory: `G:/ai/fieldscan`
- Git repo: https://github.com/ghighcove/fieldscan (branch: foss-main)
- Build: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew clean assembleDebug` from `G:/ai/fieldscan/`
- ADB: `C:/Users/ghigh/AppData/Local/Android/Sdk/platform-tools/adb.exe`
- Tablet serial: R9TR30AEGAJ | Phone: 192.168.1.116:39885
- PC receiver: `python G:/ai/wardriving/receiver.py` (Flask, port 8765)
- Incoming: `G:/ai/wardriving/incoming/` | Master: `G:/ai/wardriving/merged/merged_master.csv`
- Map: `G:/ai/wardriving/map.html`

## Quick Reference
- Tech debt: `tasks/tech_debt.md`
- No project CLAUDE.md yet
- Key Java files:
  - `wiglewifiwardriving/src/main/java/net/wigle/wigleandroid/FossMappingFragment.java`
  - `wiglewifiwardriving/src/main/java/net/wigle/wigleandroid/FossMapRender.java`
  - `wiglewifiwardriving/src/main/java/net/wigle/wigleandroid/WigleService.java`
  - `wiglewifiwardriving/src/main/java/net/wigle/wigleandroid/MainActivity.java`
  - `wiglewifiwardriving/src/main/java/net/wigle/wigleandroid/AutoSyncManager.java`
  - `wiglewifiwardriving/src/main/java/net/wigle/wigleandroid/ProbeManager.java`

## Session Debrief
**Corrections**: Assumed "MBIGH-5G Guest" was user's network — it wasn't. Don't assume ownership of discovered networks.
**Frustration signals**: "something is wrong. did you really change the code? this is the first time we've had this problem. why?" — APK not updating after fix because Android Studio incremental build cached old output. Caused confusion about whether code was actually changed.
**Patterns**: When APK timestamp doesn't update after code change, run `./gradlew clean assembleDebug` from bash directly — don't rely on Android Studio's incremental build or ask user to figure it out.

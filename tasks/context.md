# FieldScan - Session Context

## Last Updated: 2026-03-13

## Current State
All six specced FieldScan features are built, installed, and secured:
- **DeviceClassifier.java** — three-tier classifier (OUI static + synced familynames + DB self-derived)
- **ClassesFragment** — tab strip (All/Medical/Car/Accessories/Family/Open WiFi/Phone), live device list, tap → Finder
- **FinderFragment** — RSSI fuel gauge + sparkline + earbud mode (all ACCESSORIES ranked by RSSI)
- **SparklineView** — custom Canvas view, 30-reading history, dBm grid lines
- **Map class-color toggle** — ImageButton in foss_map.xml, FossMapRender.classColorMode flag
- **AutoSync** — uploads CSV exports to receiver.py on home WiFi connect, fetches /config after sync
- **Auth hardening** — X-FieldScan-Key header on all app→server requests; receiver.py rejects without key

## Active Work
This session: auth hardening (shared secret header), install to tablet + phone, receiver.py restart.

## Key Design Decisions
- API key: `fe921262089ddb97dfe0e56e1a8ca065` — hardcoded constant in AutoSyncManager.java and receiver.py
- receiver.py port: **8765** (not 8080 — 8080 is search_proxy.py)
- networkCache type: `net.wigle.wigleandroid.model.ConcurrentLinkedHashMap<String, Network>` (not com.googlecode)
- MaterialComponents restriction: use RadioGroup+RadioButton for tabs (Chip crashes with AppCompat.DayNight)
- Color resources in `res/color/` not `res/drawable/` (tab_text_selector.xml)
- SparklineView as top-level class file (not inner class — XML can't reference inner class)
- Phone detection: locally-administered MAC bit (2nd hex char = 2/6/a/e) on BLE/BT type
- Three-tier DeviceClassifier: Tier1=DB rawQuery (5+ obs SSIDs), Tier2=synced fieldscan_config.json, Tier3=static OUI/keyword sets
- PC operator tagging: users.json date_prefix → operator, consumed by make_map.py

## Recent Changes (this session)
### Android (G:/ai/fieldscan, branch: foss-main)
- `AutoSyncManager.java`: added `API_KEY` constant, `X-FieldScan-Key` header on uploadFile() and fetchConfig()
- Commits: `2bff4c54` (all 6 features), `4b7fd1c6` (auth key)

### PC Tools (G:/ai/wardriving, branch: master)
- `receiver.py`: added `API_KEY`, `_check_key()` helper, key check on /upload and /config; fixed `→` Unicode crash in startup print
- `make_map.py`: fully rewritten with operator coloring, users.json support
- `users.json`: operator date_prefix mappings (Glenn/Nikki/Lisa)
- Commits: `65f92eb` (features), `3948768` (auth key)

## APK Status
- Build: `G:/ai/fieldscan/wiglewifiwardriving/build/outputs/apk/debug/wiglewifiwardriving-debug.apk`
- Built: Mar 13 ~21:30 with auth key
- Installed: Tablet (R9TR30AEGAJ) ✅, Phone (192.168.1.116) ✅

## Blockers / Open Questions
- Nikki's OnePlus Nord Buds fingerprint — confirm next session she's home
- DeviceClassifier DB refresh path (refreshFromDB → DatabaseHelper.getDB()) not tested end-to-end
- Phone ADB port changes on each reconnect — just run `adb connect 192.168.1.116:<port>` when it shows in dev options

## Next Steps
1. Do a real scan session with Classes tab and Finder to validate live behavior
2. Test /config fetch: after sync, check app filesDir for fieldscan_config.json
3. Confirm DeviceClassifier is classifying known devices correctly (earbuds, car BT)
4. Consider making API_KEY configurable via Settings UI (currently hardcoded)

## Environment
- receiver.py: running on 192.168.1.69:8765 (background, PID varies)
- JAVA_HOME: `C:\Program Files\Android\Android Studio\jbr` (required for Gradle build)
- Gradle: `cd G:/ai/fieldscan && powershell -Command "& '.\gradlew.bat' ':wiglewifiwardriving:assembleDebug'"`
- ADB: `C:\Users\ghigh\AppData\Local\Android\Sdk\platform-tools\adb.exe`

## Quick Reference
- Android project: `G:/ai/fieldscan/wiglewifiwardriving/src/main/java/net/wigle/wigleandroid/`
- PC tools: `G:/ai/wardriving/`
- users.json: `G:/ai/wardriving/users.json`
- fieldscan repo: https://github.com/ghighcove/fieldscan (branch: foss-main)
- pc-tools repo: https://github.com/ghighcove/fieldscan-pc-tools (branch: master)
- Working directory: `G:/ai/fieldscan`

# FieldScan - Session Context

## Last Updated: 2026-03-12

## Current State
- Android app: crash-fixed, installed on tablet (R9TR30AEGAJ) and phone (192.168.1.116:39885)
- Map opens without crash — ToggleButton fix deployed
- Auto-sync working on both devices
- PC dataset: **10,973 rows** in merged_master.csv | **4,976 unique devices** in dedup_master.csv
- Coverage: March 9, 10, 11 walks including Schoenborn, Rhea, surrounding streets

## Active Work
This session:
1. Fixed map crash (Chip→ToggleButton), built via gradlew, installed both devices
2. Analyzed big walk (7,039 rows) — found unoccupied houses, BLE devices, IoT in setup mode
3. Discovered 4 Telegram files with earlier walks (March 9/10/11 AM) were never ingested — added them
4. Master grew from 7,491 → 10,973 rows after Telegram import
5. Built dedup_master.csv — one row per unique MAC with best RSSI location, obs count, date range

## Key Design Decisions
- **Chip→ToggleButton**: App uses Theme.AppCompat.DayNight — MaterialComponents crashes. Use ToggleButton for filter chips permanently.
- **Gradlew build**: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew clean assembleDebug` from `G:/ai/fieldscan/`. Android Studio incremental build unreliable — always use gradlew clean.
- **APK path**: `wiglewifiwardriving/build/outputs/apk/debug/wiglewifiwardriving-debug.apk`
- **Dedup key in master**: `(MAC, FirstSeen, Lat, Lon)` — preserves multi-location observations for movement tracking
- **dedup_master.csv**: MAC-level dedup (one row per device), best RSSI position, ObsCount, FirstSeen/LastSeen, AltSSIDs
- **Data source priority**: Check `D:/Downloads/Telegram Desktop/` for WiGLE exports before assuming all data is ingested

## Recent Changes (this session)
- `foss_map.xml` + `FossMappingFragment.java` — Chip→ToggleButton crash fix
- `G:/ai/wardriving/incoming/` — added 4 Telegram files (WigleWifi_20260309, _20260310, _20260311x2)
- `G:/ai/wardriving/merged/merged_master.csv` — 7,491 → 10,973 rows
- `G:/ai/wardriving/merged/dedup_master.csv` — NEW: 4,976 unique devices
- `G:/ai/wardriving/map.html` — regenerated with full deduplicated dataset
- Committed + pushed: a1795205 (crash fix), 6e17338f (context)

## Blockers / Open Questions
- **Cell tower data**: Phone needs "Allow all the time" location — zero cell towers in data so far
- **More Telegram data?**: Check if older WiGLE exports exist elsewhere (Downloads, phone, original WiGLE app)
- **receiver.py not running**: Was it restarted after the rewrite? Future syncs need it running

## Next Steps
1. Check for any other WiGLE data sources (older Telegram messages, Downloads folder, phone storage)
2. Grant phone "Allow all the time" location — cell tower data will start appearing
3. Pick next TD: TD-29 (GPS track overlay — pure Python, no rebuild) is quickest win
4. Consider: integrate dedup_master.csv into map as the default view

## Environment
- Working directory: `G:/ai/fieldscan`
- Git repo: https://github.com/ghighcove/fieldscan (branch: foss-main)
- Build: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew clean assembleDebug` from `G:/ai/fieldscan/`
- ADB: `C:/Users/ghigh/AppData/Local/Android/Sdk/platform-tools/adb.exe`
- Tablet: R9TR30AEGAJ (USB) | Phone: 192.168.1.116:39885 (WiFi adb)
- PC receiver: `python G:/ai/wardriving/receiver.py` (Flask, port 8765) — needs manual start
- Incoming: `G:/ai/wardriving/incoming/`
- Master CSV: `G:/ai/wardriving/merged/merged_master.csv`
- Dedup master: `G:/ai/wardriving/merged/dedup_master.csv`
- Map: `G:/ai/wardriving/map.html`
- Telegram exports: `D:/Downloads/Telegram Desktop/`

## Quick Reference
- Tech debt: `tasks/tech_debt.md`
- No project CLAUDE.md
- Key Java: `FossMappingFragment.java`, `FossMapRender.java`, `WigleService.java`, `MainActivity.java`, `AutoSyncManager.java`, `ProbeManager.java`

## Session Debrief
**Corrections**:
- "MBIGH-5G Guest is not mine" — don't assume ownership of discovered networks based on name similarity
- "I know for a fact I walked down Schoenborn multiple times" — I concluded data was missing due to no prior walks; real cause was Telegram files never ingested. Should have checked all data sources before concluding data didn't exist.
- "you didn't remember we ingested these?" — Telegram files were known but not tracked as a data source. Need to remember all data sources, not just incoming/.

**Patterns**: Before concluding data is missing, audit ALL known data sources. Don't assume incoming/ is the complete picture. When user asserts data exists, trust them and look harder.

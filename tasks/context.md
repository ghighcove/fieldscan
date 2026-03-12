# FieldScan - Session Context

## Last Updated: 2026-03-12

## Current State
- Android app: crash-fixed, installed on tablet (R9TR30AEGAJ) and phone (192.168.1.116:39885)
- Map opens without crash — ToggleButton fix deployed
- Auto-sync working on both devices
- PC dataset: **13,193 rows** in merged_master.csv | **5,954 unique devices** in dedup_master.csv
- Coverage: March 9–12 walks including Schoenborn, Rhea, and surrounding Northridge corridor streets
- DevJournal: fixed (nested-session CLAUDECODE bug) and backfilled March 9/11/12

## Active Work
Latest sync (20260312_121518): 2,767 rows, 2,220 new. BLE-heavy (2,321 BLE / 433 WiFi). 0 new MACs — same corridor covered again.

Notable BLE devices in dataset:
- **SCHLAGE000E75C9** — Schlage smart lock broadcasting BLE (5 obs)
- **Ring 10** — Active configured Ring device, index 10 in a multi-camera install (10 obs)
- **DMRRBA-001 / DMRRBA-007** — Dormakaba commercial access control across two sessions
- **ResMed 276340 + ResMed 762727** — Two CPAP machines, two different users
- **PROV_494980F** — Device in provisioning mode

## Key Design Decisions
- **Chip→ToggleButton**: App uses Theme.AppCompat.DayNight — MaterialComponents crashes. Permanent.
- **Gradlew build**: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew clean assembleDebug` from `G:/ai/fieldscan/`. Android Studio incremental build unreliable — always use gradlew clean.
- **APK path**: `wiglewifiwardriving/build/outputs/apk/debug/wiglewifiwardriving-debug.apk`
- **Dedup key in master**: `(MAC, FirstSeen, Lat, Lon)` — preserves multi-location observations
- **dedup_master.csv**: MAC-level dedup, one row per device, best RSSI position, ObsCount, FirstSeen/LastSeen
- **Data source audit**: Always check `D:/Downloads/Telegram Desktop/` for missing WiGLE exports before concluding data is absent

## Recent Changes (this session)
- Ingested 20260312_121518 (2,767 rows, +2,220 new)
- merged_master.csv: 10,973 → 13,193 rows
- dedup_master.csv: 4,976 → 5,954 unique devices
- `G:/ai/_data/run_headless.py` — patched to strip CLAUDECODE env var (fixes journal nested-session block)
- DevJournal-Daily scheduled task: re-enabled after 4 missed nights
- Dev journal backfilled: March 9, 11, 12 entries written and pushed to Google Docs

## Blockers / Open Questions
- **Cell tower data**: Phone needs "Allow all the time" location — zero cell towers in data so far
- **receiver.py**: Running (PID 29524, started Mar 11 9 PM) — new syncs will auto-ingest
- **0 new MACs in today's scan**: Dataset stable for this corridor; need to extend walk coverage for new data

## Next Steps
1. Walk new streets (north of current bounding box, or east past -118.537) to add new devices
2. Grant phone "Allow all the time" location for cell tower data
3. TD-29 (GPS track overlay — pure Python, no rebuild, quick win)
4. SCHLAGE BLE device — note GPS coords, research Schlage BLE protocol for enumeration
5. TD-25 (IMSI catcher) or TD-26 (BLE deep decode) for next app build

## Environment
- Working directory: `G:/ai/fieldscan`
- Git repo: https://github.com/ghighcove/fieldscan (branch: foss-main)
- Build: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew clean assembleDebug` from `G:/ai/fieldscan/`
- ADB: `C:/Users/ghigh/AppData/Local/Android/Sdk/platform-tools/adb.exe`
- Tablet: R9TR30AEGAJ (USB) | Phone: 192.168.1.116:39885 (WiFi adb)
- PC receiver: PID 29524 running — `G:/ai/wardriving/receiver.py` (Flask, port 8765)
- Incoming: `G:/ai/wardriving/incoming/`
- Master CSV: `G:/ai/wardriving/merged/merged_master.csv` (13,193 rows)
- Dedup master: `G:/ai/wardriving/merged/dedup_master.csv` (5,954 devices)
- Map: `G:/ai/wardriving/map.html`
- Telegram exports: `D:/Downloads/Telegram Desktop/` — check here for any missing sessions

## Quick Reference
- Tech debt: `tasks/tech_debt.md`
- No project CLAUDE.md
- Key Java: `FossMappingFragment.java`, `FossMapRender.java`, `WigleService.java`, `MainActivity.java`, `AutoSyncManager.java`, `ProbeManager.java`

## Session Debrief
**Corrections**:
- "MBIGH-5G Guest is not mine" — don't assume network ownership from name similarity
- "I know for a fact I walked down Schoenborn" / "you didn't remember we ingested these?" — before concluding data is missing, audit ALL sources including Telegram Desktop. Trust the user when they assert data exists.
**Patterns**: Data completeness assumption bias — I concluded "first walk was stationary" when the real issue was missing Telegram files. Rule: when user asserts prior data exists, exhaust all source locations before concluding otherwise.

# FieldScan — Task Tracker

## Phase 1 — Environment Setup
- [x] Fork wiglenet/wigle-wifi-wardriving → ghighcove/fieldscan
- [x] Clone to G:/ai/fieldscan
- [ ] Install Android Studio (Ladybug 2024.2.x) — MANUAL: user must do this
- [ ] Install Android SDK Platform 36, Build Tools 35+ — via Android Studio SDK Manager
- [ ] Open in Android Studio, let Gradle sync, verify `foss` flavor builds

## Phase 2 — Strip WiGLE Upload/Auth
- [ ] Delete: ObservationUploader.java
- [ ] Delete: WiGLEApiManager.java
- [ ] Delete: BasicAuthInterceptor.java
- [ ] Delete: ActivateActivity.java
- [ ] Delete: RegistrationActivity.java
- [ ] Edit WigleService.java — remove upload notification + intents
- [ ] Edit MainActivity.java — remove upload menu items + dialogs
- [ ] Edit SettingsFragment.java — remove WiGLE account settings
- [ ] Edit AndroidManifest.xml — remove deleted activities, upload receivers
- [ ] Edit build.gradle — remove play-services-maps, ML Kit

## Phase 3 — Rename to FieldScan
- [ ] Refactor package net.wigle.wigleandroid → com.fieldscan.app (Android Studio refactor)
- [ ] Update app name string to "FieldScan"
- [ ] Update applicationId in build.gradle
- [ ] Generate new launcher icon (radar/wave)
- [ ] Remove WiGLE logo assets from res/drawable

## Phase 4 — FOSS Map
- [ ] Set FossMappingFragment as default map tab
- [ ] Delete/disable MappingFragment.java
- [ ] Configure MapLibre OSM tile URL
- [ ] Verify map renders on Tab S7

## Phase 5 — Visual Theme
- [ ] Force dark theme in res/values/themes.xml
- [ ] Apply accent color #00BCD4
- [ ] Update network marker colors in MapRender.java (or equivalent)
- [ ] Implement heatmap gradient (blue→cyan→green→yellow→red)

## Phase 6 — Auto-Sync
- [ ] Create AutoSyncManager.java
- [ ] Add home SSID + server IP/port settings to SettingsFragment
- [ ] Add auto-sync toggle
- [ ] Verify permissions in AndroidManifest

## Phase 7 — PC Receiver
- [x] Create G:/ai/wardriving/receiver.py
- [x] Create G:/ai/wardriving/incoming/ directory
- [ ] pip install flask (one-time)
- [ ] Test: run receiver, POST a test file from curl

## Phase 8 — Build & Sideload
- [ ] Build foss APK: Build → Build APK(s) → foss flavor
- [ ] Transfer to Tab S7 via USB: adb install app-foss-debug.apk
- [ ] Tab S7 one-time setup (see CLAUDE.md)

## Phase 9 — Verification
- [ ] Drive/walk 1 block — networks appear on map with correct colors
- [ ] BT scan — devices appear in list
- [ ] Cell towers — logged in DB
- [ ] GPS track — route line on map
- [ ] Heatmap — gradient renders in dense area
- [ ] Export CSV — file appears in export folder
- [ ] Auto-sync — file appears in G:/ai/wardriving/incoming/ on home WiFi connect

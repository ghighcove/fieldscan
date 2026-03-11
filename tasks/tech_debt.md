# FieldScan — Tech Debt

## Priority 1 — Auto Sprint (LOW risk)

| ID | Description | Risk | Est | Bucket | Status | Notes |
|----|-------------|------|-----|--------|--------|-------|
| TD-10 | AutoSyncManager settings UI — wire home SSID, server IP, port, enable toggle into SettingsFragment so auto-sync works end-to-end | LOW | — | auto_sprint | open | Added: 2026-03-11. |
| TD-11 | Crypto-aware marker colors — open=green, WEP=yellow, WPA=orange-red, WPA3=red on map markers | LOW | — | auto_sprint | open | Added: 2026-03-11. |
| TD-12 | PC receiver end-to-end test — run receiver.py, trigger sync from tablet, confirm file lands in G:/ai/wardriving/incoming/ | LOW | — | auto_sprint | open | Added: 2026-03-11. |
| TD-13 | Heatmap signal strength overlay — MapLibre layer expression on RSSI data, blue→cyan→green→yellow→red gradient | LOW | — | auto_sprint | open | Added: 2026-03-11. |
| TD-14 | Java package rename — net.wigle.wigleandroid → com.fieldscan.app using Android Studio refactor | LOW | — | auto_sprint | open | Added: 2026-03-11. |
| TD-15 | Export on demand — button to immediately trigger CSV export + sync without waiting for home WiFi connect | LOW | — | auto_sprint | open | Added: 2026-03-11. |
| TD-16 | Scan history view — timestamp + GPS bounding box per session so covered areas are trackable | LOW | — | auto_sprint | open | Added: 2026-03-11. |
| TD-17 | Network flagging — tap network on map to flag as suspicious, known, or ignored; persist flag in DB | LOW | — | auto_sprint | open | Added: 2026-03-11. |
| TD-18 | Offline tile caching — download MapLibre tile pack for local area so app works without data connection | LOW | — | auto_sprint | open | Added: 2026-03-11. |
| TD-19 | Session summary notification — on scan stop, show X networks found, Y new since last session | LOW | — | auto_sprint | open | Added: 2026-03-11. |
| TD-20 | Filter view — show only open networks or only new-since-last-session on map | LOW | — | auto_sprint | open | Added: 2026-03-11. |
| TD-21 | Open network vulnerability probe — dedicated "Probe" button in network detail view; connects to open network, scans common ports (80, 443, 8080, 21, 22, 23, 8443), checks for default creds on detected services, captures mDNS/Bonjour leaks, generates per-network consultant report; auto-probe on open-network tap as secondary option; part of AI-enabled RF security suite | MEDIUM | — | auto_sprint | open | Added: 2026-03-11. Build TD-22 first (device fingerprinting feeds probe logic). |
| TD-23 | Rename internal SharedPreferences file from "WiglePrefs" to "FieldScanPrefs" with migration — update PreferenceKeys.SHARED_PREFS constant and add one-time migration on app start to copy existing prefs | LOW | — | auto_sprint | open | Added: 2026-03-11. |
| TD-22 | BLE/WiFi device fingerprinting in live view — enrich list entries with device type using existing btco.yaml (company ID → vendor), ble_svc_uuids.yaml (service UUID → type e.g. "Find My", "AirPods", "Tile"), and OUI lookup for WiFi; show as subtitle in network list e.g. "BLE · Apple Find My" or "WiFi · Netgear" | LOW | — | auto_sprint | open | Added: 2026-03-11. Assets already downloaded by build. |

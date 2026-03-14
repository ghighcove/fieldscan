package net.wigle.wigleandroid;

import android.content.Context;
import android.content.SharedPreferences;

import net.wigle.wigleandroid.db.DatabaseHelper;
import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.model.NetworkType;
import net.wigle.wigleandroid.util.Logging;
import net.wigle.wigleandroid.util.PreferenceKeys;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * DeviceClassifier — three-tier device classification.
 *
 * Tier 1: App-derived (DB query — SSIDs seen 5+ times, no config needed)
 * Tier 2: Synced from PC /config endpoint → fieldscan_config.json in filesDir
 * Tier 3: Static OUI + keyword baseline bundled in APK
 */
public class DeviceClassifier {

    public enum DeviceClass {
        MEDICAL,
        CAR,
        ACCESSORIES,
        OPEN_WIFI,
        FAMILY,
        RARE,
        PHONE,
        UNKNOWN
    }

    static final String CONFIG_FILENAME  = "fieldscan_config.json";
    private static final String PREF_CONFIG_TS = "fieldscan_config_timestamp";
    private static final long CONFIG_STALE_MS  = 7L * 24 * 60 * 60 * 1000; // 7 days

    // ---------------------------------------------------------------------------
    // Tier 3 — Static OUI baseline (first 3 octets lowercase with colons)
    // ---------------------------------------------------------------------------

    private static final Set<String> MEDICAL_OUIS = new HashSet<>(Arrays.asList(
        "00:1d:ba", "d0:ad:f3", "f8:16:54",   // Dexcom
        "a4:6b:f5", "00:1a:11",                // Abbott FreeStyle Libre
        "00:26:b9", "c0:9f:42",                // Medtronic
        "00:0b:81",                             // Starkey hearing aids
        "00:1b:66",                             // Phonak / Sonova
        "e8:92:a4",                             // Cochlear
        "00:26:57", "c4:f3:12"                  // ResMed CPAP
    ));

    private static final Set<String> CAR_OUIS = new HashSet<>(Arrays.asList(
        "00:1f:c0", "34:11:8a",                // BMW
        "00:17:b6", "f0:b0:52",                // Toyota/Lexus
        "e8:4d:d0",                             // Ford
        "00:16:b6", "40:b3:95",                // GM / OnStar
        "00:8c:fa",                             // Subaru
        "00:23:14"                              // Honda
    ));

    private static final Set<String> ACCESSORIES_OUIS = new HashSet<>(Arrays.asList(
        // Apple AirPods / Beats
        "3c:28:6d", "f0:d1:a9", "a0:78:17", "00:88:65",
        "c1:30:7c", "a0:7e:5a", "28:c2:1f",
        // Samsung Galaxy Buds
        "9c:02:98", "f0:25:b7", "78:bd:bc",
        // Sony WF
        "f4:4e:fd", "74:2f:68", "00:09:dd",
        // Bose
        "88:c9:e8", "04:52:c7",
        // JBL / Harman
        "c0:64:8c", "d4:7b:b0", "d8:31:cf",
        // Jabra
        "50:c2:ed", "70:bf:92", "fc:a1:3e",
        // Tile, Fitbit
        "10:e6:c1", "00:21:3e"
    ));

    private static final Set<String> MEDICAL_KEYWORDS = new HashSet<>(Arrays.asList(
        "dexcom", "freestyle", "libre", "medtronic", "starkey", "phonak",
        "cochlear", "resmed", "cpap", "glucose", "insulin", "hearing", "cardiac"
    ));

    private static final Set<String> CAR_KEYWORDS = new HashSet<>(Arrays.asList(
        "toyota", "lexus", "ford", "honda", "bmw", "subaru", "chevy", "tesla",
        "onstar", "uconnect", "entune", "sync3", "my car", "mycar", "car wifi", "vehicle"
    ));

    private static final Set<String> ACCESSORIES_KEYWORDS = new HashSet<>(Arrays.asList(
        "airpod", "buds", "beats", "earbud", "wf-", "jabra", "tune", "tozo", "jbl",
        "bose", "galaxy bud", "skullcandy", "momentum", "elite", "sennheiser",
        "anker", "liberty", "soundcore", "pixel bud", "tile", "fitbit",
        "headphone", "earphone", "smartwatch", "galaxy watch", "apple watch"
    ));

    // ---------------------------------------------------------------------------
    // Tier 1+2 — Runtime-populated family SSIDs
    // ---------------------------------------------------------------------------

    private static final Set<String> familySsids = new HashSet<>();
    private static long configLoadedAt = 0L;

    /**
     * Load synced familynames config from app storage (Tier 2).
     * Throttled — only reloads every 60s.
     */
    public static void loadSyncedConfig(final Context ctx) {
        final long now = System.currentTimeMillis();
        if (now - configLoadedAt < 60_000L && !familySsids.isEmpty()) return;

        final File configFile = new File(ctx.getFilesDir(), CONFIG_FILENAME);
        if (!configFile.exists()) return;

        final SharedPreferences prefs = ctx.getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
        final long fileAge = now - prefs.getLong(PREF_CONFIG_TS, 0L);
        if (fileAge > CONFIG_STALE_MS) {
            Logging.info("DeviceClassifier: config is stale (" + (fileAge / 3_600_000) + "h old)");
        }

        try (BufferedReader br = new BufferedReader(new FileReader(configFile))) {
            final StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            final JSONObject json    = new JSONObject(sb.toString());
            final JSONArray  entries = json.getJSONArray("familynames");
            synchronized (familySsids) {
                familySsids.clear();
                for (int i = 0; i < entries.length(); i++) {
                    final JSONObject entry = entries.getJSONObject(i);
                    // SSIDs field may be pipe-delimited
                    for (String s : entry.optString("SSIDs", "").split("[|,]")) {
                        final String trimmed = s.trim().toLowerCase();
                        if (!trimmed.isEmpty()) familySsids.add(trimmed);
                    }
                    // Also index the name token itself
                    final String name = entry.optString("Name", "").trim().toLowerCase();
                    if (!name.isEmpty()) familySsids.add(name);
                }
            }
            configLoadedAt = now;
            Logging.info("DeviceClassifier: loaded " + familySsids.size() + " family entries from config");
        } catch (Exception e) {
            Logging.error("DeviceClassifier: config load failed: ", e);
        }
    }

    /**
     * DB-derived family name population (Tier 1).
     * Queries SSIDs with 5+ location observations → frequently-seen local networks.
     * Must be called on a background thread.
     */
    public static void refreshFromDB(final DatabaseHelper db) {
        if (db == null) return;
        try {
            android.database.Cursor cursor = db.getDB().rawQuery(
                "SELECT n.ssid, COUNT(l._id) AS obs " +
                "FROM location l JOIN network n ON l.bssid = n.bssid " +
                "WHERE n.type = 'W' AND n.ssid != '' " +
                "GROUP BY l.bssid HAVING obs >= 5 " +
                "ORDER BY obs DESC LIMIT 200",
                null);
            int added = 0;
            while (cursor.moveToNext()) {
                final String ssid = cursor.getString(0);
                if (ssid != null && !ssid.isEmpty()) {
                    synchronized (familySsids) { familySsids.add(ssid.trim().toLowerCase()); }
                    added++;
                }
            }
            cursor.close();
            Logging.info("DeviceClassifier: DB-derived " + added + " candidate SSIDs");
        } catch (Exception e) {
            Logging.error("DeviceClassifier: DB refresh failed: ", e);
        }
    }

    // ---------------------------------------------------------------------------
    // Classification
    // ---------------------------------------------------------------------------

    public static DeviceClass classify(final Network network) {
        if (network == null) return DeviceClass.UNKNOWN;
        final String mac  = network.getBssid() != null ? network.getBssid().toLowerCase() : "";
        final String ssid = network.getSsid()  != null ? network.getSsid().toLowerCase()  : "";
        final String caps = network.getCapabilities() != null ? network.getCapabilities().toLowerCase() : "";
        final String oui  = mac.length() >= 8 ? mac.substring(0, 8) : "";

        // Phone: locally-administered MAC bit (second hex digit is 2/6/a/e) on BLE/BT
        if (mac.length() >= 2 && network.getType() != NetworkType.WIFI) {
            final char second = mac.charAt(1);
            if (second == '2' || second == '6' || second == 'a' || second == 'e') {
                return DeviceClass.PHONE;
            }
        }

        // Medical
        if (MEDICAL_OUIS.contains(oui)) return DeviceClass.MEDICAL;
        for (String kw : MEDICAL_KEYWORDS) if (ssid.contains(kw)) return DeviceClass.MEDICAL;

        // Car
        if (CAR_OUIS.contains(oui)) return DeviceClass.CAR;
        for (String kw : CAR_KEYWORDS) if (ssid.contains(kw)) return DeviceClass.CAR;

        // Accessories / earbuds / wearables
        if (ACCESSORIES_OUIS.contains(oui)) return DeviceClass.ACCESSORIES;
        for (String kw : ACCESSORIES_KEYWORDS) if (ssid.contains(kw)) return DeviceClass.ACCESSORIES;

        // Open WiFi
        if (network.getType() == NetworkType.WIFI) {
            final boolean open = !caps.contains("wpa") && !caps.contains("wep")
                    && !caps.contains("rsn") && !caps.contains("sae") && !caps.contains("eap");
            if (open) return DeviceClass.OPEN_WIFI;
        }

        // Family (synced PC config + DB-derived)
        if (!ssid.isEmpty()) {
            synchronized (familySsids) {
                for (String fam : familySsids) {
                    if (ssid.contains(fam) || (fam.contains(ssid) && ssid.length() >= 4)) {
                        return DeviceClass.FAMILY;
                    }
                }
            }
        }

        return DeviceClass.UNKNOWN;
    }

    public static String label(final DeviceClass cls) {
        switch (cls) {
            case MEDICAL:     return "Medical";
            case CAR:         return "Car";
            case ACCESSORIES: return "Accessories";
            case OPEN_WIFI:   return "Open WiFi";
            case FAMILY:      return "Family";
            case RARE:        return "Rare";
            case PHONE:       return "Phone";
            default:          return "Unknown";
        }
    }

    /** ARGB color for map/list rendering by class. */
    public static int colorForClass(final DeviceClass cls) {
        switch (cls) {
            case MEDICAL:     return 0xFFEC407A; // pink
            case CAR:         return 0xFF26C6DA; // teal
            case ACCESSORIES: return 0xFFAB47BC; // purple
            case OPEN_WIFI:   return 0xFF00E676; // green
            case FAMILY:      return 0xFFFFD740; // amber
            case RARE:        return 0xFFFF5722; // deep orange
            case PHONE:       return 0xFF78909C; // blue-grey
            default:          return 0xFF9E9E9E; // grey
        }
    }

    private DeviceClassifier() {}
}

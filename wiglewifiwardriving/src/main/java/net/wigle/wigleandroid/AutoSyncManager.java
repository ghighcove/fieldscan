package net.wigle.wigleandroid;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import net.wigle.wigleandroid.util.PreferenceKeys;

import net.wigle.wigleandroid.util.Logging;

import android.os.Environment;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FieldScan AutoSyncManager
 *
 * On home WiFi connect: uploads un-synced CSV export files to PC receiver script.
 * Manual sync available via manualSync(context) — called from Sync button in list view.
 *
 * SharedPreferences keys:
 *   fieldscan_home_ssid    — SSID of home WiFi
 *   fieldscan_server_ip    — PC receiver IP
 *   fieldscan_server_port  — PC receiver port (default 8765)
 *   fieldscan_auto_sync    — enable/disable toggle
 *   fieldscan_synced_[filename] — timestamp when file was synced
 */
public class AutoSyncManager extends BroadcastReceiver {

    public static final String PREF_HOME_SSID   = "fieldscan_home_ssid";
    public static final String PREF_SERVER_IP   = "fieldscan_server_ip";
    public static final String PREF_SERVER_PORT = "fieldscan_server_port";
    public static final String PREF_AUTO_SYNC   = "fieldscan_auto_sync";
    private static final String PREF_SYNCED_PREFIX = "fieldscan_synced_";

    private static final String NOTIFICATION_CHANNEL_ID = "fieldscan_sync";
    private static final int NOTIFICATION_ID = 9001;
    private static final int DEFAULT_PORT = 8765;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** Called from Sync button — skips SSID check, syncs immediately. */
    public static void manualSync(final Context context) {
        final SharedPreferences prefs = context.getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
        final String serverIp = prefs.getString(PREF_SERVER_IP, "").trim();
        final int port = prefs.getInt(PREF_SERVER_PORT, DEFAULT_PORT);
        if (serverIp.isEmpty()) {
            Logging.info("AutoSync: server IP not configured");
            return;
        }
        executor.execute(() -> syncFiles(context, prefs, serverIp, port));
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (!WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(intent.getAction())) return;

        final NetworkInfo networkInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
        if (networkInfo == null || networkInfo.getState() != NetworkInfo.State.CONNECTED) return;

        final SharedPreferences prefs = context.getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
        if (!prefs.getBoolean(PREF_AUTO_SYNC, true)) return;

        final String homeSSID = prefs.getString(PREF_HOME_SSID, "").trim();
        final String serverIp = prefs.getString(PREF_SERVER_IP, "").trim();
        if (homeSSID.isEmpty() || serverIp.isEmpty()) return;

        final String connectedSSID = getConnectedSSID(context);
        if (!homeSSID.equals(connectedSSID)) return;

        final int port = prefs.getInt(PREF_SERVER_PORT, DEFAULT_PORT);
        executor.execute(() -> syncFiles(context, prefs, serverIp, port));
    }

    private String getConnectedSSID(final Context context) {
        try {
            final WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            final WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            String ssid = info.getSSID();
            if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return ssid;
        } catch (Exception e) {
            Logging.error("AutoSync: error reading SSID: ", e);
            return null;
        }
    }

    private static void syncFiles(final Context context, final SharedPreferences prefs,
                                  final String serverIp, final int port) {
        // Match FileUtility.getUploadFilePath(): Android 11+ uses internal filesDir, older uses /sdcard/wiglewifi/
        final File exportDir;
        try {
            exportDir = new File(net.wigle.wigleandroid.util.FileUtility.getUploadFilePath(context));
        } catch (Exception e) {
            Logging.error("AutoSync: could not resolve export dir: ", e);
            return;
        }
        Logging.info("AutoSync: scanning " + exportDir.getAbsolutePath());
        if (!exportDir.exists()) return;

        final File[] csvFiles = exportDir.listFiles(
                f -> f.isFile() && (f.getName().endsWith(".csv") || f.getName().endsWith(".csv.gz")));
        if (csvFiles == null || csvFiles.length == 0) return;

        final List<String> synced = new ArrayList<>();
        final List<String> failed = new ArrayList<>();

        for (final File f : csvFiles) {
            final String key = PREF_SYNCED_PREFIX + f.getName();
            if (prefs.contains(key)) continue;
            try {
                uploadFile(f, serverIp, port);
                prefs.edit().putString(key, String.valueOf(System.currentTimeMillis())).apply();
                synced.add(f.getName());
            } catch (Exception e) {
                failed.add(f.getName());
                Logging.error("AutoSync: failed " + f.getName() + ": ", e);
            }
        }

        if (!synced.isEmpty()) {
            showNotification(context, synced.size(), failed.size());
            // Pull updated classifier config (familynames etc.) from PC after successful upload
            fetchConfig(context, serverIp, port);
        }
    }

    /**
     * GETs /config from receiver.py and saves fieldscan_config.json to app filesDir.
     * Runs on the existing sync executor thread — no separate threading needed.
     */
    private static void fetchConfig(final Context context, final String serverIp, final int port) {
        try {
            final URL url = new URL("http://" + serverIp + ":" + port + "/config");
            final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(15_000);
            final int code = conn.getResponseCode();
            if (code != 200) {
                Logging.info("AutoSync: /config returned HTTP " + code + " — skipping");
                conn.disconnect();
                return;
            }
            final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                final byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            }
            final byte[] body = buf.toByteArray();
            conn.disconnect();
            // Write to app-private storage
            final java.io.File configFile = new java.io.File(
                    context.getFilesDir(), DeviceClassifier.CONFIG_FILENAME);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile)) {
                fos.write(body);
            }
            // Record timestamp in prefs so classifier knows when it was last synced
            context.getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0)
                    .edit().putLong("fieldscan_config_timestamp",
                            System.currentTimeMillis()).apply();
            Logging.info("AutoSync: config fetched (" + body.length + " B) → " + configFile);
        } catch (Exception e) {
            Logging.error("AutoSync: fetchConfig failed: ", e);
        }
    }

    private static void uploadFile(final File file, final String serverIp, final int port) throws Exception {
        final String boundary = "FieldScanBoundary" + System.currentTimeMillis();
        final URL url = new URL("http://" + serverIp + ":" + port + "/upload");
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);

        try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\""
                    + file.getName() + "\"\r\n");
            out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
            try (InputStream in = new FileInputStream(file)) {
                final byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            out.writeBytes("\r\n--" + boundary + "--\r\n");
        }

        final int code = conn.getResponseCode();
        conn.disconnect();
        if (code != 200) throw new RuntimeException("HTTP " + code);
    }

    private static void showNotification(final Context context, final int synced, final int failed) {
        final NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, "FieldScan Sync", NotificationManager.IMPORTANCE_LOW));
        }

        final String msg = synced + " file(s) synced to PC"
                + (failed > 0 ? " (" + failed + " failed)" : "");

        nm.notify(NOTIFICATION_ID, new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("FieldScan Sync")
                .setContentText(msg)
                .setAutoCancel(true)
                .build());
    }
}

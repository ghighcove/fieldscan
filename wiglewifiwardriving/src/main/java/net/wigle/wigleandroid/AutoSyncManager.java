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
import androidx.preference.PreferenceManager;

import net.wigle.wigleandroid.util.Logging;

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
 * Listens for WiFi connect events. When connected to the configured home SSID,
 * uploads any un-synced CSV export files to the PC receiver script.
 *
 * Settings keys (in SharedPreferences):
 *   fieldscan_home_ssid    — SSID of home WiFi (default: "")
 *   fieldscan_server_ip    — PC receiver IP (default: "")
 *   fieldscan_server_port  — PC receiver port (default: 8765)
 *   fieldscan_auto_sync    — enable/disable toggle (default: true)
 *   fieldscan_synced_[filename] — timestamp string when file was synced
 */
public class AutoSyncManager extends BroadcastReceiver {

    public static final String PREF_HOME_SSID = "fieldscan_home_ssid";
    public static final String PREF_SERVER_IP = "fieldscan_server_ip";
    public static final String PREF_SERVER_PORT = "fieldscan_server_port";
    public static final String PREF_AUTO_SYNC = "fieldscan_auto_sync";
    private static final String PREF_SYNCED_PREFIX = "fieldscan_synced_";

    private static final String NOTIFICATION_CHANNEL_ID = "fieldscan_sync";
    private static final int NOTIFICATION_ID = 9001;
    private static final int DEFAULT_PORT = 8765;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (!WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(intent.getAction())) {
            return;
        }

        final NetworkInfo networkInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
        if (networkInfo == null || networkInfo.getState() != NetworkInfo.State.CONNECTED) {
            return;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean(PREF_AUTO_SYNC, true)) {
            return;
        }

        final String homeSSID = prefs.getString(PREF_HOME_SSID, "").trim();
        final String serverIp = prefs.getString(PREF_SERVER_IP, "").trim();
        if (homeSSID.isEmpty() || serverIp.isEmpty()) {
            Logging.info("AutoSync: home SSID or server IP not configured — skipping");
            return;
        }

        final String connectedSSID = getConnectedSSID(context);
        if (connectedSSID == null || !homeSSID.equals(connectedSSID)) {
            Logging.info("AutoSync: connected to '" + connectedSSID + "', not home SSID '" + homeSSID + "'");
            return;
        }

        final int port = prefs.getInt(PREF_SERVER_PORT, DEFAULT_PORT);
        Logging.info("AutoSync: home WiFi detected — starting sync to " + serverIp + ":" + port);

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
            // Android wraps SSID in quotes
            if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return ssid;
        } catch (Exception e) {
            Logging.error("AutoSync: error reading SSID: ", e);
            return null;
        }
    }

    private void syncFiles(final Context context, final SharedPreferences prefs,
                           final String serverIp, final int port) {
        // Export directory used by WiGLE / FieldScan
        final File exportDir = context.getExternalFilesDir(null);
        if (exportDir == null || !exportDir.exists()) {
            Logging.info("AutoSync: export dir not found");
            return;
        }

        final File[] csvFiles = exportDir.listFiles(
                f -> f.isFile() && (f.getName().endsWith(".csv") || f.getName().endsWith(".csv.gz")));
        if (csvFiles == null || csvFiles.length == 0) {
            Logging.info("AutoSync: no CSV files to sync");
            return;
        }

        final List<String> synced = new ArrayList<>();
        final List<String> failed = new ArrayList<>();

        for (final File f : csvFiles) {
            final String key = PREF_SYNCED_PREFIX + f.getName();
            if (prefs.contains(key)) {
                continue; // already synced
            }
            try {
                uploadFile(f, serverIp, port);
                prefs.edit().putString(key, String.valueOf(System.currentTimeMillis())).apply();
                synced.add(f.getName());
                Logging.info("AutoSync: synced " + f.getName());
            } catch (Exception e) {
                failed.add(f.getName());
                Logging.error("AutoSync: failed to sync " + f.getName() + ": ", e);
            }
        }

        if (!synced.isEmpty()) {
            showNotification(context, synced.size(), failed.size());
        }

        Logging.info("AutoSync: done — synced=" + synced.size() + " failed=" + failed.size());
    }

    private void uploadFile(final File file, final String serverIp, final int port) throws Exception {
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
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            out.writeBytes("\r\n--" + boundary + "--\r\n");
        }

        final int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("Server returned HTTP " + responseCode);
        }
        conn.disconnect();
    }

    private void showNotification(final Context context, final int syncedCount, final int failedCount) {
        final NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, "FieldScan Sync",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }

        final String msg = syncedCount + " file(s) synced to PC"
                + (failedCount > 0 ? " (" + failedCount + " failed)" : "");

        final NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("FieldScan Sync")
                .setContentText(msg)
                .setAutoCancel(true);

        nm.notify(NOTIFICATION_ID, builder.build());
    }
}

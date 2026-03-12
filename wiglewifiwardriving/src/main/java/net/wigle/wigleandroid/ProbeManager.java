package net.wigle.wigleandroid;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import net.wigle.wigleandroid.util.Logging;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ProbeManager — open-network vulnerability probe for FieldScan.
 *
 * Joins the target open WiFi network (Android 10+), discovers the gateway,
 * port-scans common admin ports, and sends an mDNS service-discovery query.
 * All heavy work runs on a background thread; the callback fires on the main thread.
 */
public class ProbeManager {

    /** Ports to probe on the gateway and 192.168.1.1 */
    private static final int[] PROBE_PORTS = {80, 443, 8080, 21, 22, 23, 8443};

    /** TCP connect timeout in milliseconds */
    private static final int CONNECT_TIMEOUT_MS = 2000;

    /** HTTP read timeout */
    private static final int HTTP_READ_TIMEOUT_MS = 3000;

    /** mDNS multicast group */
    private static final String MDNS_GROUP = "224.0.0.251";
    private static final int MDNS_PORT = 5353;

    /** How long to listen for mDNS responses (ms) */
    private static final int MDNS_LISTEN_MS = 3000;

    public interface ProbeCallback {
        /**
         * Called on the main thread when probing is complete.
         *
         * @param report Human-readable plain-text report.
         */
        void onResult(String report);
    }

    /**
     * Entry point. Runs everything on a background thread; delivers result via
     * {@code callback} on the main (UI) thread.
     *
     * @param ctx      Application context
     * @param ssid     SSID of the open network to probe
     * @param callback Result receiver
     */
    public static void probe(final Context ctx, final String ssid, final ProbeCallback callback) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            final String report;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                report = probeWithSpecifier(ctx, ssid);
            } else {
                // On SDK < 29 we cannot request a specific network without WifiManager.enableNetwork,
                // which requires the network to already be configured. Skip join, probe on current connection.
                report = probeCurrentConnection(ctx, ssid);
            }
            mainHandler.post(() -> callback.onResult(report));
        }, "FieldScan-Probe").start();
    }

    // -------------------------------------------------------------------------
    // Android 10+ path: WifiNetworkSpecifier
    // -------------------------------------------------------------------------

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private static String probeWithSpecifier(final Context ctx, final String ssid) {
        final StringBuilder sb = new StringBuilder();
        sb.append("=== FieldScan Network Probe ===\n");
        sb.append("Target SSID: ").append(ssid).append("\n\n");

        final ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return sb.append("ERROR: ConnectivityManager unavailable.\n").toString();
        }

        final WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .build();

        final NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build();

        // Latch waits for network available or timeout
        final CountDownLatch latch = new CountDownLatch(1);
        final Network[] boundNetwork = {null};

        final ConnectivityManager.NetworkCallback networkCallback =
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(@NonNull Network network) {
                        boundNetwork[0] = network;
                        latch.countDown();
                    }

                    @Override
                    public void onUnavailable() {
                        latch.countDown();
                    }
                };

        try {
            cm.requestNetwork(request, networkCallback, 10_000 /* ms timeout */);
            boolean joined = latch.await(12, TimeUnit.SECONDS);

            if (!joined || boundNetwork[0] == null) {
                sb.append("Could not join network (timeout or unavailable).\n");
                sb.append("Falling back to current active connection...\n\n");
                runProbes(ctx, null, ssid, sb);
            } else {
                sb.append("Joined network successfully.\n\n");
                runProbes(ctx, boundNetwork[0], ssid, sb);
            }
        } catch (Exception e) {
            Logging.error("ProbeManager: exception during network request", e);
            sb.append("Exception joining network: ").append(e.getMessage()).append("\n");
            runProbes(ctx, null, ssid, sb);
        } finally {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) { /* already unregistered */ }
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // SDK < 29 fallback: probe whatever connection is active
    // -------------------------------------------------------------------------

    private static String probeCurrentConnection(final Context ctx, final String ssid) {
        final StringBuilder sb = new StringBuilder();
        sb.append("=== FieldScan Network Probe ===\n");
        sb.append("Target SSID: ").append(ssid).append("\n");
        sb.append("(Android < 10: probing current network connection)\n\n");
        runProbes(ctx, null, ssid, sb);
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Core probe logic
    // -------------------------------------------------------------------------

    /**
     * Discovers gateway addresses, port-scans each, and runs mDNS discovery.
     * Results are appended to {@code sb}.
     *
     * @param network Bound network (may be null — uses default routes in that case)
     */
    private static void runProbes(final Context ctx, final Network network,
                                   final String ssid, final StringBuilder sb) {
        final List<InetAddress> targets = discoverTargets(ctx, network);

        // ---- Port scan ----
        sb.append("--- Port Scan ---\n");
        if (targets.isEmpty()) {
            sb.append("No gateway detected; scanning 192.168.1.1 only.\n");
            try {
                targets.add(InetAddress.getByName("192.168.1.1"));
            } catch (UnknownHostException ignored) { /* static address — should not fail */ }
        }

        boolean anyPortOpen = false;
        for (final InetAddress target : targets) {
            sb.append("Host: ").append(target.getHostAddress()).append("\n");
            for (final int port : PROBE_PORTS) {
                final String portResult = probePort(network, target, port);
                if (portResult != null) {
                    anyPortOpen = true;
                    sb.append("  [OPEN] ").append(port).append(" — ").append(portResult).append("\n");
                }
            }
        }
        if (!anyPortOpen) {
            sb.append("No open ports found on scanned hosts.\n");
        }

        // ---- mDNS discovery ----
        sb.append("\n--- mDNS Service Discovery ---\n");
        final List<String> mdnsServices = queryMdns(network);
        if (mdnsServices.isEmpty()) {
            sb.append("No mDNS services found.\n");
        } else {
            for (final String svc : mdnsServices) {
                sb.append("  ").append(svc).append("\n");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Gateway discovery
    // -------------------------------------------------------------------------

    private static List<InetAddress> discoverTargets(final Context ctx,
                                                      final Network network) {
        final List<InetAddress> targets = new ArrayList<>();
        try {
            final ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return targets;

            final Network queryNet = (network != null) ? network : cm.getActiveNetwork();
            if (queryNet == null) return targets;

            final LinkProperties lp = cm.getLinkProperties(queryNet);
            if (lp == null) return targets;

            // Primary: DHCP server address (the gateway on home/hotel networks)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                final InetAddress dhcp = lp.getDhcpServerAddress();
                if (dhcp != null) targets.add(dhcp);
            }

            // Secondary: default route gateway
            for (final RouteInfo route : lp.getRoutes()) {
                if (route.isDefaultRoute()) {
                    final InetAddress gw = route.getGateway();
                    if (gw != null && !gw.isAnyLocalAddress() && !targets.contains(gw)) {
                        targets.add(gw);
                    }
                }
            }

            // Always include the RFC-1918 default as fallback alongside detected gateway
            final InetAddress fallback = InetAddress.getByName("192.168.1.1");
            if (!targets.contains(fallback)) {
                targets.add(fallback);
            }

        } catch (Exception e) {
            Logging.error("ProbeManager: gateway discovery error", e);
        }
        return targets;
    }

    // -------------------------------------------------------------------------
    // Port probe
    // -------------------------------------------------------------------------

    /**
     * Attempts a TCP connect to {@code host:port}. If successful and the port is
     * HTTP-like, performs a GET and extracts the page title and Server header.
     *
     * @return A short description string if the port is open, or {@code null} if closed/filtered.
     */
    private static String probePort(final Network network, final InetAddress host, final int port) {
        // First test: raw TCP connect
        try (Socket s = (network != null) ? network.getSocketFactory().createSocket() :
                new Socket()) {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            // Port is open — for HTTP ports, fetch a banner
            if (port == 80 || port == 8080 || port == 443 || port == 8443) {
                return httpBanner(network, host, port);
            }
            return bannerGrab(s, port);
        } catch (Exception e) {
            return null; // closed or filtered
        }
    }

    /**
     * Does an HTTP GET to / and returns "title=[X] server=[Y]" where available.
     */
    private static String httpBanner(final Network network,
                                      final InetAddress host, final int port) {
        final String scheme = (port == 443 || port == 8443) ? "https" : "http";
        final String urlStr = scheme + "://" + host.getHostAddress() + ":" + port + "/";
        try {
            final URL url = new URL(urlStr);
            final HttpURLConnection conn;
            if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                conn = (HttpURLConnection) network.openConnection(url);
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);

            final String server = conn.getHeaderField("Server");
            final String contentType = conn.getContentType();
            String title = null;

            if (contentType != null && contentType.contains("text/html")) {
                try (final BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    final StringBuilder html = new StringBuilder();
                    String line;
                    int lineCount = 0;
                    while ((line = reader.readLine()) != null && lineCount < 50) {
                        html.append(line);
                        lineCount++;
                        final String lower = html.toString().toLowerCase(Locale.ROOT);
                        final int titleStart = lower.indexOf("<title>");
                        if (titleStart >= 0) {
                            final int titleEnd = lower.indexOf("</title>", titleStart);
                            if (titleEnd >= 0) {
                                title = html.substring(titleStart + 7, titleEnd).trim();
                                break;
                            }
                        }
                    }
                }
            }
            conn.disconnect();

            final StringBuilder result = new StringBuilder("HTTP");
            if (server != null && !server.isEmpty()) {
                result.append(" server=").append(server);
            }
            if (title != null && !title.isEmpty()) {
                // Truncate very long titles
                result.append(" title=").append(title.length() > 60 ? title.substring(0, 60) + "…" : title);
            }
            return result.toString();

        } catch (Exception e) {
            // Port is open but HTTPS/redirect failed — still report it
            return "HTTP (no banner)";
        }
    }

    /**
     * Reads up to 256 bytes of a plain-text banner for non-HTTP ports (FTP, SSH, Telnet).
     */
    private static String bannerGrab(final Socket socket, final int port) {
        try {
            socket.setSoTimeout(1500);
            final byte[] buf = new byte[256];
            final int read = socket.getInputStream().read(buf);
            if (read > 0) {
                final String banner = new String(buf, 0, read).trim()
                        .replaceAll("[\r\n]+", " ")
                        .replaceAll("[^\\x20-\\x7E]", "");
                return banner.isEmpty() ? "open" : banner.substring(0, Math.min(80, banner.length()));
            }
        } catch (Exception ignored) { /* no banner — fall through */ }
        return "open";
    }

    // -------------------------------------------------------------------------
    // mDNS query
    // -------------------------------------------------------------------------

    /**
     * Sends a DNS-SD PTR query for "_services._dns-sd._udp.local" to the mDNS
     * multicast group and listens {@link #MDNS_LISTEN_MS} ms for responses.
     *
     * @return List of raw service name strings found in PTR responses.
     */
    private static List<String> queryMdns(final Network network) {
        final List<String> services = new ArrayList<>();
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket(MDNS_PORT);
            socket.setSoTimeout(MDNS_LISTEN_MS);

            // Bind to the probe network if possible (SDK 21+)
            if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                network.bindSocket(socket);
            }

            final InetAddress group = InetAddress.getByName(MDNS_GROUP);
            socket.joinGroup(group);

            // Build a minimal DNS PTR query for _services._dns-sd._udp.local
            final byte[] query = buildMdnsQuery();
            final DatagramPacket sendPacket = new DatagramPacket(
                    query, query.length, group, MDNS_PORT);
            socket.send(sendPacket);

            // Listen for responses until timeout
            final byte[] recvBuf = new byte[4096];
            final long deadline = System.currentTimeMillis() + MDNS_LISTEN_MS;
            while (System.currentTimeMillis() < deadline) {
                try {
                    final DatagramPacket recv = new DatagramPacket(recvBuf, recvBuf.length);
                    socket.receive(recv);
                    final List<String> parsed = parseMdnsResponse(recvBuf, recv.getLength());
                    for (final String name : parsed) {
                        if (!services.contains(name)) {
                            services.add(name);
                        }
                    }
                } catch (IOException timeout) {
                    break; // socket timeout — done listening
                }
            }

            socket.leaveGroup(group);
        } catch (Exception e) {
            Logging.error("ProbeManager: mDNS query error", e);
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
        return services;
    }

    /**
     * Builds a minimal DNS query packet for the PTR record
     * "_services._dns-sd._udp.local" (DNS-SD browsing query).
     */
    private static byte[] buildMdnsQuery() {
        // DNS question for _services._dns-sd._udp.local PTR IN
        // Labels: _services, _dns-sd, _udp, local
        final byte[] labels = encodeDnsName("_services._dns-sd._udp.local");
        // Header (12 bytes) + question
        final int len = 12 + labels.length + 4; // 4 = QTYPE(2) + QCLASS(2)
        final byte[] pkt = new byte[len];
        // Transaction ID: 0x0000 (mDNS ignores it)
        pkt[0] = 0; pkt[1] = 0;
        // Flags: standard query
        pkt[2] = 0; pkt[3] = 0;
        // QDCOUNT = 1
        pkt[4] = 0; pkt[5] = 1;
        // ANCOUNT, NSCOUNT, ARCOUNT = 0
        pkt[6] = 0; pkt[7] = 0;
        pkt[8] = 0; pkt[9] = 0;
        pkt[10] = 0; pkt[11] = 0;
        // Question
        System.arraycopy(labels, 0, pkt, 12, labels.length);
        final int qPos = 12 + labels.length;
        // QTYPE = PTR (12)
        pkt[qPos] = 0; pkt[qPos + 1] = 12;
        // QCLASS = IN (1) with QU bit cleared
        pkt[qPos + 2] = 0; pkt[qPos + 3] = 1;
        return pkt;
    }

    /** Encodes a dot-separated DNS name into length-prefixed label format. */
    private static byte[] encodeDnsName(final String name) {
        final String[] parts = name.split("\\.");
        int totalLen = 1; // trailing zero
        for (final String p : parts) totalLen += 1 + p.length();
        final byte[] buf = new byte[totalLen];
        int pos = 0;
        for (final String p : parts) {
            buf[pos++] = (byte) p.length();
            for (int i = 0; i < p.length(); i++) {
                buf[pos++] = (byte) p.charAt(i);
            }
        }
        buf[pos] = 0; // root label
        return buf;
    }

    /**
     * Very lightweight DNS response parser — extracts PTR RDATA strings from
     * a raw mDNS packet. Does not handle compression pointers in RDATA (mDNS
     * responses to the browsing query typically use full names).
     */
    private static List<String> parseMdnsResponse(final byte[] data, final int len) {
        final List<String> names = new ArrayList<>();
        if (len < 12) return names;
        try {
            // Parse answer count from header
            final int anCount = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
            if (anCount == 0) return names;

            // Skip question section
            int pos = 12;
            final int qdCount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
            for (int q = 0; q < qdCount && pos < len; q++) {
                pos = skipDnsName(data, pos, len);
                pos += 4; // QTYPE + QCLASS
            }

            // Parse answers
            for (int a = 0; a < anCount && pos < len; a++) {
                pos = skipDnsName(data, pos, len);
                if (pos + 10 > len) break;
                final int type = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                pos += 8; // TYPE(2) CLASS(2) TTL(4)
                final int rdLen = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                pos += 2;
                if (type == 12) { // PTR
                    final String ptdName = readDnsName(data, pos, len);
                    if (ptdName != null && !ptdName.isEmpty()) {
                        names.add(ptdName);
                    }
                }
                pos += rdLen;
            }
        } catch (Exception e) {
            // Malformed packet — ignore
        }
        return names;
    }

    /** Skips over a DNS name (handles compression pointers). Returns position after name. */
    private static int skipDnsName(final byte[] data, int pos, final int len) {
        while (pos < len) {
            final int labelLen = data[pos] & 0xFF;
            if (labelLen == 0) { return pos + 1; }
            if ((labelLen & 0xC0) == 0xC0) { return pos + 2; } // compression pointer
            pos += 1 + labelLen;
        }
        return pos;
    }

    /** Reads a DNS name at {@code pos} into a dot-separated string. */
    private static String readDnsName(final byte[] data, int pos, final int len) {
        final StringBuilder sb = new StringBuilder();
        int safety = 0;
        while (pos < len && safety++ < 128) {
            final int labelLen = data[pos] & 0xFF;
            if (labelLen == 0) break;
            if ((labelLen & 0xC0) == 0xC0) {
                // Compression pointer — follow it
                if (pos + 1 >= len) break;
                pos = ((labelLen & 0x3F) << 8) | (data[pos + 1] & 0xFF);
                continue;
            }
            if (sb.length() > 0) sb.append('.');
            for (int i = 1; i <= labelLen && pos + i < len; i++) {
                sb.append((char) (data[pos + i] & 0xFF));
            }
            pos += 1 + labelLen;
        }
        return sb.toString();
    }
}

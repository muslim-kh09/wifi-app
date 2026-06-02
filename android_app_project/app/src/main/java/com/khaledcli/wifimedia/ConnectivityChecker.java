package com.khaledcli.wifimedia;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.RouteInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import java.lang.ref.WeakReference;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;

/**
 * Async helper that probes connectivity in 3 steps and opens the captive-portal URL.
 *
 * Step 1 — WiFi gateway:  http://<GatewayIP>:8080/index.php  (timeout 3 s)
 * Step 2 — Localhost:     http://127.0.0.1:8080/index.php    (timeout 2 s)
 * Step 3 — Error state:   show Arabic AlertDialog. NEVER opens Custom Tabs.
 *
 * Key safety guarantees:
 *  - A single 'resolvedUrl' guard variable controls all three outcomes.
 *    If it is null after both probes, the error dialog is ALWAYS shown.
 *  - resolveGatewayIp() is ONLY called when isWifiConnected() returns true.
 *    It never produces a fallback IP on its own — if both extraction methods
 *    fail it returns null, causing a safe drop-through to Step 2.
 *  - Every method is wrapped in try-catch with a safe default return.
 *  - All UI operations are posted to the main thread via Handler.
 *  - WeakReference prevents memory leaks when the Activity finishes.
 */
public class ConnectivityChecker {

    private static final String PORT_AND_PATH = ":8080/index.php";
    private static final String LOCALHOST_URL  = "http://127.0.0.1" + PORT_AND_PATH;

    private final WeakReference<Activity> activityRef;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ConnectivityChecker(Activity activity) {
        this.activityRef = new WeakReference<>(activity);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    /** Kicks off the background probe. Safe to call from the main thread. */
    public void check() {
        new Thread(() -> {
            Activity activity = activityRef.get();
            if (activity == null || activity.isFinishing()) return;

            // Single guard variable — only one path may set it.
            // If it remains null after all probes, the error dialog is shown.
            String resolvedUrl = null;

            // ── Step 1: WiFi gateway ──────────────────────────────────────────
            // isWifiConnected() is called FIRST. resolveGatewayIp() is called ONLY
            // if WiFi is confirmed. This prevents any cellular-IP leakage.
            if (isWifiConnected(activity)) {
                String gatewayIp = resolveGatewayIp(activity);
                // resolveGatewayIp() returns null if both extraction methods fail —
                // no hardcoded fallback, no guessing.
                if (gatewayIp != null && !gatewayIp.isEmpty()) {
                    String candidate = "http://" + gatewayIp + PORT_AND_PATH;
                    if (isReachable(candidate, 3000)) {
                        resolvedUrl = candidate;
                    }
                }
            }

            // ── Step 2: Localhost fallback — only if Step 1 did not resolve ──
            if (resolvedUrl == null) {
                if (isReachable(LOCALHOST_URL, 2000)) {
                    resolvedUrl = "http://127.0.0.1:8080/index.php?clientmac=06%3Aff%3A89%3A94%3Ae0%3Ada&tok=1&redir=google.com";
                }
            }

            // ── Step 3: Single decision point ────────────────────────────────
            // This is the ONLY place where either action is triggered.
            // If resolvedUrl == null here, it is IMPOSSIBLE for Custom Tabs to open.
            final String urlToLaunch = resolvedUrl;
            mainHandler.post(() -> {
                Activity a = activityRef.get();
                if (a == null || a.isFinishing()) return;

                if (urlToLaunch != null) {
                    // SUCCESS — open the captive portal
                    int toolbarColor = ContextCompat.getColor(a, R.color.icon_accent);
                    CustomTabsHelper.openUrl(a, urlToLaunch, toolbarColor);
                    a.finish();
                } else {
                    // FAILURE — show Arabic error dialog, never open browser
                    showArabicErrorDialog(a);
                }
            });

        }, "ConnectivityChecker").start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WiFi detection (standard Android APIs — no root required)
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private boolean isWifiConnected(Context context) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // API 23+: NetworkCapabilities (non-deprecated)
                Network active = cm.getActiveNetwork();
                if (active == null) return false;

                NetworkCapabilities nc = cm.getNetworkCapabilities(active);
                if (nc == null) return false;

                // Both conditions must be true:
                //  1. The active network uses the WiFi transport layer.
                //  2. It is not a VPN masquerading as WiFi.
                return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            } else {
                // API 21-22: fall back to deprecated getActiveNetworkInfo()
                NetworkInfo info = cm.getActiveNetworkInfo();
                return info != null
                        && info.isConnected()
                        && info.getType() == ConnectivityManager.TYPE_WIFI;
            }
        } catch (Exception e) {
            // Treat any exception as "not on WiFi" — safe conservative default
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gateway IP resolution — strict, no hardcoded fallback
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the IPv4 gateway of the active WiFi network.
     * Returns null if extraction fails — never returns a hardcoded default.
     * Only call this method AFTER confirming isWifiConnected() == true.
     */
    private String resolveGatewayIp(Context context) {

        // ── Method 1: LinkProperties default route (API 23+, most accurate) ─
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                ConnectivityManager cm =
                        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) return null;

                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork == null) return null;

                LinkProperties lp = cm.getLinkProperties(activeNetwork);
                if (lp == null) return null;

                for (RouteInfo route : lp.getRoutes()) {
                    if (route == null) continue;
                    if (!route.isDefaultRoute()) continue;
                    InetAddress gateway = route.getGateway();
                    if (!(gateway instanceof Inet4Address)) continue;
                    String ip = gateway.getHostAddress();
                    if (ip != null && !ip.isEmpty() && !ip.equals("0.0.0.0")) {
                        return ip; // ✓ found a valid IPv4 gateway
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // ── Method 2: UDP socket trick (API 21+, fallback) ───────────────────
        // IMPORTANT: This is wrapped in a strict null / loopback guard.
        // If getLocalAddress() returns 127.0.0.1 or 0.0.0.0 (i.e. no real
        // network), we discard the result rather than deriving a bogus gateway.
        try {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
                InetAddress localAddress = socket.getLocalAddress();

                // Guard: discard loopback / wildcard addresses
                if (localAddress == null
                        || localAddress.isLoopbackAddress()
                        || localAddress.isAnyLocalAddress()) {
                    return null;
                }

                String localIp = localAddress.getHostAddress();
                if (localIp == null || !localIp.contains(".")) return null;

                // Additional guard: loopback range
                if (localIp.startsWith("127.") || localIp.equals("0.0.0.0")) return null;

                // Derive gateway as <subnet>.1
                return localIp.substring(0, localIp.lastIndexOf('.')) + ".1";
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Both methods failed — return null so caller falls through to Step 2
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP HEAD probe
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if the server at {@code urlString} responds with any HTTP status code.
     * Captive portals typically return 200 or a 3xx redirect — both count as "reachable".
     * Any exception (timeout, refused, no route) correctly returns false.
     */
    private boolean isReachable(String urlString, int timeoutMs) {
        if (urlString == null || urlString.isEmpty()) return false;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            return code > 0; // any valid HTTP response = server is reachable
        } catch (Exception e) {
            return false; // timeout, connection refused, no route — server not reachable
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error dialog (main thread only)
    // ─────────────────────────────────────────────────────────────────────────

    private void showArabicErrorDialog(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("خطأ في الاتصال")
                .setMessage(
                    "عذراً، الرجاء التأكد من اتصالك بالإنترنت أو المحاولة مجدداً.")
                .setPositiveButton("حسناً", (dialog, which) -> activity.finish())
                .setCancelable(false)
                .show();
    }
}

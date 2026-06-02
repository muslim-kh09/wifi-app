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
 * Step 3 — Error state:   show Arabic AlertDialog, do NOT open Custom Tabs.
 *
 * All UI operations are posted back to the main thread via Handler.
 * A WeakReference to the Activity prevents memory leaks.
 */
public class ConnectivityChecker {

    private static final String PORT_AND_PATH = ":8080/index.php";
    private static final String LOCALHOST_URL  = "http://127.0.0.1" + PORT_AND_PATH;

    private final WeakReference<Activity> activityRef;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ConnectivityChecker(Activity activity) {
        this.activityRef = new WeakReference<>(activity);
    }

    /** Kick off the background probe. Safe to call from the main thread. */
    public void check() {
        new Thread(() -> {
            Activity activity = activityRef.get();
            if (activity == null || activity.isFinishing()) return;

            // ── Step 1: WiFi gateway ──────────────────────────────────────────
            if (isWifiConnected(activity)) {
                String gatewayIp = resolveGatewayIp(activity);
                if (gatewayIp != null && !gatewayIp.isEmpty()) {
                    String url = "http://" + gatewayIp + PORT_AND_PATH;
                    if (isReachable(url, 3000)) {
                        launchUrl(url);
                        return;
                    }
                }
            }

            // ── Step 2: Localhost fallback (developer/testing) ────────────────
            if (isReachable(LOCALHOST_URL, 2000)) {
                launchUrl(LOCALHOST_URL);
                return;
            }

            // ── Step 3: Error state ───────────────────────────────────────────
            showArabicErrorDialog();

        }, "ConnectivityChecker").start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WiFi detection (standard APIs — no root required)
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private boolean isWifiConnected(Context context) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // API 23+ — use NetworkCapabilities (non-deprecated)
                Network active = cm.getActiveNetwork();
                if (active == null) return false;
                NetworkCapabilities nc = cm.getNetworkCapabilities(active);
                return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } else {
                // API 21-22 — fall back to deprecated getActiveNetworkInfo()
                NetworkInfo info = cm.getActiveNetworkInfo();
                return info != null
                        && info.isConnected()
                        && info.getType() == ConnectivityManager.TYPE_WIFI;
            }
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gateway IP resolution — two-method chain (same as original MainActivity)
    // ─────────────────────────────────────────────────────────────────────────

    private String resolveGatewayIp(Context context) {

        // Method 1: ConnectivityManager → LinkProperties → default route (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                ConnectivityManager cm =
                        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network activeNetwork = cm.getActiveNetwork();
                    if (activeNetwork != null) {
                        LinkProperties lp = cm.getLinkProperties(activeNetwork);
                        if (lp != null) {
                            for (RouteInfo route : lp.getRoutes()) {
                                if (route.isDefaultRoute()
                                        && route.getGateway() instanceof Inet4Address) {
                                    String ip = route.getGateway().getHostAddress();
                                    if (ip != null && !ip.isEmpty()) return ip;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Method 2: UDP socket trick + NetworkInterface scan (API 21+)
        try {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
                InetAddress localAddress = socket.getLocalAddress();
                NetworkInterface iface = NetworkInterface.getByInetAddress(localAddress);
                if (iface != null) {
                    for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                        if (addr.getAddress() instanceof Inet4Address) {
                            String localIp = addr.getAddress().getHostAddress();
                            if (localIp != null && localIp.contains(".")) {
                                // Assume gateway is <subnet>.1
                                return localIp.substring(0, localIp.lastIndexOf('.')) + ".1";
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null; // Both methods failed — caller will move to Step 2
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP HEAD probe
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if the server at {@code urlString} responds with any HTTP code.
     * Captive portals typically return 200 or a 3xx redirect — both count as "reachable".
     */
    private boolean isReachable(String urlString, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            return code > 0; // any valid HTTP response = server is alive
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main-thread callbacks
    // ─────────────────────────────────────────────────────────────────────────

    private void launchUrl(String url) {
        mainHandler.post(() -> {
            Activity activity = activityRef.get();
            if (activity == null || activity.isFinishing()) return;
            int toolbarColor = ContextCompat.getColor(activity, R.color.icon_accent);
            CustomTabsHelper.openUrl(activity, url, toolbarColor);
            activity.finish();
        });
    }

    private void showArabicErrorDialog() {
        mainHandler.post(() -> {
            Activity activity = activityRef.get();
            if (activity == null || activity.isFinishing()) return;
            new AlertDialog.Builder(activity)
                    .setTitle("خطأ في الاتصال")
                    .setMessage(
                        "عذراً، الرجاء التأكد من اتصالك بالإنترنت أو المحاولة مجدداً.")
                    .setPositiveButton("حسناً", (dialog, which) -> activity.finish())
                    .setCancelable(false)
                    .show();
        });
    }
}

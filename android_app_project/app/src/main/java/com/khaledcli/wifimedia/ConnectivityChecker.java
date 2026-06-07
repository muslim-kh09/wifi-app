package com.khaledcli.wifimedia;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ActivityManager;
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

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Async helper that probes connectivity in 3 steps and opens the captive-portal URL.
 *
 * Step 1 — WiFi gateway:  http://<GatewayIP>:8080/index.php?<hw_params>  (timeout 3 s)
 * Step 2 — Localhost:     http://127.0.0.1:8080/index.php?<hw_params>    (timeout 2 s)
 * Step 3 — Error state:   show Arabic AlertDialog. NEVER opens Custom Tabs.
 *
 * Native Hardware Injection:
 *   Before building either URL, {@link #buildHardwareParams(Context)} reads:
 *     - {@code android.os.Build.BRAND}  → the device manufacturer/brand (e.g. "samsung")
 *     - {@code android.os.Build.MODEL}  → the device model (e.g. "SM-A536B")
 *     - {@link ActivityManager.MemoryInfo#totalMem} → total physical RAM in GB (1 decimal)
 *   These values are URL-encoded and appended as:
 *     {@code ?n_brand=...&n_model=...&n_ram=...}
 *   The PHP portal stores them in $_SESSION['temp_hardware'] on arrival and
 *   hydrates the database device_profile when the user activates a voucher.
 *
 * Key safety guarantees:
 *  - buildHardwareParams() is fully wrapped in try-catch; any failure returns "".
 *  - A single 'resolvedUrl' guard variable controls all three outcomes.
 *  - All UI operations are posted to the main thread via Handler.
 *  - WeakReference prevents memory leaks when the Activity finishes.
 */
public class ConnectivityChecker {

    // Constants moved to string resources to prevent hardcoding

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

            // Build native hardware query string once (used in both URLs)
            final String hwParams = buildHardwareParams(activity);

            // ── Concurrent Fast-Probing Strategy ─────────────────────────────
            java.util.concurrent.atomic.AtomicReference<String> winner = new java.util.concurrent.atomic.AtomicReference<>(null);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            int probeCount = 0;

            String dynamicGateway = isWifiConnected(activity) ? resolveGatewayIp(activity) : null;
            String[] targetIps = new String[]{ dynamicGateway, "192.168.49.1", "127.0.0.1" };
            
            int port = activity.getResources().getInteger(R.integer.server_port);
            String path = activity.getString(R.string.path_connectivity_index);
            String portAndPath = ":" + port + path;
            
            for (String ip : targetIps) {
                if (ip != null && !ip.isEmpty() && !ip.equals("0.0.0.0")) {
                    probeCount++;
                    final String candidateUrl;
                    if (ip.equals("127.0.0.1")) {
                        candidateUrl = "http://127.0.0.1" + portAndPath
                                + "?clientmac=06%3Aff%3A89%3A94%3Ae0%3Ada&tok=1&redir=google.com"
                                + (hwParams.startsWith("?") ? "&" + hwParams.substring(1) : hwParams);
                    } else {
                        candidateUrl = "http://" + ip + portAndPath + hwParams;
                    }

                    new Thread(() -> {
                        if (isReachable(candidateUrl, 2500)) {
                            if (winner.compareAndSet(null, candidateUrl)) {
                                latch.countDown();
                            }
                        }
                    }).start();
                }
            }

            try {
                if (probeCount > 0) {
                    latch.await(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException ignored) {}

            String resolvedUrl = winner.get();

            // ── Step 3: Single decision point ─────────────────────────────────
            final String urlToLaunch = resolvedUrl;
            mainHandler.post(() -> {
                Activity a = activityRef.get();
                if (a == null || a.isFinishing()) return;

                if (urlToLaunch != null) {
                    int toolbarColor = ContextCompat.getColor(a, R.color.icon_accent);
                    CustomTabsHelper.openUrl(a, urlToLaunch, toolbarColor);
                    a.finish();
                } else {
                    showArabicErrorDialog(a);
                }
            });

        }, "ConnectivityChecker").start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Native hardware parameter builder
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads true hardware facts from {@link android.os.Build} and {@link ActivityManager},
     * then returns a URL query string like {@code ?n_brand=samsung&n_model=SM-A536B&n_ram=6.0}.
     *
     * <p>All values are individually URL-encoded. A failed read for any single value
     * is silently skipped. If all reads fail, returns an empty string (safe no-op).
     *
     * @param context Application context (used to get ActivityManager)
     * @return URL-encoded query string beginning with "?", or "" on total failure
     */
    private String buildHardwareParams(Context context) {
        try {
            // ── Brand (manufacturer) ──────────────────────────────────────────
            String brand = "";
            try {
                brand = Build.BRAND != null ? Build.BRAND.toLowerCase().trim() : "";
            } catch (Exception ignored) {}

            // ── Model ─────────────────────────────────────────────────────────
            String model = "";
            try {
                model = Build.MODEL != null ? Build.MODEL.trim() : "";
            } catch (Exception ignored) {}

            // ── Total physical RAM in GB (1 decimal place) ────────────────────
            String ramGb = "0";
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    // totalMem is in bytes; convert to GB with 1 decimal
                    double gb = mi.totalMem / (1024.0 * 1024.0 * 1024.0);
                    ramGb = String.format("%.1f", gb);
                }
            } catch (Exception ignored) {}

            if (brand.isEmpty() && model.isEmpty()) {
                AppLogger.info("HTTP_INJECT", "No hardware info available to inject");
                return ""; // Nothing to append
            }

            String nativeId = MacAddressHelper.getAndroidId(context);
            String params = "?n_brand=" + encode(brand)
                 + "&n_model=" + encode(model)
                 + "&n_ram="   + encode(ramGb)
                 + "&native_id=" + encode(nativeId);

            AppLogger.info("HTTP_INJECT", "Appending params: brand=" + brand + ", model=" + model + ", ram=" + ramGb + "GB, id=" + nativeId);
            return params;

        } catch (Exception e) {
            AppLogger.error("HTTP_INJECT", "Failed to build hardware params", e);
            e.printStackTrace();
            return ""; // Safe: hardware params are best-effort
        }
    }

    /** URL-encodes a single parameter value. Returns empty string on failure. */
    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WiFi detection
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private boolean isWifiConnected(Context context) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network active = cm.getActiveNetwork();
                if (active == null) return false;
                NetworkCapabilities nc = cm.getNetworkCapabilities(active);
                if (nc == null) return false;
                return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            } else {
                NetworkInfo info = cm.getActiveNetworkInfo();
                return info != null && info.isConnected()
                        && info.getType() == ConnectivityManager.TYPE_WIFI;
            }
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gateway IP resolution
    // ─────────────────────────────────────────────────────────────────────────

    private String resolveGatewayIp(Context context) {
        // Method 1: LinkProperties default route (API 23+, most accurate)
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
                    if (route == null || !route.isDefaultRoute()) continue;
                    InetAddress gateway = route.getGateway();
                    if (!(gateway instanceof Inet4Address)) continue;
                    String ip = gateway.getHostAddress();
                    if (ip != null && !ip.isEmpty() && !ip.equals("0.0.0.0")) return ip;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Method 2: UDP socket trick (fallback)
        try {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
                InetAddress localAddress = socket.getLocalAddress();
                if (localAddress == null
                        || localAddress.isLoopbackAddress()
                        || localAddress.isAnyLocalAddress()) return null;
                String localIp = localAddress.getHostAddress();
                if (localIp == null || !localIp.contains(".")) return null;
                if (localIp.startsWith("127.") || localIp.equals("0.0.0.0")) return null;
                return localIp.substring(0, localIp.lastIndexOf('.')) + ".1";
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP HEAD probe
    // ─────────────────────────────────────────────────────────────────────────

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
            return conn.getResponseCode() > 0;
        } catch (Exception e) {
            return false;
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
                .setMessage("عذراً، الرجاء التأكد من اتصالك بالإنترنت أو المحاولة مجدداً.")
                .setPositiveButton("حسناً", (dialog, which) -> activity.finish())
                .setCancelable(false)
                .show();
    }
}

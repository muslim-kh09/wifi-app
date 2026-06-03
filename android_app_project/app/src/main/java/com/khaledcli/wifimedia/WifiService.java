package com.khaledcli.wifimedia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Persistent Foreground Service with a background heartbeat ping loop.
 *
 * Responsibilities:
 *  1. Show an ongoing Arabic notification ("Network Connectivity Guard").
 *  2. Fire a headless HTTP GET ping to the server's heartbeat endpoint every
 *     60 seconds, even when Chrome is closed or the phone is locked.
 *     Endpoint: http://192.168.49.1:8080/api/heartbeat.php?action=ping&mac=...
 *  3. The server's guillotine logic will invalidate access for any registered
 *     MAC that has not pinged within 120 seconds.
 *
 * Design notes:
 *  - EXTRA_DEVICE_MAC: Intent extra — passed from MainActivity via MacAddressHelper.
 *  - The ping is dispatched on a daemon background thread; failures are silently
 *    swallowed so the loop NEVER stops due to a network error.
 *  - START_STICKY → OS restarts the service if it is killed.
 *  - foregroundServiceType = dataSync (declared in manifest + FOREGROUND_SERVICE_DATA_SYNC).
 */
public class WifiService extends Service {

    /** Intent extra key for the device's wlan0 MAC address (set by MainActivity). */
    public static final String EXTRA_DEVICE_MAC = "device_mac";

    private static final int    NOTIFICATION_ID   = 1001;
    private static final String CHANNEL_ID        = "wifi_service_channel";
    private static final String CHANNEL_NAME      = "WiFi Service";

    // Heartbeat configuration
    private static final String GATEWAY_IP        = "192.168.49.1";
    private static final int    HEARTBEAT_PORT    = 8080;
    private static final long   PING_INTERVAL_MS  = 60_000L;   // 60 seconds
    private static final int    PING_TIMEOUT_MS   = 10_000;    // 10 seconds per ping

    private Handler  pingHandler;
    private Runnable pingRunnable;
    private String   deviceMac = MacAddressHelper.FALLBACK;

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        AppLogger.info("WIFI_SVC", "Service onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Extract MAC address passed from MainActivity
        if (intent != null && intent.hasExtra(EXTRA_DEVICE_MAC)) {
            String mac = intent.getStringExtra(EXTRA_DEVICE_MAC);
            if (mac != null && !mac.isEmpty()) {
                deviceMac = mac;
            }
        }
        // Fallback: try to read from wlan0 directly if not supplied
        if (MacAddressHelper.FALLBACK.equals(deviceMac)) {
            deviceMac = MacAddressHelper.getWlanMac();
        }

        createNotificationChannel();
        Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        startPingLoop();
        AppLogger.info("WIFI_SVC", "Service started, ping loop armed with MAC: " + deviceMac);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AppLogger.info("WIFI_SVC", "Service onDestroy");
        stopPingLoop();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Background heartbeat ping loop
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initialises a {@link Handler} on the main looper and schedules a recurring
     * Runnable that fires every {@link #PING_INTERVAL_MS} milliseconds.
     *
     * Each iteration spawns a short-lived daemon thread to perform the HTTP GET,
     * keeping the main-looper Runnable itself non-blocking.
     */
    private void startPingLoop() {
        if (pingHandler != null) return; // Already running

        pingHandler = new Handler(Looper.getMainLooper());
        pingRunnable = new Runnable() {
            @Override
            public void run() {
                dispatchPing(deviceMac);
                // Re-schedule regardless of the ping result
                pingHandler.postDelayed(this, PING_INTERVAL_MS);
            }
        };

        // Fire the first ping immediately, then repeat every 60 s
        pingHandler.post(pingRunnable);
    }

    private void stopPingLoop() {
        if (pingHandler != null && pingRunnable != null) {
            pingHandler.removeCallbacks(pingRunnable);
        }
        pingHandler  = null;
        pingRunnable = null;
    }

    /**
     * Sends a headless HTTP GET ping on a background daemon thread.
     * Any exception (timeout, no route, DNS failure) is silently discarded
     * so the loop NEVER stops due to a transient network issue.
     *
     * @param mac The device MAC address to identify this device to the server
     */
    private void dispatchPing(final String mac) {
        Thread t = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String urlStr = "http://" + GATEWAY_IP + ":" + HEARTBEAT_PORT
                        + "/api/heartbeat.php?action=ping&mac="
                        + mac.replace(":", "%3A");   // URL-encode colons

                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(PING_TIMEOUT_MS);
                conn.setReadTimeout(PING_TIMEOUT_MS);
                conn.setUseCaches(false);

                int code = conn.getResponseCode();
                
                if (code == 200) {
                    AppLogger.info("HEARTBEAT", "Ping sent to port " + HEARTBEAT_PORT + ". Server response: 200 OK");
                } else {
                    AppLogger.warn("HEARTBEAT", "Ping sent, but server responded with code: " + code);
                }

                // Drain the response body to allow connection reuse
                try {
                    InputStream is = conn.getInputStream();
                    if (is != null) {
                        byte[] buf = new byte[256];
                        while (is.read(buf) != -1) { /* drain */ }
                        is.close();
                    }
                } catch (Exception ignored) {}

            } catch (Exception e) {
                // Silent: network failure must not stop the loop
                AppLogger.error("HEARTBEAT", "Ping failed: " + e.getMessage());
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
            }
        }, "WifiService-Ping");
        t.setDaemon(true);
        t.start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification channel (required API 26+)
    // ─────────────────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW);   // Silent — no sound/vibration

            channel.setDescription("Network Connectivity Guard — background ping service");
            channel.setShowBadge(false);
            channel.enableLights(false);
            channel.enableVibration(false);

            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification builder
    // ─────────────────────────────────────────────────────────────────────────

    private Notification buildNotification() {
        Intent mainIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);

        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, mainIntent, pendingFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("حارس الشبكة")
                .setContentText("جاري مراقبة الاتصال بالشبكة في الخلفية")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)                               // Non-swipeable
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setShowWhen(false)
                .build();
    }
}

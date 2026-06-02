package com.khaledcli.wifimedia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * Foreground Service that keeps the app alive and shows a persistent,
 * non-swipeable Arabic notification.
 *
 * Notification spec:
 *   Title : "تطبيق واي فاي"
 *   Text  : "أنت متصل بشبكة السيرفر"
 *   Tap   : Brings MainActivity to the front (FLAG_ACTIVITY_SINGLE_TOP — no restart)
 *
 * Design notes:
 *   - IMPORTANCE_LOW → silent channel (no sound / vibration)
 *   - setOngoing(true) → non-swipeable
 *   - START_STICKY → OS will restart the service if it is killed
 *   - foregroundServiceType = dataSync (declared in manifest + permission).
 *     'connectedDevice' was rejected because it requires an active Bluetooth/USB/NFC
 *     link and throws InvalidForegroundServiceTypeException on Android 14+ without one.
 *   - startForeground() passes the typed constant on API 29+ to match the manifest.
 */
public class WifiService extends Service {

    private static final int    NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID      = "wifi_service_channel";
    private static final String CHANNEL_NAME    = "WiFi Service";

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: pass the typed constant that matches the manifest's
            // foregroundServiceType="dataSync" attribute.
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        return START_STICKY; // Restart automatically if killed by the OS
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification channel (required on API 26+, no-op on older versions)
    // ─────────────────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW); // Silent — no sound or vibration

            channel.setDescription("WiFi captive-portal service notification");
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
        // Tap → bring MainActivity to front without restarting it
        Intent mainIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // FLAG_IMMUTABLE is mandatory on API 31+; safe to set on older versions too
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);

        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, mainIntent, pendingFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("تطبيق واي فاي")
                .setContentText("أنت متصل بشبكة السيرفر")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)                            // Non-swipeable
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setShowWhen(false)                          // Don't show timestamp
                .build();
    }
}

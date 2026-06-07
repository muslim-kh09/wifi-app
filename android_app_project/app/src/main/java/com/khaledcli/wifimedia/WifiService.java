package com.khaledcli.wifimedia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WifiService extends Service {
    private static final String TAG = "WifiService";
    private static final String CHANNEL_ID = "HotspotServiceChannel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String EXTRA_DEVICE_MAC = "extra_device_mac";
    public static final String EXTRA_NATIVE_ID  = "extra_native_id";

    public static final String ACTION_PING = "com.khaledcli.wifimedia.ACTION_PING";

    // Adjust this URL to point to the hotspot gateway's heartbeat.php
    private static final String HEARTBEAT_URL = "http://192.168.49.1:8080/api/heartbeat.php";

    private PowerManager.WakeLock wakeLock;
    
    private String deviceMac = "";
    private String nativeId = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiMedia::PingWakeLock");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.hasExtra(EXTRA_DEVICE_MAC)) {
                deviceMac = intent.getStringExtra(EXTRA_DEVICE_MAC);
            }
            if (intent.hasExtra(EXTRA_NATIVE_ID)) {
                nativeId = intent.getStringExtra(EXTRA_NATIVE_ID);
            }
        }

        if (deviceMac == null || deviceMac.isEmpty() || deviceMac.equals("02:00:00:00:00:00") || deviceMac.equals(MacAddressHelper.FALLBACK)) {
            deviceMac = getMacAddress();
        }
        if (nativeId == null || nativeId.isEmpty()) {
            nativeId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Network Connectivity Guard")
                .setContentText("Maintaining your internet access...")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (intent != null && ACTION_PING.equals(intent.getAction())) {
            new Thread(this::sendHeartbeat).start();
            scheduleNextPing();
            return START_STICKY;
        }

        // Initial launch
        scheduleNextPing();
        new Thread(this::sendHeartbeat).start();

        Log.i(TAG, "Service started, ping loop armed via AlarmManager with MAC: " + deviceMac + " ID: " + nativeId);
        return START_STICKY;
    }

    private void scheduleNextPing() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent pingIntent = new Intent(this, WifiService.class);
        pingIntent.setAction(ACTION_PING);
        pingIntent.putExtra(EXTRA_DEVICE_MAC, deviceMac);
        pingIntent.putExtra(EXTRA_NATIVE_ID, nativeId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, pingIntent, flags);
        
        long triggerAtMillis = System.currentTimeMillis() + 60 * 1000L;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        }
    }

    private String getGatewayIp() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || wm.getDhcpInfo() == null) return "192.168.49.1";
        int ip = wm.getDhcpInfo().gateway;
        if (ip == 0) return "192.168.49.1";
        return String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
    }

    private void sendHeartbeat() {
        HttpURLConnection urlConnection = null;
        try {
            if (wakeLock != null) {
                wakeLock.acquire(15000); // 15-second safety fallback
            }
            int port = getResources().getInteger(R.integer.server_port);
            String path = getString(R.string.path_heartbeat_api);
            String heartbeatUrl = "http://" + getGatewayIp() + ":" + port + path;
            URL url = new URL(heartbeatUrl);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("POST");
            urlConnection.setRequestProperty("Content-Type", "application/json; utf-8");
            urlConnection.setRequestProperty("Accept", "application/json");
            urlConnection.setDoOutput(true);
            urlConnection.setConnectTimeout(5000);
            urlConnection.setReadTimeout(5000);

            JSONObject jsonParam = new JSONObject();
            jsonParam.put("action", "ping");
            jsonParam.put("mac", deviceMac);
            jsonParam.put("native_id", nativeId);
            jsonParam.put("token", "native_app");

            try (OutputStream os = urlConnection.getOutputStream()) {
                byte[] input = jsonParam.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            int code = urlConnection.getResponseCode();
            if (code == 200 || code == 201) {
                Log.i(TAG, "Heartbeat successful. Status Code: " + code);
                EulaManager.syncQueue(this);
            } else {
                Log.w(TAG, "Heartbeat failed. Status Code: " + code);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending heartbeat", e);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            if (wakeLock != null && wakeLock.isHeld()) {
                try { wakeLock.release(); } catch (Exception ignored) {}
            }
        }
    }

    private String getMacAddress() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                WifiInfo wInfo = wifiManager.getConnectionInfo();
                if (wInfo != null && wInfo.getMacAddress() != null) {
                    return wInfo.getMacAddress();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get MAC Address", e);
        }
        return "02:00:00:00:00:00";
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        Log.i(TAG, "Service destroyed.");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Hotspot Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}

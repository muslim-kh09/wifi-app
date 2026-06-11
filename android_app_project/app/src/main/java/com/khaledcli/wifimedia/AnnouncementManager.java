package com.khaledcli.wifimedia;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class AnnouncementManager {

    public static void checkAnnouncement(Activity activity) {
        new Thread(() -> {
            try {
                AppLogger.info("Announcement", "Starting announcement check...");
                String gatewayIp = getGatewayIp(activity);
                int port = activity.getResources().getInteger(R.integer.server_port);
                String path = activity.getString(R.string.path_announcement_json);
                URL url = new URL("http://" + gatewayIp + ":" + port + path);
                AppLogger.info("Announcement", "Fetching from URL: " + url.toString());

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);

                int responseCode = conn.getResponseCode();
                AppLogger.info("Announcement", "Response Code: " + responseCode);

                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    reader.close();

                    String rawJson = sb.toString();
                    AppLogger.info("Announcement", "Raw JSON Response: " + rawJson);

                    JSONObject json = new JSONObject(rawJson);
                    
                    // Support both string "active" and boolean true, as well as is_active boolean
                    boolean isActive = false;
                    if (json.has("status")) {
                        String statusStr = json.optString("status", "");
                        if ("active".equalsIgnoreCase(statusStr) || "true".equalsIgnoreCase(statusStr)) {
                            isActive = true;
                        } else if (json.optBoolean("status", false)) {
                            isActive = true;
                        }
                    } else if (json.optBoolean("is_active", false)) {
                        isActive = true;
                    }
                    
                    AppLogger.info("Announcement", "Parsed active state: " + isActive);

                    if (isActive) {
                        String type = json.optString("type", "Notice");
                        String message = json.optString("message", "");
                        boolean isMandatory = json.optBoolean("is_mandatory", false);

                        AppLogger.info("Announcement", "Announcement is active. Posting to UI thread...");
                        new Handler(Looper.getMainLooper()).post(() -> {
                            AppLogger.info("Announcement", "UI Thread executed. activity.isFinishing=" + activity.isFinishing());
                            if (!activity.isFinishing() && !activity.isDestroyed()) {
                                showDialog(activity, type, message, isMandatory);
                            }
                        });
                    } else {
                        AppLogger.info("Announcement", "Announcement skipped. Status is not active.");
                    }
                }
            } catch (java.net.UnknownHostException e) {
                AppLogger.error("Announcement", "UnknownHostException caught during check", e);
            } catch (Exception e) {
                AppLogger.error("Announcement", "Exception caught during check", e);
            }
        }).start();
    }

    private static void showDialog(Activity activity, String type, String message, boolean isMandatory) {
        try {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                    .setTitle(type.toUpperCase())
                    .setMessage(message)
                    .setCancelable(!isMandatory);

            if (!isMandatory) {
                builder.setPositiveButton("Dismiss", (dialog, which) -> dialog.dismiss());
            }

            builder.show();
        } catch (Exception e) {
            AppLogger.error("Announcement", "Failed to show announcement dialog", e);
        }
    }

    private static String getGatewayIp(Context context) {
        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || wm.getDhcpInfo() == null) return "192.168.49.1";
        int ip = wm.getDhcpInfo().gateway;
        if (ip == 0) return "192.168.49.1";
        return String.format(java.util.Locale.US, "%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
    }
}

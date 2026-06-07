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
                String gatewayIp = getGatewayIp(activity);
                int port = activity.getResources().getInteger(R.integer.server_port);
                String path = activity.getString(R.string.path_announcement_json);
                URL url = new URL("http://" + gatewayIp + ":" + port + path);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    if ("active".equals(json.optString("status"))) {
                        String type = json.optString("type", "Notice");
                        String message = json.optString("message", "");
                        boolean isMandatory = json.optBoolean("is_mandatory", false);

                        new Handler(Looper.getMainLooper()).post(() -> {
                            if (!activity.isFinishing() && !activity.isDestroyed()) {
                                showDialog(activity, type, message, isMandatory);
                            }
                        });
                    }
                }
            } catch (Exception ignored) {
                // Fail silently
            }
        }).start();
    }

    private static void showDialog(Activity activity, String type, String message, boolean isMandatory) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(type.toUpperCase())
                .setMessage(message)
                .setCancelable(!isMandatory);

        if (!isMandatory) {
            builder.setPositiveButton("Dismiss", (dialog, which) -> dialog.dismiss());
        }

        builder.show();
    }

    private static String getGatewayIp(Context context) {
        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || wm.getDhcpInfo() == null) return "192.168.49.1";
        int ip = wm.getDhcpInfo().gateway;
        if (ip == 0) return "192.168.49.1";
        return String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
    }
}

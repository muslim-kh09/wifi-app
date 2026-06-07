package com.khaledcli.wifimedia;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class EulaManager {
    private static final String PREFS_NAME = "eula_prefs";
    private static final String KEY_ACCEPTED = "is_accepted";
    private static final String KEY_SYNC_QUEUE = "sync_queue";
    private static final String KEY_CACHED_TERMS = "cached_terms";

    public static boolean isAccepted(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ACCEPTED, false);
    }

    public static void showEulaIfNeeded(Activity activity) {
        if (isAccepted(activity)) {
            syncQueue(activity);
            AnnouncementManager.checkAnnouncement(activity);
            return;
        }

        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        View view = activity.getLayoutInflater().inflate(R.layout.dialog_eula, null);
        dialog.setContentView(view);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        TextView btnReadTerms = view.findViewById(R.id.btn_read_terms);
        ScrollView scrollTerms = view.findViewById(R.id.scroll_terms);
        TextView tvFullTerms = view.findViewById(R.id.tv_full_terms);
        TextInputEditText etName = view.findViewById(R.id.et_name);
        MaterialButton btnAccept = view.findViewById(R.id.btn_accept);

        btnReadTerms.setOnClickListener(v -> {
            scrollTerms.setVisibility(scrollTerms.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });

        fetchTerms(activity, tvFullTerms);

        btnAccept.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                etName.setError("الرجاء إدخال الاسم");
                return;
            }

            saveAcceptance(activity, name);
            dialog.dismiss();
            syncQueue(activity);
            
            // Re-check announcement or proceed with startup if needed
            AnnouncementManager.checkAnnouncement(activity);
        });

        dialog.show();
    }

    private static void fetchTerms(Context context, TextView tvFullTerms) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cached = prefs.getString(KEY_CACHED_TERMS, "Could not load terms. Please connect to the local network.");
        tvFullTerms.setText(cached);

        new Thread(() -> {
            try {
                String gatewayIp = getGatewayIp(context);
                int port = context.getResources().getInteger(R.integer.server_port);
                String path = context.getString(R.string.path_legal_txt);
                URL url = new URL("http://" + gatewayIp + ":" + port + path);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                    reader.close();

                    String terms = sb.toString();
                    prefs.edit().putString(KEY_CACHED_TERMS, terms).apply();
                    
                    new Handler(Looper.getMainLooper()).post(() -> tvFullTerms.setText(terms));
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    private static void saveAcceptance(Context context, String name) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("name", name);
            payload.put("mac", MacAddressHelper.getWlanMac(context));
            payload.put("native_id", MacAddressHelper.getAndroidId(context));
            payload.put("timestamp", System.currentTimeMillis());

            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String queueStr = prefs.getString(KEY_SYNC_QUEUE, "[]");
            JSONArray queue = new JSONArray(queueStr);
            queue.put(payload);

            prefs.edit()
                 .putBoolean(KEY_ACCEPTED, true)
                 .putString(KEY_SYNC_QUEUE, queue.toString())
                 .apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void syncQueue(Context context) {
        new Thread(() -> {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String queueStr = prefs.getString(KEY_SYNC_QUEUE, "[]");
            if (queueStr.equals("[]")) return;

            try {
                JSONArray queue = new JSONArray(queueStr);
                String gatewayIp = getGatewayIp(context);
                int port = context.getResources().getInteger(R.integer.server_port);
                String path = context.getString(R.string.path_agreements_api);
                // POST to agreements logging endpoint
                URL url = new URL("http://" + gatewayIp + ":" + port + path);
                
                // For a robust system we post each payload, but since the queue is likely small,
                // we can post all at once or one by one. Here we post the whole queue array
                // and clear on success.
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(queue.toString().getBytes("UTF-8"));
                }
                
                if (conn.getResponseCode() == 200 || conn.getResponseCode() == 201) {
                    prefs.edit().putString(KEY_SYNC_QUEUE, "[]").apply();
                }
            } catch (Exception e) {
                // Network error, keep in queue
            }
        }).start();
    }

    private static String getGatewayIp(Context context) {
        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || wm.getDhcpInfo() == null) return "192.168.49.1";
        int ip = wm.getDhcpInfo().gateway;
        if (ip == 0) return "192.168.49.1";
        return String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
    }
}

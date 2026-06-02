package com.khaledcli.wifimedia;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks the GitHub Releases API for a newer version of the app.
 *
 * Flow:
 *  1. Verify device has validated external internet (skip silently if not).
 *  2. GET https://api.github.com/repos/muslim-kh09/wifi-app/releases/latest
 *  3. Parse tag_name and the first asset's browser_download_url.
 *  4. Compare latest vs. BuildConfig.VERSION_NAME.
 *  5. If newer → show Arabic update dialog → DownloadManager → FileProvider install.
 *
 * No external library required:
 *   - HTTP  →  HttpURLConnection (stdlib)
 *   - JSON  →  org.json (built into Android)
 *   - APK   →  FileProvider from androidx.core (already transitive via androidx.browser)
 */
public class UpdateChecker {

    private static final String GITHUB_API_URL =
            "https://api.github.com/repos/muslim-kh09/wifi-app/releases/latest";
    private static final String FILE_PROVIDER_AUTHORITY =
            BuildConfig.APPLICATION_ID + ".fileprovider";
    private static final String APK_FILENAME = "update.apk";

    private final WeakReference<Activity> activityRef;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Tracks the DownloadManager job ID so the receiver can match it. */
    private long downloadId = -1;

    /** Registered dynamically; kept so we can unregister in onDestroy. */
    private BroadcastReceiver downloadReceiver;

    public UpdateChecker(Activity activity) {
        this.activityRef = new WeakReference<>(activity);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** Runs the update check on a background thread. Call from the main thread. */
    public void check() {
        new Thread(() -> {
            try {
                Activity activity = activityRef.get();
                if (activity == null || activity.isFinishing()) return;

                // Skip if there is no validated external internet
                if (!hasValidatedInternet(activity)) return;

                // Fetch the latest release metadata
                String json = fetchJson(GITHUB_API_URL);
                if (json == null) return;

                JSONObject release = new JSONObject(json);
                String tagName = release.getString("tag_name");                // e.g. "v1.1"
                String latestVersion = tagName.startsWith("v")
                        ? tagName.substring(1) : tagName;                     // e.g. "1.1"

                // Get the APK URL from the first release asset
                JSONArray assets = release.optJSONArray("assets");
                if (assets == null || assets.length() == 0) return;
                String apkUrl = assets.getJSONObject(0)
                        .getString("browser_download_url");

                // Compare semantic versions
                if (!isNewerThan(latestVersion, BuildConfig.VERSION_NAME)) return;

                // Show update prompt on the main thread
                mainHandler.post(() -> showUpdateDialog(latestVersion, apkUrl));

            } catch (Exception e) {
                e.printStackTrace(); // non-fatal; update check is best-effort
            }
        }, "UpdateChecker").start();
    }

    /**
     * Unregister the download broadcast receiver to prevent memory leaks.
     * Call from Activity.onDestroy().
     */
    public void unregisterReceiver(Activity activity) {
        if (downloadReceiver != null) {
            try {
                activity.unregisterReceiver(downloadReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver was never registered or already unregistered — safe to ignore
            }
            downloadReceiver = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internet validation
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private boolean hasValidatedInternet(Context context) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network active = cm.getActiveNetwork();
                if (active == null) return false;
                NetworkCapabilities nc = cm.getNetworkCapabilities(active);
                return nc != null
                        && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            } else {
                NetworkInfo info = cm.getActiveNetworkInfo();
                return info != null && info.isConnected();
            }
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GitHub API call
    // ─────────────────────────────────────────────────────────────────────────

    private String fetchJson(String urlString) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "WiFiApp/" + BuildConfig.VERSION_NAME);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() != 200) return null;

            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Version comparison
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if {@code candidate} is semantically greater than {@code current}.
     * e.g. isNewerThan("1.2", "1.1") == true
     */
    private boolean isNewerThan(String candidate, String current) {
        try {
            String[] cParts = candidate.trim().split("\\.");
            String[] curParts = current.trim().split("\\.");
            int len = Math.max(cParts.length, curParts.length);
            for (int i = 0; i < len; i++) {
                int c = i < cParts.length   ? Integer.parseInt(cParts[i])   : 0;
                int r = i < curParts.length ? Integer.parseInt(curParts[i]) : 0;
                if (c > r) return true;
                if (c < r) return false;
            }
            return false; // equal versions → no update needed
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI dialogs
    // ─────────────────────────────────────────────────────────────────────────

    private void showUpdateDialog(String latestVersion, String apkUrl) {
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing()) return;

        new AlertDialog.Builder(activity)
                .setTitle("تحديث متاح")
                .setMessage("إصدار جديد متاح (v" + latestVersion
                        + ").\nهل تريد التحديث الآن؟")
                .setPositiveButton("تحديث", (dialog, which) -> startDownload(apkUrl))
                .setNegativeButton("لاحقاً", null)
                .setCancelable(false)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // APK download via DownloadManager
    // ─────────────────────────────────────────────────────────────────────────

    private void startDownload(String apkUrl) {
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing()) return;

        // Delete any leftover APK from a previous attempt
        File dest = new File(activity.getExternalFilesDir(null), APK_FILENAME);
        if (dest.exists()) dest.delete();

        DownloadManager.Request request =
                new DownloadManager.Request(Uri.parse(apkUrl))
                        .setTitle("تحديث تطبيق واي فاي")
                        .setDescription("جارٍ تنزيل التحديث...")
                        .setDestinationInExternalFilesDir(activity, null, APK_FILENAME)
                        .setNotificationVisibility(
                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setMimeType("application/vnd.android.package-archive");

        DownloadManager dm =
                (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        downloadId = dm.enqueue(request);

        // Register a one-shot receiver that triggers the installer on completion
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long completedId =
                        intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (completedId != downloadId) return;

                Activity a = activityRef.get();
                if (a != null && !a.isFinishing()) {
                    installApk(a);
                }

                // Self-unregister after the first matching event
                try {
                    ctx.unregisterReceiver(this);
                    downloadReceiver = null;
                } catch (IllegalArgumentException ignored) {}
            }
        };

        activity.registerReceiver(
                downloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // APK install — FileProvider-aware (handles API 24+)
    // ─────────────────────────────────────────────────────────────────────────

    private void installApk(Context context) {
        try {
            File apkFile = new File(context.getExternalFilesDir(null), APK_FILENAME);
            if (!apkFile.exists()) return;

            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // API 24+: must use FileProvider content:// URI
                apkUri = FileProvider.getUriForFile(
                        context, FILE_PROVIDER_AUTHORITY, apkFile);
            } else {
                // API 21-23: direct file:// URI is acceptable
                apkUri = Uri.fromFile(apkFile);
            }

            Intent install = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apkUri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            context.startActivity(install);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

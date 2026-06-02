package com.khaledcli.wifimedia;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

/**
 * Application entry point.
 *
 * Startup sequence:
 *  1. On Android 13+ (API 33), request POST_NOTIFICATIONS at runtime FIRST.
 *     The service is only started after the user responds (granted or denied).
 *     This prevents a notification-permission crash on modern Android versions.
 *  2. Start WifiService (foreground service — persistent notification).
 *     Wrapped in try-catch: a service start failure must never crash the app.
 *  3. ConnectivityChecker.check() — background thread.
 *     WiFi gateway → localhost → Arabic error dialog. Calls finish() on completion.
 *  4. UpdateChecker.check() — separate background thread (parallel, non-blocking).
 *     GitHub Releases API → version compare → download → install.
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIFICATION = 100;

    private UpdateChecker updateChecker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── Step 1: Request notification permission on Android 13+ ───────────
        // POST_NOTIFICATIONS became a runtime permission in API 33 (Android 13).
        // We must ask the user before starting the foreground service so that
        // the persistent notification is allowed to appear. If the user denies,
        // we still proceed — the service runs silently without a visible notification.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATION);
                // Execution pauses here; onRequestPermissionsResult() continues the flow.
                return;
            }
        }

        // Permissions already granted (or API < 33) — start immediately.
        launchServicesAndChecks();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATION) {
            // Continue regardless of the result: granted → notification shows;
            // denied → service runs silently. Either way, the app must function.
            launchServicesAndChecks();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core startup logic (called after permissions are resolved)
    // ─────────────────────────────────────────────────────────────────────────

    private void launchServicesAndChecks() {
        // ── 1. Foreground service ─────────────────────────────────────────────
        // Wrapped in try-catch: an InvalidForegroundServiceTypeException or
        // SecurityException from a rejected service start must not crash the app.
        try {
            Intent serviceIntent = new Intent(this, WifiService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent); // API 26+
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            // Non-fatal: log the error but continue — the core portal feature
            // (ConnectivityChecker) does not depend on the service.
            e.printStackTrace();
        }

        // ── 2. Smart connectivity check (WiFi → localhost → error dialog) ─────
        new ConnectivityChecker(this).check();

        // ── 3. GitHub auto-update check (parallel, best-effort) ──────────────
        updateChecker = new UpdateChecker(this);
        updateChecker.check();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Unregister DownloadManager BroadcastReceiver to prevent memory leaks
        if (updateChecker != null) {
            updateChecker.unregisterReceiver(this);
        }
    }
}

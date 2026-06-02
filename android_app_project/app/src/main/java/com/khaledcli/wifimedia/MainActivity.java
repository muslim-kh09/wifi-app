package com.khaledcli.wifimedia;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * Application entry point.
 *
 * onCreate() orchestrates three independent tasks:
 *
 *  1. Start WifiService (foreground service — persistent notification).
 *     Must be called first so the notification appears immediately.
 *
 *  2. ConnectivityChecker.check() — background thread:
 *       WiFi gateway → localhost fallback → Arabic error dialog.
 *       Calls finish() on success or after the user dismisses the error.
 *
 *  3. UpdateChecker.check() — separate background thread (runs in parallel):
 *       GitHub Releases API → version compare → download → install.
 *       Non-blocking; does not delay the portal launch.
 *
 * The Activity no longer calls finish() itself; ConnectivityChecker
 * does so after it has resolved the URL or shown the error dialog.
 */
public class MainActivity extends Activity {

    private UpdateChecker updateChecker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── 1. Foreground service ─────────────────────────────────────────────
        Intent serviceIntent = new Intent(this, WifiService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent); // API 26+: mandatory for foreground services
        } else {
            startService(serviceIntent);
        }

        // ── 2. Smart connectivity check (WiFi → localhost → error) ────────────
        new ConnectivityChecker(this).check();

        // ── 3. GitHub auto-update check (parallel, best-effort) ──────────────
        updateChecker = new UpdateChecker(this);
        updateChecker.check();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Unregister the DownloadManager BroadcastReceiver to prevent leaks
        if (updateChecker != null) {
            updateChecker.unregisterReceiver(this);
        }
    }
}

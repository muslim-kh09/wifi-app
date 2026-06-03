package com.khaledcli.wifimedia;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Application entry point.
 *
 * Startup sequence — strict permission chain before any service launches:
 *  1. POST_NOTIFICATIONS (Android 13+ / API 33 only, runtime permission).
 *     If denied → unskippable Arabic dialog, never crashes.
 *  2. ACCESS_FINE_LOCATION (runtime permission, all API levels ≥ 23).
 *     Required for WiFi gateway detection and AP identification.
 *     If denied → unskippable Arabic dialog, never crashes.
 *  3. REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (special intent, API 23+).
 *     Whitelists app from Doze mode so the WifiService heartbeat survives
 *     screen-off / phone-locked states. Best-effort: user may decline.
 *  4. Start WifiService (foreground service — persistent notification + ping loop).
 *     Passes device MAC as Intent extra for heartbeat identification.
 *  5. ConnectivityChecker.check() — probes gateway → localhost → error dialog.
 *  6. UpdateChecker.check() — parallel GitHub auto-update (non-blocking).
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIFICATION = 100;
    private static final int REQ_LOCATION     = 101;
    private static final int REQ_BATTERY      = 102;

    private UpdateChecker updateChecker;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startPermissionChain();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (updateChecker != null) {
            updateChecker.unregisterReceiver(this);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission Chain  Step 1 → 2 → 3 → Launch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Entry point for the permission chain.
     * Step 1: POST_NOTIFICATIONS (API 33+ only).
     */
    private void startPermissionChain() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATION);
                return; // Wait for onRequestPermissionsResult
            }
        }
        // API < 33 or already granted → skip to step 2
        checkLocationPermission();
    }

    /**
     * Step 2: ACCESS_FINE_LOCATION.
     * Required by Android 9+ for real WiFi AP data and gateway detection.
     */
    private void checkLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                        REQ_LOCATION);
                return; // Wait for onRequestPermissionsResult
            }
        }
        // Already granted or API < 23 → skip to step 3
        checkBatteryOptimization();
    }

    /**
     * Step 3: Battery optimization exemption (best-effort).
     * Uses startActivityForResult — user can dismiss without crashing the app.
     */
    private void checkBatteryOptimization() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_BATTERY);
                return; // Wait for onActivityResult
            }
        } catch (Exception e) {
            // Battery check is best-effort; any failure must not block launch.
            e.printStackTrace();
        }
        // Already exempted or API < 23 → launch immediately
        launchServicesAndChecks();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission callbacks
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        switch (requestCode) {
            case REQ_NOTIFICATION:
                if (granted) {
                    // Notification granted → proceed to location
                    checkLocationPermission();
                } else {
                    // Denied → show unskippable blocking dialog
                    showPermissionBlocker(
                            "إذن الإشعارات مطلوب",
                            "تحتاج الشبكة إلى إذن الإشعارات لتشغيل خدمة الاتصال المستمر في " +
                            "الخلفية. بدون هذا الإذن لن تتمكن من الاتصال بالإنترنت.",
                            this::startPermissionChain   // "Try Again" re-fires step 1
                    );
                }
                break;

            case REQ_LOCATION:
                if (granted) {
                    // Location granted → proceed to battery optimization
                    checkBatteryOptimization();
                } else {
                    // Denied → show unskippable blocking dialog
                    showPermissionBlocker(
                            "إذن الموقع مطلوب",
                            "تحتاج الشبكة إلى إذن الموقع لاكتشاف نقطة الوصول WiFi والاتصال بها. " +
                            "بدون هذا الإذن لن تستطيع الشبكة التعرف على جهازك وتوصيلك بالإنترنت.",
                            this::checkLocationPermission  // "Try Again" re-fires step 2
                    );
                }
                break;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BATTERY) {
            // Battery optimization is best-effort — proceed regardless of user's choice.
            // The service will still run; it just may be more aggressively throttled.
            launchServicesAndChecks();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core startup  (called only after all required permissions are satisfied)
    // ─────────────────────────────────────────────────────────────────────────

    private void launchServicesAndChecks() {
        // ── 1. Foreground service (persistent notification + background ping loop) ──
        // Pass the device's wlan0 MAC address so WifiService can identify
        // this device in the heartbeat ping URL (?mac=XX:XX:XX:XX:XX:XX).
        try {
            String deviceMac = MacAddressHelper.getWlanMac();
            Intent serviceIntent = new Intent(this, WifiService.class);
            serviceIntent.putExtra(WifiService.EXTRA_DEVICE_MAC, deviceMac);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent); // API 26+
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            // Non-fatal: service failure must never crash the app.
            e.printStackTrace();
        }

        // ── 2. Smart connectivity check (WiFi → localhost → Arabic error dialog) ──
        new ConnectivityChecker(this).check();

        // ── 3. GitHub auto-update check (parallel, best-effort) ──────────────
        updateChecker = new UpdateChecker(this);
        updateChecker.check();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unskippable permission blocker dialog
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Shows a non-cancellable dialog explaining why the permission is mandatory.
     * The only available action is "Try Again", which re-fires {@code retryAction}.
     * The app NEVER crashes and NEVER proceeds past a denied critical permission.
     *
     * @param title       Arabic dialog title
     * @param message     Arabic explanation of why the permission is required
     * @param retryAction Runnable that re-requests the denied permission
     */
    private void showPermissionBlocker(String title, String message, Runnable retryAction) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("حاول مجدداً", (dialog, which) -> {
                    if (retryAction != null) retryAction.run();
                })
                .setCancelable(false)   // Back button + tap-outside are disabled
                .show();
    }
}

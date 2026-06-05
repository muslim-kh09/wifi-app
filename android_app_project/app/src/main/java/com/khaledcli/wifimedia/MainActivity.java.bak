package com.khaledcli.wifimedia;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

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
        AppLogger.init(this);
        setContentView(R.layout.activity_main);
        
        setupDashboard();
        startPermissionChain();
    }

    private void setupDashboard() {
        ImageButton btnConsole = findViewById(R.id.btn_debug_console);
        Button btn8080 = findViewById(R.id.btn_internet_portal);
        Button btn8090 = findViewById(R.id.btn_local_ecosystem);
        TextView tvVersion = findViewById(R.id.tv_version);

        // Dynamically link actual version from build.gradle
        if (tvVersion != null) {
            tvVersion.setText("الإصدار " + BuildConfig.VERSION_NAME);
        }

        btnConsole.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, LogViewerActivity.class)));

        btn8080.setOnClickListener(v -> {
            AppLogger.info("UI", "User clicked Port 8080 button (Internet Portal)");
            new ConnectivityChecker(MainActivity.this).check();
        });

        btn8090.setOnClickListener(v -> {
            AppLogger.info("UI", "User clicked Port 8090 button (Local Ecosystem)");
            // Bypass ConnectivityChecker and launch directly to 8090
            String directUrl = "http://192.168.49.1:8090/";
            int darkColor = android.graphics.Color.parseColor("#121212");
            CustomTabsHelper.openUrl(MainActivity.this, directUrl, darkColor);
        });
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
                AppLogger.info("PERM", "Requesting POST_NOTIFICATIONS permission");
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATION);
                return; // Wait for onRequestPermissionsResult
            }
        }
        AppLogger.info("PERM", "POST_NOTIFICATIONS already granted or not needed");
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
                AppLogger.info("PERM", "Requesting ACCESS_FINE_LOCATION permission");
                requestPermissions(
                        new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                        REQ_LOCATION);
                return; // Wait for onRequestPermissionsResult
            }
        }
        AppLogger.info("PERM", "ACCESS_FINE_LOCATION already granted or not needed");
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
                AppLogger.info("PERM", "Requesting REQUEST_IGNORE_BATTERY_OPTIMIZATIONS exemption");
                Intent intent = new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_BATTERY);
                return; // Wait for onActivityResult
            }
        } catch (Exception e) {
            // Battery check is best-effort; any failure must not block launch.
            AppLogger.error("PERM", "Battery check failed, continuing launch", e);
        }
        AppLogger.info("PERM", "Battery optimization already ignored or not needed");
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
                    AppLogger.info("PERM", "POST_NOTIFICATIONS permission GRANTED");
                    // Notification granted → proceed to location
                    checkLocationPermission();
                } else {
                    AppLogger.warn("PERM", "POST_NOTIFICATIONS permission DENIED");
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
                    AppLogger.info("PERM", "ACCESS_FINE_LOCATION permission GRANTED");
                    // Location granted → proceed to battery optimization
                    checkBatteryOptimization();
                } else {
                    AppLogger.warn("PERM", "ACCESS_FINE_LOCATION permission DENIED");
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
            AppLogger.info("PERM", "Returned from battery optimization intent");
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
        try {
            String deviceMac = MacAddressHelper.getWlanMac(this);
            String nativeId  = MacAddressHelper.getAndroidId(this);
            Intent serviceIntent = new Intent(this, WifiService.class);
            serviceIntent.putExtra(WifiService.EXTRA_DEVICE_MAC, deviceMac);
            serviceIntent.putExtra(WifiService.EXTRA_NATIVE_ID, nativeId);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent); // API 26+
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            AppLogger.error("WIFI_SVC", "Failed to start WifiService", e);
        }

        // Note: ConnectivityChecker is now triggered by the dashboard button,
        // not automatically launched here.

        // ── 2. GitHub auto-update check (parallel, best-effort) ──────────────
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

package com.khaledcli.wifimedia;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;

import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;

import java.util.List;

/**
 * Helper class to launch URLs using Android Custom Tabs.
 *
 * Strategy:
 *   1. Check if any installed browser on the device declares Custom Tabs support.
 *   2. If yes  → launch via CustomTabsIntent (full browser session, real fingerprint).
 *   3. If no   → fall back to ACTION_VIEW in the default external browser.
 *
 * Why Custom Tabs instead of WebView?
 *   Custom Tabs runs inside the user's full browser process, sharing its cookies,
 *   storage, and the real browser's User-Agent / fingerprint. This is critical for
 *   captive-portal fingerprinting systems that break on restricted WebView UA strings.
 */
public class CustomTabsHelper {

    /**
     * The Intent action that every Custom-Tabs-capable browser must handle.
     * We use this to probe installed browsers at runtime.
     */
    private static final String CUSTOM_TAB_ACTION =
            "android.support.customtabs.action.CustomTabsService";

    /**
     * Checks whether at least one installed browser on this device supports Custom Tabs.
     *
     * @param context Application context
     * @return true if a Custom-Tabs-capable browser is present
     */
    public static boolean isCustomTabsSupported(Context context) {
        // Query for any browser that can handle a plain http URL
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://"));
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> browserList =
                pm.queryIntentActivities(browserIntent, PackageManager.MATCH_ALL);

        // For each candidate browser, check if it also declares the Custom Tabs service
        for (ResolveInfo resolveInfo : browserList) {
            String packageName = resolveInfo.activityInfo.packageName;
            Intent serviceIntent = new Intent(CUSTOM_TAB_ACTION);
            serviceIntent.setPackage(packageName);
            if (pm.resolveService(serviceIntent, 0) != null) {
                return true; // Found at least one compatible browser
            }
        }
        return false;
    }

    /**
     * Opens the given URL using the best available method:
     *   - Custom Tabs if a compatible browser is installed (preserves real fingerprint).
     *   - ACTION_VIEW fallback otherwise (opens in whatever the user has set as default).
     *
     * @param context  The calling Activity context
     * @param url      The URL to open (e.g. "http://192.168.1.1:8080")
     * @param toolbarColor  ARGB color int for the Custom Tab toolbar (use your brand color)
     */
    public static void openUrl(Context context, String url, int toolbarColor) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(context, "Could not determine Gateway IP", Toast.LENGTH_LONG).show();
            return;
        }

        Uri uri = Uri.parse(url);

        try {
            if (isCustomTabsSupported(context)) {
                launchCustomTab(context, uri, toolbarColor);
            } else {
                launchFallbackBrowser(context, uri);
            }
        } catch (Exception e) {
            // Fallback: Custom Tabs bind failed or browser crashed
            try {
                launchFallbackBrowser(context, uri);
            } catch (Exception ex) {
                Toast.makeText(context, "No browser found on this device.", Toast.LENGTH_LONG).show();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds and fires a CustomTabsIntent with branding and animation options.
     */
    private static void launchCustomTab(Context context, Uri uri, int toolbarColor) {
        // Configure color scheme for the toolbar (supports dark/light variants)
        CustomTabColorSchemeParams colorSchemeParams = new CustomTabColorSchemeParams.Builder()
                .setToolbarColor(toolbarColor)
                .build();

        CustomTabsIntent customTabsIntent = new CustomTabsIntent.Builder()
                // Apply your brand color to the top toolbar
                .setDefaultColorSchemeParams(colorSchemeParams)
                // Show the page title in the toolbar instead of the URL
                .setShowTitle(true)
                // Hide the share button to keep the UI clean
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                // Smooth slide-in / slide-out animations
                .setStartAnimations(context,
                        android.R.anim.slide_in_left,
                        android.R.anim.slide_out_right)
                .setExitAnimations(context,
                        android.R.anim.slide_in_left,
                        android.R.anim.slide_out_right)
                .build();

        customTabsIntent.launchUrl(context, uri);
    }

    /**
     * Fallback: opens the URL in the device's default external browser via ACTION_VIEW.
     * Used when no Custom-Tabs-capable browser is found (e.g. some Huawei / Xiaomi devices).
     */
    private static void launchFallbackBrowser(Context context, Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        // Safety check: ensure *some* app can handle this intent before firing
        if (intent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(intent);
        } else {
            Toast.makeText(context,
                    "No browser found on this device. Please install a browser.",
                    Toast.LENGTH_LONG).show();
        }
    }
}

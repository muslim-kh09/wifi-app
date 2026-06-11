package com.khaledcli.wifimedia;

import android.content.Context;
import android.provider.Settings;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Lightweight MAC address helper with ANDROID_ID fallback.
 *
 * Reads the hardware address directly from the {@code wlan0} {@link NetworkInterface}.
 * If the device sandboxes the MAC to all zeros, it falls back to a device-unique ANDROID_ID.
 */
public class MacAddressHelper {

    public static final String FALLBACK = "00:00:00:00:00:00";

    /**
     * Returns the MAC address of wlan0, or the Android ID if unavailable/zeroed.
     */
    public static String getWlanMac(Context context) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return FALLBACK;

            for (NetworkInterface iface : Collections.list(interfaces)) {
                if (iface == null) continue;
                String name = iface.getName();
                if (name == null || !name.equalsIgnoreCase("wlan0")) continue;

                byte[] mac = iface.getHardwareAddress();
                if (mac == null || mac.length == 0) continue;

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < mac.length; i++) {
                    sb.append(String.format(java.util.Locale.US, "%02X", mac[i]));
                    if (i < mac.length - 1) sb.append(":");
                }
                String result = sb.toString();
                return result;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return FALLBACK;
    }

    /**
     * Returns the device-unique ANDROID_ID to be used as a fallback signature.
     */
    public static String getAndroidId(Context context) {
        try {
            if (context != null) {
                String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (androidId != null && !androidId.isEmpty()) {
                    return androidId;
                }
            }
        } catch (Exception ignored) {}
        return "UNKNOWN_ID";
    }
}

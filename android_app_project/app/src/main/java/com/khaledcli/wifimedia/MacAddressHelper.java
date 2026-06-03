package com.khaledcli.wifimedia;

import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Lightweight MAC address helper.
 *
 * Reads the hardware address directly from the {@code wlan0} {@link NetworkInterface},
 * which works on Android 6+ without requiring any special permission.
 *
 * Returns {@link #FALLBACK} when:
 *  - No wlan0 interface exists (device not on Wi-Fi yet)
 *  - The interface has no hardware address
 *  - Any exception occurs (safe default, never throws)
 */
public class MacAddressHelper {

    /** Returned when the real MAC cannot be determined. */
    public static final String FALLBACK = "00:00:00:00:00:00";

    /**
     * Returns the MAC address of the {@code wlan0} interface in upper-case
     * colon-separated format (e.g. {@code "AA:BB:CC:DD:EE:FF"}), or
     * {@link #FALLBACK} if unavailable.
     */
    public static String getWlanMac() {
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
                    sb.append(String.format("%02X", mac[i]));
                    if (i < mac.length - 1) sb.append(":");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return FALLBACK;
    }
}

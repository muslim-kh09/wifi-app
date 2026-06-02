package com.khaledcli.wifimedia;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Resolve the captive portal gateway IP dynamically
        String gatewayIp = getGatewayIp();

        if (gatewayIp != null && !gatewayIp.isEmpty()) {
            String url = "http://" + gatewayIp + ":8080";

            // Read the brand accent color from colors.xml (#3b82f6)
            int toolbarColor = getResources().getColor(R.color.icon_accent, null);

            // Launch via Custom Tabs (real browser session → real fingerprint)
            // Falls back automatically to ACTION_VIEW if no Custom-Tabs browser found
            CustomTabsHelper.openUrl(this, url, toolbarColor);
        } else {
            Toast.makeText(this, "Could not determine Gateway IP", Toast.LENGTH_LONG).show();
        }

        // Nothing left to display in this Activity — finish so it doesn't sit in the stack
        finish();
    }

    // -------------------------------------------------------------------------
    // Gateway IP resolution — kept exactly as in the original
    // -------------------------------------------------------------------------

    private String getGatewayIp() {
        // Method 1: ConnectivityManager LinkProperties (API 23+)
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork != null) {
                    LinkProperties linkProperties = cm.getLinkProperties(activeNetwork);
                    if (linkProperties != null) {
                        for (RouteInfo route : linkProperties.getRoutes()) {
                            if (route.isDefaultRoute()
                                    && route.getGateway() instanceof Inet4Address) {
                                return route.getGateway().getHostAddress();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Method 2: Fallback via Java Network Sockets & InterfaceAddress
        try {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
                InetAddress localAddress = socket.getLocalAddress();

                NetworkInterface networkInterface =
                        NetworkInterface.getByInetAddress(localAddress);
                if (networkInterface != null) {
                    for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                        if (address.getAddress() instanceof Inet4Address) {
                            String localIp = address.getAddress().getHostAddress();
                            if (localIp != null && localIp.contains(".")) {
                                return localIp.substring(0, localIp.lastIndexOf('.')) + ".1";
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Ultimate default fallback
        return "192.168.1.1";
    }
}

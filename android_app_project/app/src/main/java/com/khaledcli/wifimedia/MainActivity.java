package com.khaledcli.wifimedia;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;

public class MainActivity extends Activity {

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        webView = new WebView(this);
        setContentView(webView);

        // Configure WebView
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.setWebViewClient(new WebViewClient());

        // Resolve Gateway IP dynamically and build URL
        String gatewayIp = getGatewayIp();
        if (gatewayIp != null && !gatewayIp.isEmpty()) {
            String url = "http://" + gatewayIp + ":8080";
            webView.loadUrl(url);
        } else {
            Toast.makeText(this, "Could not determine Gateway IP", Toast.LENGTH_LONG).show();
        }
    }

    private String getGatewayIp() {
        // Method 1: Try ConnectivityManager LinkProperties for modern Android versions (API 23+)
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork != null) {
                    LinkProperties linkProperties = cm.getLinkProperties(activeNetwork);
                    if (linkProperties != null) {
                        for (RouteInfo route : linkProperties.getRoutes()) {
                            if (route.isDefaultRoute() && route.getGateway() instanceof Inet4Address) {
                                return route.getGateway().getHostAddress();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Method 2: Robust Fallback using Java Network Sockets & InterfaceAddress
        try {
            // Open a dummy socket to force network interface binding
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
                InetAddress localAddress = socket.getLocalAddress();
                
                NetworkInterface networkInterface = NetworkInterface.getByInetAddress(localAddress);
                if (networkInterface != null) {
                    for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                        if (address.getAddress() instanceof Inet4Address) {
                            String localIp = address.getAddress().getHostAddress();
                            // In most consumer networks, the gateway is at .1 or .254
                            // Fallback derivation based on subnet prefix
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

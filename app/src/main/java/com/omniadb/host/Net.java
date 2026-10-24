package com.omniadb.host;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/** 網路狀態查詢工具。 */
public final class Net {

    private static final String TAG = "OmniADB";

    private Net() {
    }

    /**
     * 取得手機在**區域網路**上的 IPv4 位址。
     *
     * ★ 必須排除 VPN / 行動網路。實測（Samsung S20 + VPN）在有 VPN 的機器上，
     *   {@code getActiveNetwork()} 會回 VPN 介面（例如 10.8.0.2），於是 App 顯示
     *   錯的 IP，使用者 {@code adb connect} 到那個位址永遠連不上。
     *   ADB 常駐要的是「同一個 WiFi 下電腦連得到的位址」，
     *   所以**優先 WiFi / 乙太網路**，其次才挑不像 VPN 的介面。
     */
    public static String deviceIp(Context context) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                    if (caps == null) continue;
                    boolean lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                    if (!lan) continue;
                    String ip = firstIpv4(cm.getLinkProperties(n));
                    if (ip != null) return ip;
                }
            }
            List<NetworkInterface> nics =
                    Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nic : nics) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                String name = nic.getName();
                if (name.startsWith("tun") || name.startsWith("ppp")
                        || name.startsWith("rmnet") || name.startsWith("clat")) {
                    continue;   // VPN / 行動網路 / 464xlat
                }
                String ip = firstIpv4(nic);
                if (ip != null) return ip;
            }
        } catch (Exception e) {
            Log.e(TAG, "解析 IP 失敗", e);
        }
        return "127.0.0.1";
    }

    private static String firstIpv4(LinkProperties lp) {
        if (lp == null) return null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                return a.getHostAddress();
            }
        }
        return null;
    }

    private static String firstIpv4(NetworkInterface nic) {
        for (InetAddress a : Collections.list(nic.getInetAddresses())) {
            if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                return a.getHostAddress();
            }
        }
        return null;
    }

    /** 是否已有可用的區域網路（WiFi / 乙太網路 / VPN）。 */
    public static boolean lanConnected(Context context) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null) return false;
            boolean lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            return lan && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return false;
        }
    }
}

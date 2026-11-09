package com.limelight.bitrate;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.net.Inet4Address;
import java.security.MessageDigest;

/**
 * What automatic bitrate learnt, per host and network (PEN-10): the bitrate the link held and the
 * step-up wait. Kept 24 hours, on this device only. Networks are named by a hash: of the Wi-Fi
 * SSID when Android shows it (it needs location access, which Penumbra doesn't ask for), else of
 * the subnet and gateway, so "wifi:" keys are stable per network either way. Never the names.
 */
public final class AutoBitrateMemory {
    private static final String PREFS = "penumbra_auto_bitrate";
    private static final long MAX_AGE_MS = 24 * 3600_000L;

    public static final class Entry {
        public final int limitKbps;
        public final long upHoldMs;
        public final long at;

        Entry(int limitKbps, long upHoldMs, long at) {
            this.limitKbps = limitKbps;
            this.upHoldMs = upHoldMs;
            this.at = at;
        }
    }

    private AutoBitrateMemory() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String key(String hostUuid, String network) {
        return (hostUuid != null ? hostUuid : "?") + "|" + network;
    }

    public static Entry recall(Context context, String key) {
        String value = prefs(context).getString(key, null);
        if (value == null) return null;
        String[] parts = value.split(",");
        if (parts.length != 3) return null;
        try {
            Entry e = new Entry(Integer.parseInt(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]));
            if (System.currentTimeMillis() - e.at > MAX_AGE_MS) return null;
            return e;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static void remember(Context context, String key, int limitKbps, long upHoldMs) {
        prefs(context).edit().putString(key, limitKbps + "," + upHoldMs + "," + System.currentTimeMillis()).apply();
    }

    /** "wifi:&lt;hash&gt;", "ethernet", "mobile", "vpn:&lt;hash&gt;", or null off any network. */
    @SuppressWarnings("deprecation")
    public static String networkId(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
            Network n = cm.getActiveNetwork();
            if (n == null) return null;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            boolean vpn = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            if (caps != null && !vpn) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "mobile";
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
            }
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !vpn) {
                WifiInfo info = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && caps.getTransportInfo() instanceof WifiInfo) {
                    info = (WifiInfo) caps.getTransportInfo();
                }
                if (info == null) {
                    WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) info = wm.getConnectionInfo();
                }
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.equals(WifiManager.UNKNOWN_SSID) && !ssid.equals("0x")) {
                        return "wifi:" + hash("ssid|" + ssid);
                    }
                }
            }
            // No SSID: name the network by its subnet and gateway
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return null;
            String subnet = null;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address) {
                    byte[] b = la.getAddress().getAddress();
                    int v = ((b[0] & 255) << 24) | ((b[1] & 255) << 16) | ((b[2] & 255) << 8) | (b[3] & 255);
                    int bits = la.getPrefixLength();
                    int net = bits == 0 ? 0 : v & (-1 << (32 - bits));
                    subnet = Integer.toHexString(net) + "/" + bits;
                    break;
                }
            }
            if (subnet == null) return null;
            String gateway = "";
            for (RouteInfo r : lp.getRoutes()) {
                if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) {
                    gateway = r.getGateway().getHostAddress();
                    break;
                }
            }
            return (vpn ? "vpn:" : "wifi:") + hash("net|" + subnet + "|" + gateway);
        } catch (Exception e) {
            return null;
        }
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i] & 255));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}

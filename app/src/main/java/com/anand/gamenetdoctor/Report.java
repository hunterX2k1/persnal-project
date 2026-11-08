package com.anand.gamenetdoctor;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Builds a plain-text diagnostic report the user can copy/share for analysis. */
public final class Report {

    static final String CRASH_FILE = "last_crash.txt";
    static final String SCAN_FILE = "last_scan.txt";
    static final String TEST_FILE = "last_test.txt";
    static final String ROUTER_FILE = "last_router.txt";

    static void save(Context c, String name, String text) {
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), name))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    static String load(Context c, String name) {
        File f = new File(c.getFilesDir(), name);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static void clearCrash(Context c) {
        //noinspection ResultOfMethodCallIgnored
        new File(c.getFilesDir(), CRASH_FILE).delete();
    }

    static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    static String build(Context c) {
        StringBuilder s = new StringBuilder();
        s.append("===== GAME NET DOCTOR REPORT =====\n");
        s.append("Created: ").append(now()).append('\n');
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            s.append("App version: ").append(pi.versionName).append('\n');
        } catch (Exception ignored) { }
        s.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");

        s.append("\n----- NETWORK -----\n").append(network(c));

        section(s, "LAST SPEED TEST", load(c, TEST_FILE), "(not run yet: tap RUN TEST on the main screen)");
        section(s, "LAST ROUTER CHECK", load(c, ROUTER_FILE), "(not opened yet)");
        section(s, "LAST DEVICE SCAN", load(c, SCAN_FILE), "(not run yet)");
        section(s, "LAST CRASH", load(c, CRASH_FILE), "none");
        s.append("\n===== END OF REPORT =====\n");
        return s.toString();
    }

    private static void section(StringBuilder s, String title, String body, String empty) {
        s.append("\n----- ").append(title).append(" -----\n").append(body == null ? empty : body.trim()).append('\n');
    }

    private static String network(Context c) {
        StringBuilder s = new StringBuilder();
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            if (caps == null) {
                s.append("No active network\n");
            } else {
                s.append("Transport: ")
                        .append(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi " : "")
                        .append(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Mobile " : "")
                        .append(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet " : "")
                        .append(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN(ACTIVE) " : "")
                        .append('\n');
                s.append("Internet validated: ").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).append('\n');
                s.append("Down/Up estimate: ").append(caps.getLinkDownstreamBandwidthKbps()).append('/')
                        .append(caps.getLinkUpstreamBandwidthKbps()).append(" kbps\n");
            }
            // Detect a VPN on any network, not just the active one (VPN apps can intercept LAN traffic).
            boolean anyVpn = false;
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(net);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) anyVpn = true;
            }
            s.append("Any VPN / ad-blocker tunnel running: ").append(anyVpn).append('\n');

            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp != null) {
                s.append("Interface: ").append(lp.getInterfaceName()).append('\n');
                for (LinkAddress la : lp.getLinkAddresses()) s.append("Address: ").append(la).append('\n');
                s.append("Gateway: ").append(BaseActivity.gateway(c)).append('\n');
                StringBuilder dns = new StringBuilder();
                for (InetAddress d : lp.getDnsServers()) dns.append(d.getHostAddress()).append(' ');
                s.append("DNS: ").append(dns.toString().trim()).append('\n');
                if (Build.VERSION.SDK_INT >= 28) {
                    s.append("Private DNS: ").append(lp.isPrivateDnsActive() ? "active" : "off")
                            .append(lp.getPrivateDnsServerName() != null ? " (" + lp.getPrivateDnsServerName() + ")" : "").append('\n');
                }
                if (lp.getHttpProxy() != null) s.append("HTTP proxy: ").append(lp.getHttpProxy()).append('\n');
            }
            if (Build.VERSION.SDK_INT >= 24) {
                s.append("Data saver: ").append(cm.getRestrictBackgroundStatus()
                        == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED ? "ON" : "off").append('\n');
            }
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            //noinspection deprecation
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            if (wi != null && wi.getFrequency() > 0) {
                s.append("Wi-Fi: ").append(wi.getFrequency()).append(" MHz · RSSI ").append(wi.getRssi())
                        .append(" dBm · link ").append(wi.getLinkSpeed()).append(" Mbps");
                if (Build.VERSION.SDK_INT >= 30) s.append(" · standard ").append(wifiStandard(wi.getWifiStandard()));
                s.append('\n');
            }
            PowerManager pm = c.getSystemService(PowerManager.class);
            s.append("Battery saver: ").append(pm != null && pm.isPowerSaveMode() ? "ON" : "off").append('\n');
            s.append("Game Mode running: ").append(GameModeService.running).append('\n');
        } catch (Exception e) {
            s.append("Error reading network: ").append(e).append('\n');
        }
        return s.toString();
    }

    private static String wifiStandard(int std) {
        switch (std) {
            case 1: return "legacy (a/b/g)";
            case 4: return "Wi-Fi 4 (n)";
            case 5: return "Wi-Fi 5 (ac)";
            case 6: return "Wi-Fi 6 (ax)";
            case 7: return "Wi-Fi 6E/7";
            default: return "unknown(" + std + ")";
        }
    }

    private Report() { }
}

package com.anand.gamenetdoctor;

import android.content.Intent;
import android.net.LinkAddress;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helps the user give their phone priority on the router. Routers have no standard API for QoS,
 * so we detect the brand, check UPnP, open the admin page in-app and show brand-specific steps.
 */
public class RouterActivity extends BaseActivity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView infoText, stepsText;
    private String gw;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = screen("Router control",
                "Make the router serve your phone first and limit other devices.");

        gw = gateway(this);
        LinkAddress self = ownIpv4(this);
        String myIp = self == null ? "unknown" : self.getAddress().getHostAddress();

        LinearLayout c1 = card(root, "Your router");
        infoText = text("Router IP: " + (gw == null ? "not found (connect to Wi-Fi)" : gw)
                + "\nYour phone IP: " + myIp + "\nDetecting router brand and UPnP…", 14, TEXT, false);
        c1.addView(infoText);

        Button open = button("OPEN ROUTER SETTINGS PAGE", ACCENT);
        open.setOnClickListener(v -> {
            if (gw == null) return;
            startActivity(new Intent(this, WebActivity.class).putExtra("url", "http://" + gw + "/"));
        });
        c1.addView(open);
        c1.addView(text("The login is usually printed on a sticker under the router (often admin / admin, "
                + "or the password set by your internet technician).", 12, MUTED, false));

        LinearLayout c2 = card(root, "Before you start: make the router recognise your phone");
        c2.addView(text("Android uses a random MAC address on each Wi-Fi network. Set it to the real one so your priority "
                + "setting keeps working:\nWi-Fi settings → tap ⚙ next to your network → Privacy → \"Use device MAC\".\n"
                + "Then write down the MAC shown there. The router lists your phone by that MAC.", 13, MUTED, false));
        Button wifi = button("Open Wi-Fi settings", SECONDARY);
        wifi.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        c2.addView(wifi);

        LinearLayout c3 = card(root, "Steps for your router");
        stepsText = text("Detecting…", 14, TEXT, false);
        c3.addView(stepsText);

        Button devs = button("SEE DEVICES ON MY WI-FI", SECONDARY);
        devs.setOnClickListener(v -> startActivity(new Intent(this, DevicesActivity.class)));
        root.addView(devs);

        Button app = button("Find my router brand's official app (Play Store)", SECONDARY);
        app.setOnClickListener(v -> {
            String q = brandAppQuery;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(q))));
            } catch (Exception e) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(q) + "&c=apps")));
            }
        });
        root.addView(app);

        detect(myIp);
    }

    private String brandAppQuery = "router app";

    private void detect(String myIp) {
        new Thread(() -> {
            String page = gw == null ? "" : fetchRouterPage(gw);
            String upnp = ssdpGateway();
            String brand = brandOf(page + " " + (upnp == null ? "" : upnp));
            String upnpLine = upnp == null
                    ? "UPnP: OFF or not found ⚠ (turn it on in the router, it helps online games connect)"
                    : "UPnP: ON ✅";
            String steps = steps(brand, myIp, upnp != null);
            String title = "";
            java.util.regex.Matcher tm = Pattern.compile("<title>\\s*([^<]{1,80})</title>", Pattern.CASE_INSENSITIVE).matcher(page);
            if (tm.find()) title = tm.group(1).trim();
            Report.save(this, Report.ROUTER_FILE, "Checked: " + Report.now() + "\nGateway: " + gw
                    + "\nBrand guess: " + brand + "\nPage title: " + title
                    + "\nPage bytes read: " + page.length()
                    + "\nUPnP gateway: " + (upnp == null ? "none answered" : upnp));
            ui.post(() -> {
                infoText.setText("Router IP: " + (gw == null ? "not found" : gw)
                        + "\nYour phone IP: " + myIp
                        + "\nRouter brand: " + (brand == null ? "not detected" : brand)
                        + "\n" + upnpLine);
                stepsText.setText(steps);
            });
        }).start();
    }

    /** Reads the router's login page (title + headers) to guess the brand. */
    private static String fetchRouterPage(String gw) {
        StringBuilder sb = new StringBuilder();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + gw + "/").openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setInstanceFollowRedirects(true);
            sb.append(c.getHeaderField("Server")).append(' ').append(c.getHeaderField("WWW-Authenticate")).append(' ');
            InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                byte[] buf = new byte[16384];
                int n, total = 0;
                while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) total += n;
                sb.append(new String(buf, 0, total, StandardCharsets.ISO_8859_1));
                in.close();
            }
        } catch (Exception ignored) { }
        return sb.toString();
    }

    /** SSDP search for a UPnP Internet Gateway Device. Returns the SERVER header, or null if none answered. */
    private static String ssdpGateway() {
        String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
                + "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n";
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(2500);
            byte[] b = msg.getBytes(StandardCharsets.US_ASCII);
            s.send(new DatagramPacket(b, b.length, InetAddress.getByName("239.255.255.250"), 1900));
            byte[] buf = new byte[2048];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            String resp = new String(buf, 0, p.getLength(), StandardCharsets.US_ASCII);
            Matcher m = Pattern.compile("(?im)^server:\\s*(.+)$").matcher(resp);
            return m.find() ? m.group(1).trim() : "found";
        } catch (SocketTimeoutException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String brandOf(String raw) {
        String s = raw.toLowerCase(Locale.ROOT);
        if (s.contains("netlink") || s.contains("hg323")) return "Netlink ONT";
        if (s.contains("jio")) return "JioFiber";
        if (s.contains("airtel")) return "Airtel Xstream";
        if (s.contains("tp-link") || s.contains("tplink") || s.contains("archer") || s.contains("deco")) return "TP-Link";
        if (s.contains("netgear") || s.contains("nighthawk")) return "Netgear";
        if (s.contains("asus") || s.contains("rt-ax") || s.contains("rt-ac")) return "ASUS";
        if (s.contains("d-link") || s.contains("dlink")) return "D-Link";
        if (s.contains("tenda")) return "Tenda";
        if (s.contains("xiaomi") || s.contains("miwifi") || s.contains("redmi")) return "Xiaomi / Mi";
        if (s.contains("mercusys")) return "Mercusys";
        if (s.contains("huawei")) return "Huawei";
        if (s.contains("zte")) return "ZTE";
        if (s.contains("nokia") || s.contains("alcatel")) return "Nokia";
        if (s.contains("syrotech")) return "Syrotech";
        if (s.contains("digisol")) return "Digisol";
        if (s.contains("linksys")) return "Linksys";
        if (s.contains("bsnl")) return "BSNL";
        Matcher m = Pattern.compile("<title>\\s*([^<]{2,60})</title>", Pattern.CASE_INSENSITIVE).matcher(raw);
        return m.find() ? "\"" + m.group(1).trim() + "\" (unknown brand)" : null;
    }

    private String steps(String brand, String myIp, boolean upnpOn) {
        String b = brand == null ? "" : brand;
        String where;
        if (b.startsWith("TP-Link")) {
            brandAppQuery = "TP-Link Tether";
            where = "TP-Link: Advanced → QoS (newer models: \"QoS → Device Priority\"). Turn QoS on, set your upload/download "
                    + "speed, then turn ON priority for your phone and set the timing to \"Always\".\n"
                    + "Older TP-Link: Advanced → Bandwidth Control → add rules that cap other devices' IPs.\n"
                    + "You can also do this in the TP-Link Tether app.";
        } else if (b.startsWith("Mercusys")) {
            brandAppQuery = "Mercusys";
            where = "Mercusys: Advanced → QoS / Bandwidth Control. Give your phone High priority or limit other devices.";
        } else if (b.startsWith("Netgear")) {
            brandAppQuery = "Netgear Nighthawk";
            where = "Netgear: Advanced → Setup → QoS (or \"Dynamic QoS\"). Turn it on, then under the device list set your phone to "
                    + "Highest priority. Nighthawk app: Settings → Internet Speed/QoS.";
        } else if (b.startsWith("ASUS")) {
            brandAppQuery = "ASUS Router";
            where = "ASUS: Adaptive QoS → QoS → enable, then in Bandwidth Monitor drag your phone to the top (highest) or "
                    + "turn on \"Game Boost\" / Gear Accelerator for your phone. ASUS Router app works too.";
        } else if (b.startsWith("D-Link")) {
            brandAppQuery = "D-Link EAGLE PRO AI";
            where = "D-Link: Features → QoS Engine. Drag your phone into the \"Highest\" priority box and save.";
        } else if (b.startsWith("Tenda")) {
            brandAppQuery = "Tenda WiFi";
            where = "Tenda: Bandwidth Control (or \"Advanced → QoS\"). Set download/upload limits on the OTHER devices and leave your phone unlimited.";
        } else if (b.startsWith("Xiaomi")) {
            brandAppQuery = "Mi WiFi";
            where = "Xiaomi / Mi: in the Mi WiFi app → Tools → QoS (Smart speed limit) → choose \"Game priority\" mode or set your phone to high priority.";
        } else if (b.startsWith("Netlink")) {
            brandAppQuery = "TP-Link Archer";
            where = "Netlink GPON ONT (e.g. HG323RGW): the login is usually printed on the sticker. Many Netlink ONTs are 2.4 GHz-only and have "
                    + "no device-priority (QoS) option. In the WLAN / Wireless settings you can: set the channel to 1 or 11 instead of Auto, "
                    + "try 20 MHz channel width for stability, and change a weak Wi-Fi password. For 5 GHz and real device priority, connect a "
                    + "dual-band QoS router (e.g. TP-Link Archer) to a LAN port of the ONT, or ask your ISP for a dual-band ONT.";
        } else if (b.startsWith("JioFiber")) {
            brandAppQuery = "MyJio";
            where = "JioFiber: many Jio routers have no QoS/priority setting. You can still log in (Network → Wi-Fi / LAN) to: see connected devices, "
                    + "block devices you don't know, and use a separate 5 GHz network only for your phone. If you need real device priority, "
                    + "connect your own QoS router (e.g. TP-Link/ASUS) behind the Jio box.";
        } else if (b.startsWith("Airtel")) {
            brandAppQuery = "Airtel Thanks";
            where = "Airtel Xstream: look for QoS / Bandwidth Control (some models only). If it's missing, use the Airtel Thanks app or "
                    + "router page to remove unknown devices, and keep your phone on the 5 GHz network. A personal QoS router fixes it permanently.";
        } else {
            where = "Look in the router menu for any of these names: \"QoS\", \"Device Priority\", \"Bandwidth Control\", "
                    + "\"Traffic Control\", \"Game Mode\" or \"Smart QoS\". Set your phone to High/Highest priority, "
                    + "or set speed limits on the other devices. If your router has none of these, it can't prioritise devices; "
                    + "you'd need a router with QoS.";
        }

        return "1. Tap OPEN ROUTER SETTINGS PAGE above and log in.\n\n"
                + "2. Reserve a fixed IP for your phone so it never changes: look for \"Address Reservation\" / \"DHCP Reservation\" / "
                + "\"Static lease\" and reserve " + myIp + " for your phone's MAC.\n\n"
                + "3. Give your phone priority:\n" + where + "\n\n"
                + "4. Bandwidth tip: set the QoS total speed to about 85–90% of your real plan speed (from a speed test). "
                + "If you set it higher, priority has no effect.\n\n"
                + "5. " + (upnpOn ? "UPnP is already ON. Good." : "Turn ON UPnP (usually under Advanced → NAT Forwarding → UPnP). It helps online games connect.") + "\n\n"
                + "6. Remove devices you don't recognise (Access Control / Block list) and change the Wi-Fi password if strangers are connected.\n\n"
                + "7. Save, reboot the router, then run the network test again on the main screen.";
    }
}

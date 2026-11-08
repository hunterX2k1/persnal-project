package com.anand.gamenetdoctor;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseActivity {

    static final String SFA_PACKAGE = "com.nekki.shadowfightarena";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView infoText, resultText, adviceText, statusText;
    private Button testBtn, gameModeBtn;
    private boolean testing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        infoText.setText(connectionInfo());
        refreshGameModeButton();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(32));
        scroll.addView(root);

        TextView title = text("Game Net Doctor", 26, TEXT, true);
        root.addView(title);
        root.addView(text("Finds why online games lag on your Wi-Fi when YouTube works fine, then helps fix it.", 14, MUTED, false));

        LinearLayout c1 = card(root, "Your connection");
        infoText = text("", 14, TEXT, false);
        c1.addView(infoText);

        LinearLayout c2 = card(root, "1 · Run the network test (≈30 s)");
        testBtn = button("RUN TEST", ACCENT);
        testBtn.setOnClickListener(v -> runTest());
        c2.addView(testBtn);
        statusText = text("Stand where you normally play, close other apps, then tap RUN TEST.", 13, MUTED, false);
        c2.addView(statusText);
        resultText = text("", 13, TEXT, false);
        resultText.setTypeface(Typeface.MONOSPACE);
        c2.addView(resultText);

        LinearLayout c3 = card(root, "2 · Diagnosis & fixes");
        adviceText = text("Run the test first.", 14, TEXT, false);
        c3.addView(adviceText);

        LinearLayout c4 = card(root, "3 · Game Mode");
        c4.addView(text("Keeps the phone's Wi-Fi radio out of power-save while you play. This removes the random lag spikes that games feel but video doesn't (video buffers ahead). Strongest effect on Android 13 and older.", 13, MUTED, false));
        gameModeBtn = button("", GOOD);
        gameModeBtn.setOnClickListener(v -> toggleGameMode());
        c4.addView(gameModeBtn);
        Button launch = button("GAME MODE ON + OPEN SHADOW FIGHT ARENA", ACCENT);
        launch.setOnClickListener(v -> launchGame());
        c4.addView(launch);

        LinearLayout c5 = card(root, "4 · Quick fix shortcuts");
        addShortcut(c5, "Wi-Fi settings (switch to 5 GHz network)", new Intent(Settings.ACTION_WIFI_SETTINGS));
        addShortcut(c5, "SFA app settings → Battery: Unrestricted, Mobile data: allow background",
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + SFA_PACKAGE)));
        addShortcut(c5, "Battery saver settings (turn OFF while playing)", new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
        addShortcut(c5, "Private DNS / network settings", new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        if (Build.VERSION.SDK_INT >= 24) {
            addShortcut(c5, "Data saver settings (turn OFF)", new Intent("android.settings.DATA_SAVER_SETTINGS"));
        }
        addShortcut(c5, "VPN settings (disconnect any VPN)", new Intent("android.net.vpn.SETTINGS"));

        LinearLayout c6 = card(root, "5 · My Wi-Fi & router priority");
        c6.addView(text("See who else is using your Wi-Fi, and set your router to serve your phone first (QoS / device priority / bandwidth limits).", 13, MUTED, false));
        Button devices = button("DEVICES ON MY WI-FI", ACCENT);
        devices.setOnClickListener(v -> startActivity(new Intent(this, DevicesActivity.class)));
        c6.addView(devices);
        Button router = button("PRIORITISE MY PHONE ON THE ROUTER", GOOD);
        router.setOnClickListener(v -> startActivity(new Intent(this, RouterActivity.class)));
        c6.addView(router);

        LinearLayout c7 = card(root, "6 · Problems? Send a report");
        c7.addView(text("Creates a text report (network details, last test, last device scan, any crash) that you can copy or share and paste to Claude for analysis.", 13, MUTED, false));
        Button export = button("EXPORT REPORT", ACCENT);
        export.setOnClickListener(v -> startActivity(new Intent(this, ReportActivity.class)));
        c7.addView(export);
        if (Report.load(this, Report.CRASH_FILE) != null) {
            c7.addView(text("⚠ The app crashed last time. The crash details are included in the report.", 13, WARN, false));
        }

        setContentView(scroll);
        refreshGameModeButton();
    }

    private void addShortcut(LinearLayout card, String label, Intent intent) {
        Button b = button(label, Color.parseColor("#2B303B"));
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.getLayoutParams().height = -2;
        b.setMinHeight(dp(48));
        b.setOnClickListener(v -> {
            try {
                startActivity(intent);
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { }
            }
        });
        card.addView(b);
    }

    // ---------------------------------------------------------------- connection info

    private static final class Info {
        boolean wifi, cellular, vpn, validated;
        int freqMhz = -1, rssi = -127, linkMbps = -1;
        String gateway;
        boolean powerSave, dataSaver;
        String privateDns = "unknown";
    }

    private Info readInfo() {
        Info i = new Info();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        if (n != null) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null) {
                i.wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                i.cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                i.vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                i.validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp != null) {
                for (RouteInfo r : lp.getRoutes()) {
                    InetAddress gw = r.getGateway();
                    if (r.isDefaultRoute() && gw instanceof Inet4Address && !gw.isAnyLocalAddress()) {
                        i.gateway = gw.getHostAddress();
                        break;
                    }
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    String host = lp.getPrivateDnsServerName();
                    i.privateDns = host != null ? "custom (" + host + ")" : (lp.isPrivateDnsActive() ? "automatic (on)" : "off");
                }
            }
            if (Build.VERSION.SDK_INT >= 24) {
                i.dataSaver = cm.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
            }
        }
        if (i.wifi) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            //noinspection deprecation
            WifiInfo wi = wm.getConnectionInfo();
            if (wi != null) {
                i.freqMhz = wi.getFrequency();
                i.rssi = wi.getRssi();
                i.linkMbps = wi.getLinkSpeed();
            }
        }
        PowerManager pm = getSystemService(PowerManager.class);
        i.powerSave = pm != null && pm.isPowerSaveMode();
        return i;
    }

    private static String band(int f) {
        if (f <= 0) return "unknown";
        if (f < 3000) return "2.4 GHz";
        if (f < 5925) return "5 GHz";
        return "6 GHz";
    }

    private static String signalWord(int rssi) {
        if (rssi >= -55) return "excellent";
        if (rssi >= -65) return "good";
        if (rssi >= -72) return "fair";
        return "WEAK";
    }

    private String connectionInfo() {
        Info i = readInfo();
        StringBuilder s = new StringBuilder();
        s.append("Network: ").append(i.wifi ? "Wi-Fi" : i.cellular ? "Mobile data" : "none / other")
                .append(i.vpn ? " (through VPN)" : "").append('\n');
        if (i.wifi) {
            s.append("Band: ").append(band(i.freqMhz)).append(i.freqMhz > 0 ? " (" + i.freqMhz + " MHz)" : "").append('\n');
            s.append("Signal: ").append(i.rssi).append(" dBm – ").append(signalWord(i.rssi)).append('\n');
            s.append("Link speed: ").append(i.linkMbps).append(" Mbps\n");
            s.append("Router: ").append(i.gateway == null ? "unknown" : i.gateway).append('\n');
        }
        s.append("Private DNS: ").append(i.privateDns).append('\n');
        s.append("Battery saver: ").append(i.powerSave ? "ON ⚠" : "off").append('\n');
        s.append("Data saver: ").append(i.dataSaver ? "ON ⚠" : "off").append('\n');
        s.append("SFA installed: ").append(isSfaInstalled() ? "yes" : "not found");
        return s.toString();
    }

    private boolean isSfaInstalled() {
        try {
            getPackageManager().getPackageInfo(SFA_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- test

    private void status(String s) { ui.post(() -> statusText.setText(s)); }

    private void runTest() {
        if (testing) return;
        testing = true;
        testBtn.setEnabled(false);
        testBtn.setText("TESTING…");
        resultText.setText("");
        adviceText.setText("Testing…");
        final Info info = readInfo();
        infoText.setText(connectionInfo());

        new Thread(() -> {
            List<NetTester.Result> lines = new ArrayList<>();
            NetTester.Result router = null;
            if (info.gateway != null) {
                status("Testing phone ↔ router link (Wi-Fi quality)…");
                router = NetTester.pingHost("Router", info.gateway, 30);
                lines.add(router);
                publish(lines);
            }
            status("Testing internet with TCP (what YouTube uses)…");
            NetTester.Result tcp = NetTester.tcpConnect("Internet TCP", "1.1.1.1", 443, 20);
            lines.add(tcp);
            publish(lines);

            status("Testing internet with UDP (what online games use)…");
            NetTester.Result udp1 = NetTester.udpDns("Internet UDP-A", "8.8.8.8", 30);
            lines.add(udp1);
            publish(lines);
            NetTester.Result udp2 = NetTester.udpDns("Internet UDP-B", "1.1.1.1", 30);
            lines.add(udp2);
            publish(lines);

            NetTester.Result udp = udp1.lossPct() <= udp2.lossPct() ? udp1 : udp2;
            String advice = diagnose(info, router, tcp, udp);
            StringBuilder log = new StringBuilder("Test time: " + Report.now() + "\n");
            for (NetTester.Result r : lines) log.append(r.summary()).append('\n');
            log.append("Verdict: ").append(verdict(router, udp)).append("\nAdvice:\n").append(advice);
            Report.save(this, Report.TEST_FILE, log.toString());
            NetTester.Result fRouter = router;
            ui.post(() -> {
                adviceText.setText(advice);
                statusText.setText(verdict(fRouter, udp));
                testBtn.setEnabled(true);
                testBtn.setText("RUN TEST AGAIN");
                testing = false;
            });
        }).start();
    }

    private void publish(List<NetTester.Result> lines) {
        StringBuilder s = new StringBuilder();
        for (NetTester.Result r : lines) s.append(r.summary()).append("\n\n");
        String out = s.toString();
        ui.post(() -> resultText.setText(out));
    }

    private static boolean bad(NetTester.Result r, double maxAvg, double maxJitter, double maxLoss) {
        return r != null && (!r.ok() || r.avg() > maxAvg || r.jitter() > maxJitter || r.lossPct() > maxLoss);
    }

    private String verdict(NetTester.Result router, NetTester.Result udp) {
        boolean good = !bad(router, 15, 10, 1) && !bad(udp, 120, 25, 2);
        return good ? "✅ Result: connection looks good for online games."
                : "⚠ Result: problems found – read the diagnosis below.";
    }

    private String diagnose(Info i, NetTester.Result router, NetTester.Result tcp, NetTester.Result udp) {
        List<String> tips = new ArrayList<>();
        int n = 1;

        if (i.vpn) tips.add("A VPN is active. VPNs add delay and often break game UDP traffic. Turn it OFF before playing.");

        if (i.wifi && i.rssi < -70)
            tips.add("Your Wi-Fi signal is WEAK (" + i.rssi + " dBm). Move closer to the router, remove walls/obstacles, or add a Wi-Fi extender/mesh. Weak signal = re-sent packets = lag spikes.");

        if (i.wifi && i.freqMhz > 0 && i.freqMhz < 3000)
            tips.add("You are on 2.4 GHz Wi-Fi. It is crowded (neighbours, Bluetooth, microwaves) and causes jitter. If your router has a 5 GHz network (name often ends in -5G), connect to it while you play in the same room as the router.");

        boolean wifiBad = bad(router, 15, 10, 1);
        boolean tcpBad = bad(tcp, 150, 40, 5);
        boolean udpBad = bad(udp, 150, 30, 3);

        if (wifiBad) {
            tips.add("The link between your PHONE and ROUTER is unstable (" + router.summary().replace("Router: ", "") + "). "
                    + "This is the most likely cause. Fixes: restart the router; play closer to it; turn on Game Mode below; stop other devices from downloading/streaming during matches; on the router change the Wi-Fi channel (1, 6 or 11 for 2.4 GHz).");
        }

        if (udp != null && !udp.ok() && tcp.ok()) {
            tips.add("UDP traffic is BLOCKED on this Wi-Fi while TCP works. This exactly matches \"YouTube works but the game says unstable connection\". "
                    + "Fixes: restart the router; in router settings disable any strict firewall / 'SIP ALG' / 'DoS protection' and enable UPnP; if this is office/college/hotel Wi-Fi, the admin blocks game traffic – use mobile data instead; otherwise call your ISP and ask them to remove UDP / gaming restrictions.");
        } else if (udpBad && !tcpBad) {
            tips.add("UDP (game traffic) is worse than TCP (video traffic): " + udp.summary() + ". Your router or ISP is dropping/throttling UDP. "
                    + "Fixes: restart the router; enable UPnP and disable 'SIP ALG' in router settings; update router firmware; ask your ISP about packet loss to gaming servers. Compare by testing on mobile data.");
        } else if (udpBad || tcpBad) {
            if (!wifiBad) {
                tips.add("Your Wi-Fi to the router is fine, but the internet line itself has high delay or loss. This is the ISP/router side. "
                        + "Fixes: restart the router (unplug 30 s); make sure nobody is downloading/updating/streaming 4K at the same time; if you have QoS on the router, give your phone priority; if it persists, complain to your ISP with these numbers.");
            }
        }

        if (i.powerSave) tips.add("Battery saver is ON. It throttles network and CPU for games. Turn it OFF while playing.");
        if (i.dataSaver) tips.add("Data saver is ON. It can restrict the game's connection. Turn it OFF or allow Shadow Fight Arena unrestricted data.");
        if (i.privateDns.startsWith("custom"))
            tips.add("You use a custom Private DNS (" + i.privateDns + "). If the game fails to connect, set Private DNS to Automatic and test again.");

        tips.add("Open SFA app settings (shortcut below) → Battery → Unrestricted, and allow background data. Then clear the game's cache (not data).");
        if (!wifiBad && !udpBad && !tcpBad)
            tips.add("Your network numbers look healthy right now. If matches still show 'unstable', the issue is likely the game server for your region or a time-of-day ISP congestion – run this test again at the moment the game lags and compare.");

        StringBuilder s = new StringBuilder();
        for (String t : tips) s.append(n++).append(". ").append(t).append("\n\n");
        return s.toString().trim();
    }

    // ---------------------------------------------------------------- game mode

    private void refreshGameModeButton() {
        if (gameModeBtn == null) return;
        boolean on = GameModeService.running;
        gameModeBtn.setText(on ? "GAME MODE: ON (tap to turn off)" : "GAME MODE: OFF (tap to turn on)");
        ((GradientDrawable) gameModeBtn.getBackground()).setColor(on ? GOOD : Color.parseColor("#3A3F4B"));
    }

    private void startGameMode() {
        Intent svc = new Intent(this, GameModeService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
    }

    private void toggleGameMode() {
        if (GameModeService.running) {
            stopService(new Intent(this, GameModeService.class));
            Toast.makeText(this, "Game Mode off", Toast.LENGTH_SHORT).show();
        } else {
            startGameMode();
            Toast.makeText(this, "Game Mode on", Toast.LENGTH_SHORT).show();
        }
        ui.postDelayed(this::refreshGameModeButton, 300);
    }

    private void launchGame() {
        if (!GameModeService.running) startGameMode();
        Intent launch = getPackageManager().getLaunchIntentForPackage(SFA_PACKAGE);
        if (launch != null) {
            startActivity(launch);
        } else {
            Toast.makeText(this, "Shadow Fight Arena not found – Game Mode is on, open your game manually.", Toast.LENGTH_LONG).show();
            ui.postDelayed(this::refreshGameModeButton, 300);
        }
    }
}

package com.opensearch.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import net.freehaven.tor.control.TorControlConnection;

import org.json.JSONArray;
import org.json.JSONObject;
import org.torproject.jni.TorService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Runs Tor inside the app and controls which country the exit server (the IP sites see) is in.
 * Everything here talks to the internet only through Tor itself.
 */
final class TorManager {
    interface Listener { void onTorChanged(); }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService BG = Executors.newSingleThreadExecutor();

    private static TorService service;
    private static ServiceConnection conn;
    private static volatile boolean ready;
    private static volatile int socksPort = -1;
    private static volatile int progress;
    private static volatile String error;
    /** Exit country actually in use ("" = any). Resolves "random". */
    private static volatile String activeCountry = "";
    private static volatile String exitIp;
    /** Live number of Tor exit servers per country code (from the Tor network directory). */
    static volatile Map<String, Integer> serverCounts;
    private static Listener listener;

    private TorManager() {}

    static void setListener(Listener l) { listener = l; }
    static boolean isReady() { return ready; }
    static int socksPort() { return socksPort; }
    static int progress() { return progress; }
    static String error() { return error; }
    static String exitIp() { return exitIp; }
    static String activeCountry() { return activeCountry; }
    static boolean isRunning() { return conn != null; }

    private static void changed() {
        UI.post(() -> { if (listener != null) listener.onTorChanged(); });
    }

    /** Picks a concrete country for a preference value ("", "random" or a code). */
    static String resolve(String pref) {
        if (!"random".equals(pref)) return pref == null ? "" : pref;
        List<String> pool = new ArrayList<>();
        Map<String, Integer> counts = serverCounts;
        for (Countries.Country c : Countries.ALL) {
            if (counts == null || (counts.containsKey(c.code) && counts.get(c.code) > 0)) pool.add(c.code);
        }
        return pool.isEmpty() ? "" : pool.get(new Random().nextInt(pool.size()));
    }

    static void start(Context ctx, String countryPref) {
        if (conn != null) return;
        Context app = ctx.getApplicationContext();
        activeCountry = resolve(countryPref);
        error = null; progress = 0; ready = false; exitIp = null;
        try {
            File torrc = TorService.getTorrc(app);
            try (FileOutputStream out = new FileOutputStream(torrc)) {
                out.write(torrcFor(activeCountry).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            error = "Could not write Tor config: " + e.getMessage();
        }
        conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n, IBinder b) {
                service = ((TorService.LocalBinder) b).getService();
                BG.execute(TorManager::waitForBootstrap);
            }
            @Override public void onServiceDisconnected(ComponentName n) {
                service = null; ready = false; changed();
            }
        };
        app.bindService(new Intent(app, TorService.class).setAction(TorService.ACTION_START),
                conn, Context.BIND_AUTO_CREATE);
        changed();
    }

    static void stop(Context ctx) {
        if (conn == null) return;
        try { ctx.getApplicationContext().unbindService(conn); } catch (Exception ignored) {}
        conn = null; service = null; ready = false; socksPort = -1; exitIp = null;
        changed();
    }

    private static String torrcFor(String cc) {
        StringBuilder s = new StringBuilder("AvoidDiskWrites 1\n");
        if (!cc.isEmpty()) s.append("ExitNodes {").append(cc).append("}\nStrictNodes 1\n");
        return s.toString();
    }

    private static void waitForBootstrap() {
        try {
            for (int i = 0; i < 600 && service != null; i++) {      // up to ~5 minutes
                TorService s = service;
                if (s == null) return;
                String phase = s.getInfo("status/bootstrap-phase");
                if (phase != null) {
                    int at = phase.indexOf("PROGRESS=");
                    if (at >= 0) {
                        int end = phase.indexOf(' ', at);
                        try { progress = Integer.parseInt(phase.substring(at + 9, end < 0 ? phase.length() : end)); }
                        catch (NumberFormatException ignored) {}
                    }
                }
                int port = s.getSocksPort();
                if (progress >= 100 && port > 0) {
                    socksPort = port; ready = true; changed();
                    refreshExitIp();
                    if (serverCounts == null) refreshServerCounts();
                    return;
                }
                changed();
                Thread.sleep(500);
            }
            if (!ready) { error = "Tor could not connect. Check your internet, then try again."; changed(); }
        } catch (Exception e) {
            error = "Tor error: " + e.getMessage(); changed();
        }
    }

    /** Switches the exit country live (no restart) and gets a fresh IP. */
    static void setCountry(String pref) {
        final String cc = resolve(pref);
        activeCountry = cc;
        exitIp = null;
        changed();
        BG.execute(() -> {
            try {
                TorService s = service;
                if (s == null) return;
                TorControlConnection c = s.getTorControlConnection();
                if (c == null) return;
                Map<String, String> conf = new HashMap<>();
                conf.put("ExitNodes", cc.isEmpty() ? "" : "{" + cc + "}");
                conf.put("StrictNodes", cc.isEmpty() ? "0" : "1");
                c.setConf(conf);
                c.signal("NEWNYM");                 // drop old circuits -> new exit in the new country
                Thread.sleep(1500);
                refreshExitIp();
            } catch (Exception e) {
                error = "Could not switch country: " + e.getMessage(); changed();
            }
        });
    }

    /** New identity: same country, different exit server / IP. */
    static void newIp() {
        exitIp = null; changed();
        BG.execute(() -> {
            try {
                TorService s = service;
                if (s == null || s.getTorControlConnection() == null) return;
                s.getTorControlConnection().signal("NEWNYM");
                Thread.sleep(1500);
                refreshExitIp();
            } catch (Exception ignored) {}
        });
    }

    /** Asks the Tor Project which IP websites now see (request goes through Tor). */
    private static void refreshExitIp() {
        try {
            JSONObject o = new JSONObject(httpsGetViaTor("check.torproject.org", "/api/ip"));
            exitIp = o.optString("IP", null);
        } catch (Exception e) {
            exitIp = "?";
        }
        changed();
    }

    /** Downloads the live list of Tor exit servers and counts them per country. */
    static void refreshServerCounts() {
        BG.execute(() -> {
            try {
                JSONArray relays = new JSONObject(httpsGetViaTor("onionoo.torproject.org",
                        "/details?type=relay&running=true&flag=Exit&fields=country")).getJSONArray("relays");
                Map<String, Integer> m = new HashMap<>();
                for (int i = 0; i < relays.length(); i++) {
                    String cc = relays.getJSONObject(i).optString("country", "");
                    if (!cc.isEmpty()) m.put(cc, (m.containsKey(cc) ? m.get(cc) : 0) + 1);
                }
                serverCounts = m;
                changed();
            } catch (Exception ignored) {}
        });
    }

    /**
     * Minimal HTTPS GET through Tor's SOCKS5 port. The hostname is resolved by Tor
     * (SOCKS5 domain request), so not even the DNS lookup leaves your phone directly.
     */
    static String httpsGetViaTor(String host, String path) throws Exception {
        if (!ready) throw new IllegalStateException("Tor not ready");
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress("127.0.0.1", socksPort), 10000);
        raw.setSoTimeout(60000);
        OutputStream o = raw.getOutputStream();
        InputStream in = raw.getInputStream();
        o.write(new byte[]{5, 1, 0});
        byte[] r = readN(in, 2);
        if (r[0] != 5 || r[1] != 0) throw new IllegalStateException("SOCKS auth refused");
        byte[] h = host.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        req.write(new byte[]{5, 1, 0, 3, (byte) h.length});
        req.write(h);
        req.write(new byte[]{1, (byte) 0xBB});            // port 443
        o.write(req.toByteArray());
        byte[] rep = readN(in, 4);
        if (rep[1] != 0) throw new IllegalStateException("SOCKS connect failed: " + rep[1]);
        int skip = rep[3] == 1 ? 4 : rep[3] == 4 ? 16 : (readN(in, 1)[0] & 0xFF);
        readN(in, skip + 2);

        SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(raw, host, 443, true);
        ssl.startHandshake();
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
            ssl.close();
            throw new IllegalStateException("Certificate does not match " + host);
        }
        ssl.getOutputStream().write(("GET " + path + " HTTP/1.0\r\nHost: " + host
                + "\r\nUser-Agent: Mozilla/5.0\r\nAccept: application/json\r\nAccept-Encoding: identity"
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        InputStream sin = ssl.getInputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = sin.read(buf)) > 0; ) body.write(buf, 0, n);
        ssl.close();
        String resp = body.toString("UTF-8");
        int split = resp.indexOf("\r\n\r\n");
        return split < 0 ? resp : resp.substring(split + 4);
    }

    private static byte[] readN(InputStream in, int n) throws Exception {
        byte[] b = new byte[n];
        for (int off = 0; off < n; ) {
            int k = in.read(b, off, n - off);
            if (k < 0) throw new IllegalStateException("connection closed");
            off += k;
        }
        return b;
    }
}

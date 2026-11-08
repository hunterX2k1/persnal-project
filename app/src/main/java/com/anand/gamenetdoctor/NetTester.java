package com.anand.gamenetdoctor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Latency / jitter / packet-loss measurements. All methods block; call off the UI thread. */
public final class NetTester {

    public static final class Result {
        public final String name;
        public final int sent;
        public final List<Double> rtts = new ArrayList<>();
        public String note = "";

        Result(String name, int sent) { this.name = name; this.sent = sent; }

        public int received() { return rtts.size(); }
        public double lossPct() { return sent == 0 ? 100 : 100.0 * (sent - rtts.size()) / sent; }
        public double avg() {
            if (rtts.isEmpty()) return -1;
            double s = 0; for (double r : rtts) s += r; return s / rtts.size();
        }
        /** Jitter = mean absolute difference between consecutive samples (like RFC 3550). */
        public double jitter() {
            if (rtts.size() < 2) return 0;
            double s = 0;
            for (int i = 1; i < rtts.size(); i++) s += Math.abs(rtts.get(i) - rtts.get(i - 1));
            return s / (rtts.size() - 1);
        }
        public double max() {
            double m = -1; for (double r : rtts) m = Math.max(m, r); return m;
        }
        public boolean ok() { return !rtts.isEmpty(); }

        public String summary() {
            if (rtts.isEmpty()) return name + ": NO RESPONSE (100% loss)" + (note.isEmpty() ? "" : " – " + note);
            return String.format("%s: avg %.0f ms · jitter %.0f ms · max %.0f ms · loss %.0f%% (%d/%d)",
                    name, avg(), jitter(), max(), lossPct(), received(), sent);
        }
    }

    public interface Progress { void update(String msg); }

    private static final Pattern TIME = Pattern.compile("time[=<]([0-9.]+)");

    /** ICMP ping using the system ping binary. Falls back to TCP-to-port-80 timing if ping is unavailable. */
    public static Result pingHost(String name, String host, int count) {
        Result r = new Result(name, count);
        try {
            Process p = new ProcessBuilder("/system/bin/ping", "-c", String.valueOf(count),
                    "-i", "0.2", "-W", "1", host).redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                Matcher m = TIME.matcher(line);
                if (line.contains("bytes from") && m.find()) r.rtts.add(Double.parseDouble(m.group(1)));
            }
            p.waitFor();
            if (r.ok()) return r;
        } catch (Exception ignored) { }
        // Fallback: TCP connect timing (a "connection refused" reply still measures the round trip).
        Result t = tcpConnect(name, host, 80, count);
        t.note = "measured with TCP (ping not allowed on this phone)";
        return t;
    }

    /** Time a TCP handshake. This is what web/video traffic depends on. */
    public static Result tcpConnect(String name, String host, int port, int count) {
        Result r = new Result(name, count);
        for (int i = 0; i < count; i++) {
            long start = System.nanoTime();
            try (Socket s = new Socket()) {
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(host, port), 1500);
                r.rtts.add((System.nanoTime() - start) / 1e6);
            } catch (ConnectException refused) {
                double ms = (System.nanoTime() - start) / 1e6;
                if (ms < 1500) r.rtts.add(ms); // RST came back = host reachable
            } catch (Exception timeout) {
                // counted as loss
            }
            sleep(120);
        }
        return r;
    }

    /**
     * UDP round trip using small DNS queries. Real-time multiplayer games usually send their
     * match traffic over UDP, so this is the test that matters most for "unstable connection".
     */
    public static Result udpDns(String name, String server, int count) {
        Result r = new Result(name, count);
        Random rnd = new Random();
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(1000);
            InetAddress addr = InetAddress.getByName(server);
            byte[] buf = new byte[512];
            for (int i = 0; i < count; i++) {
                int id = rnd.nextInt(0xFFFF);
                byte[] q = dnsQuery(id, "example.com");
                long start = System.nanoTime();
                sock.send(new DatagramPacket(q, q.length, addr, 53));
                long deadline = start + 1_000_000_000L;
                while (true) {
                    try {
                        DatagramPacket resp = new DatagramPacket(buf, buf.length);
                        sock.receive(resp);
                        int rid = ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);
                        if (rid == id) { r.rtts.add((System.nanoTime() - start) / 1e6); break; }
                        if (System.nanoTime() > deadline) break; // stale reply, give up on this one
                    } catch (SocketTimeoutException e) { break; }
                }
                sleep(100);
            }
        } catch (Exception e) {
            r.note = "UDP blocked: " + e.getClass().getSimpleName();
        }
        return r;
    }

    private static byte[] dnsQuery(int id, String domain) {
        byte[] out = new byte[12 + domain.length() + 2 + 4];
        out[0] = (byte) (id >> 8); out[1] = (byte) id;
        out[2] = 0x01;          // recursion desired
        out[5] = 0x01;          // 1 question
        int p = 12;
        for (String label : domain.split("\\.")) {
            out[p++] = (byte) label.length();
            for (char c : label.toCharArray()) out[p++] = (byte) c;
        }
        out[p++] = 0;
        out[p++] = 0; out[p++] = 1; // type A
        out[p++] = 0; out[p] = 1;   // class IN
        return out;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private NetTester() { }
}

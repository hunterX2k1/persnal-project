package com.anand.gamenetdoctor;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds devices on the local network. Android 10+ hides the ARP table and MAC addresses from apps,
 * so devices are discovered by probing every address (ping + a few TCP ports), and named using
 * mDNS (phones, Macs, TVs), NetBIOS (Windows PCs) and the router's reverse DNS.
 */
public final class LanScanner {

    public static final class Device {
        public String ip;
        public String name;
        public String mac;
        public String type = "Unknown device";
        public boolean isRouter, isMe;
        public final List<Integer> openPorts = new ArrayList<>();

        int lastOctet() { return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1)); }
    }

    public interface Listener {
        void onProgress(int done, int total, int found);
    }

    private static final int[] PORTS = {80, 443, 53, 62078, 8008, 8009, 445, 139, 554, 7000, 5000, 9100, 22, 8080};

    public static List<Device> scan(String selfIp, int prefix, String gatewayIp, Listener listener) {
        int self = toInt(selfIp);
        int bits = Math.max(prefix, 24); // never scan more than 254 hosts
        int mask = bits == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - bits));
        int net = self & mask;
        int hosts = (1 << (32 - bits)) - 2;

        Map<String, String> arp = readArp();
        List<Device> found = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(48);

        for (int h = 1; h <= hosts; h++) {
            final String ip = fromInt(net + h);
            pool.execute(() -> {
                Device d = probe(ip, arp.containsKey(ip));
                if (d != null) {
                    d.isMe = ip.equals(selfIp);
                    d.isRouter = ip.equals(gatewayIp);
                    d.mac = arp.get(ip);
                    found.add(d);
                }
                listener.onProgress(done.incrementAndGet(), hosts, found.size());
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(3, TimeUnit.MINUTES); } catch (InterruptedException ignored) { }

        // Naming pass (in parallel, it's slow per device).
        ExecutorService namer = Executors.newFixedThreadPool(16);
        for (Device d : new ArrayList<>(found)) namer.execute(() -> nameAndClassify(d));
        namer.shutdown();
        try { namer.awaitTermination(1, TimeUnit.MINUTES); } catch (InterruptedException ignored) { }

        List<Device> out = new ArrayList<>(found);
        out.sort((a, b) -> {
            if (a.isRouter != b.isRouter) return a.isRouter ? -1 : 1;
            if (a.isMe != b.isMe) return a.isMe ? -1 : 1;
            return Integer.compare(a.lastOctet(), b.lastOctet());
        });
        return out;
    }

    private static Device probe(String ip, boolean inArp) {
        Device d = new Device();
        d.ip = ip;
        boolean alive = inArp;
        try {
            if (InetAddress.getByName(ip).isReachable(400)) alive = true;
        } catch (Exception ignored) { }
        for (int port : PORTS) {
            long start = System.nanoTime();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, port), 250);
                d.openPorts.add(port);
                alive = true;
            } catch (ConnectException refused) {
                if ((System.nanoTime() - start) / 1e6 < 250) alive = true; // host answered with RST
            } catch (Exception ignored) { }
            if (alive && d.openPorts.isEmpty() && port == PORTS[3]) break; // alive, enough probing for a quiet host
        }
        return alive ? d : null;
    }

    private static void nameAndClassify(Device d) {
        String n = mdnsReverse(d.ip);
        if (n == null) n = netbiosName(d.ip);
        if (n == null) {
            try {
                String h = InetAddress.getByName(d.ip).getCanonicalHostName();
                if (h != null && !h.equals(d.ip)) n = h;
            } catch (Exception ignored) { }
        }
        if (n != null) {
            n = n.replaceAll("\\.local\\.?$", "").replaceAll("\\.$", "");
        }
        d.name = n;
        d.type = guessType(d);
    }

    private static String guessType(Device d) {
        String n = d.name == null ? "" : d.name.toLowerCase();
        List<Integer> p = d.openPorts;
        if (d.isMe) return "This phone";
        if (d.isRouter) return "Router";
        if (n.contains("iphone")) return "iPhone";
        if (n.contains("ipad")) return "iPad";
        if (n.contains("macbook") || n.contains("imac") || n.contains("mac-")) return "Mac";
        if (n.contains("android") || n.contains("galaxy") || n.contains("redmi") || n.contains("oneplus")
                || n.contains("vivo") || n.contains("oppo") || n.contains("realme") || n.contains("pixel")
                || n.contains("poco") || n.contains("moto") || n.contains("samsung")) return "Android phone";
        if (n.contains("tv") || n.contains("bravia") || n.contains("firestick") || n.contains("chromecast")) return "TV / streaming";
        if (n.contains("desktop") || n.contains("laptop") || n.contains("-pc")) return "Computer";
        if (n.contains("printer") || n.contains("epson") || n.contains("hp")) return "Printer";
        if (n.contains("cam") || n.contains("ipc")) return "Camera";
        if (p.contains(62078)) return "iPhone / iPad";
        if (p.contains(8008) || p.contains(8009)) return "TV / Chromecast";
        if (p.contains(7000) || p.contains(5000)) return "Apple TV / Mac / speaker";
        if (p.contains(445) || p.contains(139)) return "Windows PC";
        if (p.contains(9100)) return "Printer";
        if (p.contains(554)) return "Camera / DVR";
        if (p.contains(80) || p.contains(443) || p.contains(8080)) return "Smart device / extender";
        return "Phone / other (quiet device)";
    }

    // ---------------------------------------------------------------- ARP (works on Android 9 and older)

    private static Map<String, String> readArp() {
        Map<String, String> m = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line = br.readLine(); // header
            while ((line = br.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 4 && "0x2".equals(f[2]) && !f[3].equals("00:00:00:00:00:00")) {
                    m.put(f[0], f[3].toUpperCase());
                }
            }
        } catch (Exception ignored) { }
        return m;
    }

    // ---------------------------------------------------------------- mDNS reverse lookup

    static String mdnsReverse(String ip) {
        String[] o = ip.split("\\.");
        String rev = o[3] + "." + o[2] + "." + o[1] + "." + o[0] + ".in-addr.arpa";
        int id = new Random().nextInt(0xFFFF);
        byte[] q = query(id, rev, 12, 0x8001); // PTR, IN, unicast-response bit
        byte[] buf = new byte[1500];
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(700);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName(ip), 5353));
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            int qd = u16(buf, 4), an = u16(buf, 6);
            if (an == 0) return null;
            int off = 12;
            for (int i = 0; i < qd; i++) off = skipName(buf, off) + 4;
            off = skipName(buf, off);
            int type = u16(buf, off);
            if (type != 12) return null;
            off += 10; // type, class, ttl, rdlength
            return readName(buf, off);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- NetBIOS (Windows)

    static String netbiosName(String ip) {
        byte[] q = new byte[50];
        q[0] = 0x13; q[1] = 0x37;
        q[5] = 1; // one question
        q[12] = 0x20;
        q[13] = 'C'; q[14] = 'K';
        for (int i = 15; i < 45; i++) q[i] = 'A';
        q[45] = 0;
        q[46] = 0; q[47] = 0x21; // NBSTAT
        q[48] = 0; q[49] = 1;
        byte[] buf = new byte[1024];
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(600);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName(ip), 137));
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            if (p.getLength() < 57) return null;
            int count = buf[56] & 0xFF;
            for (int i = 0; i < count; i++) {
                int off = 57 + i * 18;
                if (off + 18 > p.getLength()) break;
                boolean group = (buf[off + 16] & 0x80) != 0;
                if (buf[off + 15] == 0 && !group) return new String(buf, off, 15, "US-ASCII").trim();
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ---------------------------------------------------------------- DNS wire helpers

    static byte[] query(int id, String name, int type, int cls) {
        byte[] out = new byte[12 + name.length() + 2 + 4];
        out[0] = (byte) (id >> 8); out[1] = (byte) id;
        out[5] = 1;
        int p = 12;
        for (String label : name.split("\\.")) {
            out[p++] = (byte) label.length();
            for (char c : label.toCharArray()) out[p++] = (byte) c;
        }
        out[p++] = 0;
        out[p++] = (byte) (type >> 8); out[p++] = (byte) type;
        out[p++] = (byte) (cls >> 8); out[p] = (byte) cls;
        return out;
    }

    private static int u16(byte[] b, int off) { return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF); }

    private static int skipName(byte[] b, int off) {
        while (true) {
            int len = b[off] & 0xFF;
            if (len == 0) return off + 1;
            if ((len & 0xC0) == 0xC0) return off + 2;
            off += len + 1;
        }
    }

    private static String readName(byte[] b, int off) {
        StringBuilder sb = new StringBuilder();
        int guard = 0;
        while (guard++ < 64) {
            int len = b[off] & 0xFF;
            if (len == 0) break;
            if ((len & 0xC0) == 0xC0) { off = ((len & 0x3F) << 8) | (b[off + 1] & 0xFF); continue; }
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(b, off + 1, len, java.nio.charset.StandardCharsets.UTF_8));
            off += len + 1;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static int toInt(String ip) {
        String[] p = ip.split("\\.");
        return (Integer.parseInt(p[0]) << 24) | (Integer.parseInt(p[1]) << 16)
                | (Integer.parseInt(p[2]) << 8) | Integer.parseInt(p[3]);
    }

    static String fromInt(int v) {
        return ((v >>> 24) & 0xFF) + "." + ((v >>> 16) & 0xFF) + "." + ((v >>> 8) & 0xFF) + "." + (v & 0xFF);
    }

    private LanScanner() { }
}

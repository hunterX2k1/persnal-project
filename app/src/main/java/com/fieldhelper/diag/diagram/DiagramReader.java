package com.fieldhelper.diag.diagram;

import com.fieldhelper.diag.core.Card;
import com.fieldhelper.diag.core.CircuitTracer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a computer-made (vector) wiring diagram without any AI model:
 * the PDF already holds every wire as an exact line and every label as text with a position.
 *
 * 1) Sort drawing pieces into wires (long straight lines) and symbol parts (circles, boxes, short/slanted strokes).
 * 2) Group symbol parts into symbols; cut wires where they pass through a symbol.
 * 3) Join touching wires into nets (T-joints and junction dots connect; plain crossings don't).
 * 4) Give each symbol its device tag (K12), pin numbers (13, 14) and description from the nearest text.
 * 5) For each load (lamp, coil, motor, valve) find the path supply → ... → load → ... → 0V.
 * 6) Write circuit and component cards with the page as source.
 *
 * Pure Java (no Android), so it can be tested on any computer.
 */
public final class DiagramReader {

    // ---------- text classes ----------
    private static final Pattern POT = Pattern.compile(
            "^(\\+?\\d+([.,]\\d+)?\\s?V(DC|AC)?|[+-]\\d+V?|0V|L\\+|L-|L1|L2|L3|N|M|GND|PE|BAT[+-]?|\\+UB|-UB|UB[+-]?|KL\\.?\\d+|KL\\d+|31|30|15)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("^[-=+]?([A-Z0-9]+[-+.])*-?[A-Z]{1,3}\\d{1,4}[A-Z]?$");
    private static final Pattern PIN = Pattern.compile("^([A-Z]{0,2}\\d{1,3}[A-Z]?|NO|NC|COM|[+-]|L\\+|L-)$");

    /** Result of one page. */
    public static final class PageResult {
        public int page;
        public int wires, symbols;
        /** True when the page is only a picture (scan) with no lines or text to read. */
        public boolean scanned;
        public final List<Elem> elems = new ArrayList<>();
        public final List<Circuit> circuits = new ArrayList<>();
        public final List<String> issues = new ArrayList<>();
    }

    /** One placed part (a symbol occurrence), e.g. contact K12:13-14. */
    public static final class Elem {
        public String tag = "";       // K12, or "?" when no tag was found
        public String label = "";     // K12:13-14
        public String desc = "";
        public String kind = "";      // lamp, coil, contact, fuse, terminal, ...
        public final List<String> pinInfo = new ArrayList<>(); // "13 → X2:5, F3:2"
    }

    public static final class Circuit {
        public int page;
        public String loadTag, loadLabel, loadKind, desc;
        public List<String> nodes = new ArrayList<>();
        /** Descriptions of the other parts in the path, e.g. "Lamp test" for S1. */
        public List<String> partDescs = new ArrayList<>();
        public boolean complete;
    }

    // ---------- page reading ----------

    public static PageResult readPage(PageGeom g) {
        PageResult res = new PageResult();
        res.page = g.page;
        res.scanned = g.images > 0 && g.words.isEmpty() && g.subs.size() < 5;

        float h = medianHeight(g);
        float tol = Math.max(0.5f, h * 0.15f);
        float minWire = h * 1.6f;
        float margin = h * 0.9f;
        float dotMax = h * 0.9f;
        float symMax = h * 8f;

        List<float[]> wires = new ArrayList<>();
        List<float[]> parts = new ArrayList<>();   // boxes x0,y0,x1,y1
        List<float[]> bigs = new ArrayList<>();
        List<float[]> dots = new ArrayList<>();

        for (PageGeom.Sub s : g.subs) {
            if (s.pts == null || s.pts.length < 4) continue;
            float[] bb = bbox(s.pts);
            float bw = bb[2] - bb[0], bh = bb[3] - bb[1];
            if (s.filled && bw <= dotMax && bh <= dotMax) { dots.add(new float[]{(bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2}); continue; }
            if (s.closed || s.curve) {
                if (g.width > 0 && bw > g.width * 0.6f && bh > g.height * 0.6f) continue; // page frame
                if (Math.max(bw, bh) <= symMax) parts.add(bb);
                else if (s.closed && bw > minWire && bh > minWire) bigs.add(bb);
                continue;
            }
            for (int k = 0; k + 3 < s.pts.length; k += 2) {
                float x1 = s.pts[k], y1 = s.pts[k + 1], x2 = s.pts[k + 2], y2 = s.pts[k + 3];
                float len = (float) Math.hypot(x2 - x1, y2 - y1);
                if (len < tol) continue;
                boolean axis = Math.abs(x2 - x1) <= tol * 0.5f || Math.abs(y2 - y1) <= tol * 0.5f;
                if (axis && len >= minWire) wires.add(norm(x1, y1, x2, y2));
                else parts.add(new float[]{Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)});
            }
        }
        findLineRectangles(wires, parts, tol, symMax);

        // group symbol parts that touch
        List<float[]> syms = cluster(parts, Math.max(tol * 2f, h * 0.8f)); // e.g. the open gap of a contact
        int nSmall = syms.size();
        syms.addAll(bigs);

        // cut wires where they run through a small symbol
        List<float[]> pieces = new ArrayList<>();  // x1,y1,x2,y2
        List<int[]> ends = new ArrayList<>();      // symbol index attached at end 0 / end 1 (-1 none)
        for (float[] w : wires) clip(w, syms, nSmall, tol, pieces, ends);
        res.wires = pieces.size();

        // attach loose wire ends that stop at a symbol
        for (int i = 0; i < pieces.size(); i++) {
            float[] p = pieces.get(i);
            int[] e = ends.get(i);
            for (int end = 0; end < 2; end++) {
                if (e[end] >= 0) continue;
                float px = p[end * 2], py = p[end * 2 + 1];
                int best = -1; float bd = margin;
                for (int s = 0; s < syms.size(); s++) {
                    float d = boxDist(syms.get(s), px, py);
                    if (d <= bd) { bd = d; best = s; }
                }
                e[end] = best;
            }
        }

        // nets
        int n = pieces.size();
        int[] uf = new int[n];
        for (int i = 0; i < n; i++) uf[i] = i;
        for (int i = 0; i < n; i++) {
            float[] a = pieces.get(i);
            for (int j = i + 1; j < n; j++) {
                float[] b = pieces.get(j);
                if (a[0] > Math.max(b[0], b[2]) + tol && a[2] > Math.max(b[0], b[2]) + tol) continue;
                if (Math.max(a[0], a[2]) < Math.min(b[0], b[2]) - tol) continue;
                if (touches(a, b, tol) || touches(b, a, tol)) union(uf, i, j);
            }
        }
        for (float[] d : dots) {
            int first = -1;
            for (int i = 0; i < n; i++) {
                if (segDist(pieces.get(i), d[0], d[1]) <= dotMax) {
                    if (first < 0) first = i; else union(uf, first, i);
                }
            }
        }
        int[] net = new int[n];
        for (int i = 0; i < n; i++) net[i] = find(uf, i);
        Map<Integer, Integer> netSize = new HashMap<>();
        for (int i = 0; i < n; i++) netSize.merge(net[i], 1, Integer::sum);
        boolean[] stub = new boolean[n];
        for (int i = 0; i < n; i++) {
            float[] p = pieces.get(i);
            int[] e = ends.get(i);
            float len = Math.abs(p[2] - p[0]) + Math.abs(p[3] - p[1]);
            boolean oneEnd = (e[0] >= 0) != (e[1] >= 0);
            stub[i] = netSize.get(net[i]) == 1 && oneEnd && len < h * 4f;
        }

        // classify words
        List<PageGeom.Word> pinWords = new ArrayList<>(), tagWords = new ArrayList<>(), potWords = new ArrayList<>(), descWords = new ArrayList<>();
        for (PageGeom.Word w : g.words) {
            String t = w.text.trim();
            if (t.isEmpty()) continue;
            String u = t.toUpperCase(Locale.US);
            boolean pot = POT.matcher(t).matches() && !u.matches("^\\d+$");
            boolean tag = TAG.matcher(u).matches();
            boolean pin = PIN.matcher(u).matches();
            if (pot) potWords.add(w);
            if (tag) tagWords.add(w);
            if (pin || u.matches("^\\d+$")) pinWords.add(w);
            if (!pot && !tag && !pin && t.length() > 2 && t.matches(".*[A-Za-z]{2,}.*")) descWords.add(w);
        }

        // net labels from supply/ground text sitting on a wire
        Map<Integer, String> netLabel = new HashMap<>();
        for (PageGeom.Word w : potWords) {
            int best = -1; float bd = Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                float[] pc = pieces.get(i);
                float d = segDist(pc, w.x, w.y);
                float endD = Math.min((float) Math.hypot(pc[0] - w.x, pc[1] - w.y), (float) Math.hypot(pc[2] - w.x, pc[3] - w.y));
                float limit = endD <= d + 0.01f ? h * 5f : h * 2.5f; // beside the end of a line, or along it
                if (d <= limit && d < bd) { bd = d; best = i; }
            }
            if (best >= 0 && !netLabel.containsKey(net[best])) netLabel.put(net[best], potName(w.text));
        }

        // pins per symbol: one per net touching it
        List<Map<Integer, float[]>> symPins = new ArrayList<>();
        for (int s = 0; s < syms.size(); s++) symPins.add(new LinkedHashMap<>());
        for (int i = 0; i < n; i++) {
            if (stub[i]) continue;
            float[] p = pieces.get(i);
            int[] e = ends.get(i);
            for (int end = 0; end < 2; end++) {
                if (e[end] < 0) continue;
                Map<Integer, float[]> m = symPins.get(e[end]);
                if (!m.containsKey(net[i])) m.put(net[i], new float[]{p[end * 2], p[end * 2 + 1]});
            }
        }

        // pin names: nearest pin-like word to each pin point
        Set<PageGeom.Word> usedAsPin = new java.util.HashSet<>();
        List<Map<Integer, String>> pinNames = new ArrayList<>();
        for (int s = 0; s < syms.size(); s++) {
            Map<Integer, String> names = new LinkedHashMap<>();
            for (Map.Entry<Integer, float[]> pe : symPins.get(s).entrySet()) {
                float[] pt = pe.getValue();
                PageGeom.Word best = null; float bd = h * 3.5f;
                for (PageGeom.Word w : pinWords) {
                    if (w.text.trim().startsWith("-")) continue;
                    float d = (float) Math.hypot(w.x - pt[0], w.y - pt[1]);
                    if (!w.text.trim().matches("^\\d+$")) d *= 1.15f;
                    if (d < bd) { bd = d; best = w; }
                }
                names.put(pe.getKey(), best == null ? "" : best.text.trim().toUpperCase(Locale.US));
                if (best != null) usedAsPin.add(best);
            }
            pinNames.add(names);
        }

        // device tags: each tag word goes to its nearest wired symbol
        String[] symTag = new String[syms.size()];
        float[] symTagD = new float[syms.size()];
        Arrays.fill(symTagD, Float.MAX_VALUE);
        for (PageGeom.Word w : tagWords) {
            boolean dash = w.text.trim().startsWith("-") || w.text.contains("-");
            if (usedAsPin.contains(w) && !dash) continue;
            int best = -1; float bd = h * 7f;
            for (int s = 0; s < syms.size(); s++) {
                if (symPins.get(s).isEmpty()) continue;
                float d = boxDist(syms.get(s), w.x, w.y);
                if (d < bd) { bd = d; best = s; }
            }
            if (best < 0) continue;
            float score = dash ? bd * 0.6f : bd;
            if (score < symTagD[best]) { symTagD[best] = score; symTag[best] = tagName(w.text); }
        }

        // descriptions
        Map<Integer, List<PageGeom.Word>> symDesc = new HashMap<>();
        for (List<PageGeom.Word> phrase : phrases(descWords)) {
            int best = -1; float bd = h * 5f;
            for (int s = 0; s < syms.size(); s++) {
                if (symPins.get(s).isEmpty()) continue;
                for (PageGeom.Word w : phrase) {
                    float d = boxDist(syms.get(s), w.x, w.y);
                    if (d < bd) { bd = d; best = s; }
                }
            }
            if (best >= 0) symDesc.computeIfAbsent(best, k -> new ArrayList<>()).addAll(phrase);
        }

        // build elements and graph edges
        List<int[]> edges = new ArrayList<>();          // netA, netB, elemIndex
        Map<Integer, List<String>> netMembers = new HashMap<>();
        Map<Integer, String> sourceLabel = new HashMap<>();
        List<Boolean> isLoad = new ArrayList<>();
        int symbolsWithPins = 0;
        for (int s = 0; s < syms.size(); s++) {
            Map<Integer, String> pins = pinNames.get(s);
            if (pins.isEmpty()) continue;
            symbolsWithPins++;
            String tag = symTag[s] == null ? "?" : symTag[s];
            String desc = joinDesc(symDesc.get(s));
            List<Integer> nets = new ArrayList<>(pins.keySet());
            List<int[]> pairs = s >= nSmall ? new ArrayList<>() : pairPins(nets, pins); // module boxes: every pin is a source/sink
            Set<Integer> paired = new java.util.HashSet<>();
            for (int[] pr : pairs) {
                Elem e = new Elem();
                e.tag = tag;
                String p1 = pins.get(pr[0]), p2 = pins.get(pr[1]);
                if (!p1.isEmpty() && p1.equals(p2)) e.label = tag + ":" + p1;
                else if (!p1.isEmpty() && !p2.isEmpty()) e.label = tag + ":" + p1 + "-" + p2;
                else e.label = tag;
                e.kind = kindOf(tag, p1, p2, s >= nSmall);
                e.desc = desc;
                res.elems.add(e);
                int idx = res.elems.size() - 1;
                edges.add(new int[]{pr[0], pr[1], idx});
                isLoad.add(isLoadKind(e.kind));
                netMembers.computeIfAbsent(pr[0], k -> new ArrayList<>()).add(tag + (p1.isEmpty() ? "" : ":" + p1));
                netMembers.computeIfAbsent(pr[1], k -> new ArrayList<>()).add(tag + (p2.isEmpty() ? "" : ":" + p2));
                paired.add(pr[0]); paired.add(pr[1]);
            }
            for (int nt : nets) {
                if (paired.contains(nt)) continue;
                String pn = pins.get(nt);
                String lab = tag + (pn.isEmpty() ? "" : ":" + pn);
                netMembers.computeIfAbsent(nt, k -> new ArrayList<>()).add(lab);
                if (!"?".equals(tag)) sourceLabel.putIfAbsent(nt, lab);
                Elem e = new Elem();
                e.tag = tag; e.label = lab; e.kind = s >= nSmall ? "module" : kindOf(tag, pn, "", false); e.desc = desc;
                res.elems.add(e);
                isLoad.add(false);
            }
            if ("?".equals(tag)) res.issues.add("Page " + g.page + ": a part with " + pins.size() + " wire(s) has no readable tag (UNREADABLE).");
        }
        res.symbols = symbolsWithPins;

        // pin info for component cards
        for (int[] ed : edges) {
            Elem e = res.elems.get(ed[2]);
            e.pinInfo.add(pinLine(e, 0, ed[0], netMembers, netLabel));
            e.pinInfo.add(pinLine(e, 1, ed[1], netMembers, netLabel));
        }

        // circuits
        Set<Integer> supply = new java.util.HashSet<>(), ground = new java.util.HashSet<>();
        for (Map.Entry<Integer, String> nl : netLabel.entrySet()) {
            if (isGroundName(nl.getValue())) ground.add(nl.getKey()); else supply.add(nl.getKey());
        }
        for (Integer nt : sourceLabel.keySet()) if (!netLabel.containsKey(nt)) supply.add(nt);

        for (int ei = 0; ei < edges.size(); ei++) {
            int[] ed = edges.get(ei);
            Elem load = res.elems.get(ed[2]);
            if (!isLoad.get(ed[2])) continue;
            List<int[]> up1 = bfs(ed[0], supply, edges, isLoad, ei), dn1 = bfs(ed[1], ground, edges, isLoad, ei);
            List<int[]> up2 = bfs(ed[1], supply, edges, isLoad, ei), dn2 = bfs(ed[0], ground, edges, isLoad, ei);
            int s1 = score(up1, dn1), s2 = score(up2, dn2);
            List<int[]> up = s1 <= s2 ? up1 : up2, dn = s1 <= s2 ? dn1 : dn2;
            Circuit c = new Circuit();
            c.page = g.page;
            c.loadTag = load.tag; c.loadLabel = load.label; c.loadKind = load.kind; c.desc = load.desc;
            c.complete = up != null && dn != null;
            if (up != null) {
                int startNet = up.isEmpty() ? (s1 <= s2 ? ed[0] : ed[1]) : up.get(up.size() - 1)[1];
                c.nodes.add(netName(startNet, netLabel, sourceLabel));
                for (int k = up.size() - 1; k >= 0; k--) c.nodes.add(res.elems.get(up.get(k)[0]).label);
            } else c.nodes.add("? (supply not on this page)");
            c.nodes.add(load.label);
            if (dn != null) {
                for (int[] st : dn) c.nodes.add(res.elems.get(st[0]).label);
                int endNet = dn.isEmpty() ? (s1 <= s2 ? ed[1] : ed[0]) : dn.get(dn.size() - 1)[1];
                c.nodes.add(netName(endNet, netLabel, sourceLabel));
            } else c.nodes.add("? (return not on this page)");
            for (Elem other : res.elems)
                if (other != load && !other.desc.isEmpty() && c.nodes.contains(other.label) && !c.partDescs.contains(other.tag + " " + other.desc))
                    c.partDescs.add(other.tag + " " + other.desc);
            res.circuits.add(c);
        }
        return res;
    }

    /** BFS from net to any target net, not using loads. Returns steps [elemIndex, netReached] from start outward, or null. */
    private static List<int[]> bfs(int start, Set<Integer> targets, List<int[]> edges, List<Boolean> isLoad, int skip) {
        if (targets.contains(start)) return new ArrayList<>();
        Map<Integer, int[]> prev = new HashMap<>(); // net -> [prevNet, elemIndex]
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(start);
        prev.put(start, new int[]{-1, -1});
        while (!q.isEmpty()) {
            int cur = q.poll();
            for (int i = 0; i < edges.size(); i++) {
                if (i == skip) continue;
                int[] e = edges.get(i);
                if (isLoad.get(e[2])) continue;
                int nx = e[0] == cur ? e[1] : e[1] == cur ? e[0] : -1;
                if (nx < 0 || prev.containsKey(nx)) continue;
                prev.put(nx, new int[]{cur, e[2]});
                if (targets.contains(nx)) {
                    List<int[]> path = new ArrayList<>();
                    int at = nx;
                    while (prev.get(at)[0] >= 0) { int[] pv = prev.get(at); path.add(new int[]{pv[1], at}); at = pv[0]; }
                    Collections.reverse(path);
                    return path;
                }
                q.add(nx);
            }
        }
        return null;
    }

    private static int score(List<int[]> up, List<int[]> dn) {
        int s = 0;
        s += up == null ? 1000 : up.size();
        s += dn == null ? 1000 : dn.size();
        return s;
    }

    private static String netName(int nt, Map<Integer, String> netLabel, Map<Integer, String> src) {
        if (netLabel.containsKey(nt)) return netLabel.get(nt);
        if (src.containsKey(nt)) return src.get(nt);
        return "?";
    }

    private static String pinLine(Elem e, int side, int nt, Map<Integer, List<String>> members, Map<Integer, String> netLabel) {
        String[] parts = e.label.contains(":") ? e.label.substring(e.label.indexOf(':') + 1).split("-") : new String[0];
        String pin = parts.length == 2 ? parts[side] : parts.length == 1 ? parts[0] : (side == 0 ? "side 1" : "side 2");
        Set<String> other = new LinkedHashSet<>();
        if (netLabel.containsKey(nt)) other.add(netLabel.get(nt));
        String self = e.tag + ":" + pin;
        for (String m : members.getOrDefault(nt, Collections.emptyList())) if (!m.equals(self) && !m.equals(e.tag)) other.add(m);
        return pin + " → " + (other.isEmpty() ? "nothing else on this page" : String.join(", ", other));
    }

    // ---------- turning page results into cards ----------

    public static final class DocResult {
        public final List<Card> cards = new ArrayList<>();
        public String report = "";
        public int circuits, complete, parts;
        /** Every page was a scan: nothing was read, so no cards should be saved. */
        public boolean scannedOnly;
    }

    public static DocResult toCards(String fileName, List<PageResult> pages) {
        DocResult out = new DocResult();
        String code = docCode(fileName);
        String docId = "DOC-" + code;

        // coil circuits (what switches each relay contact)
        Map<String, String> coilCkt = new HashMap<>();
        Map<String, Integer> idCount = new HashMap<>();
        List<Card> ckts = new ArrayList<>();
        for (PageResult pr : pages) {
            for (Circuit c : pr.circuits) {
                String base = "CKT-" + code + "-" + safe(c.loadTag) + ("coil".equals(c.loadKind) ? "-COIL" : "");
                int k = idCount.merge(base, 1, Integer::sum);
                Card card = new Card();
                card.id = k == 1 ? base : base + "-" + k;
                if ("coil".equals(c.loadKind) && !c.loadTag.equals("?")) coilCkt.putIfAbsent(c.loadTag, card.id);
                card.title = c.loadTag + " " + (c.desc.isEmpty() ? c.loadKind : c.desc) + " circuit (" + shortName(fileName) + " p." + c.page + ")";
                card.path = String.join(" -> ", c.nodes);
                card.load = c.loadTag;
                card.source = shortName(fileName) + " page " + c.page;
                card.simple = "Read automatically from the drawing. Check terminal numbers against " + card.source + " before relying on it."
                        + (c.complete ? "" : " Part of the path is not on this page (it may continue on another sheet).");
                Set<String> tags = new LinkedHashSet<>();
                for (String nd : c.nodes) { String t = nd.contains(":") ? nd.substring(0, nd.indexOf(':')) : nd; if (t.matches("[A-Z]{1,3}\\d.*")) tags.add(t); }
                tags.addAll(kindWords(c.loadKind));
                tags.addAll(words(c.desc));
                for (String pd : c.partDescs) tags.addAll(words(pd));
                tags.add(code.toLowerCase(Locale.US));
                if (!c.partDescs.isEmpty()) card.simple += " Parts: " + String.join("; ", c.partDescs) + ".";
                card.tags = new ArrayList<>(tags);
                card.user = true;
                ckts.add(card);
                out.circuits++;
                if (c.complete) out.complete++;
            }
        }
        for (Card card : ckts) {
            Set<String> links = new LinkedHashSet<>();
            List<String> ctl = new ArrayList<>();
            for (String nd : CircuitTracer.nodes(card.path)) {
                String t = nd.contains(":") ? nd.substring(0, nd.indexOf(':')) : nd;
                if (t.matches("[A-Z]{1,3}\\d.*")) links.add("C-" + code + "-" + safe(t));
                if (t.startsWith("K") && !t.equals(card.load) && nd.contains(":") && !nd.contains("A1-A2") && !nd.contains("A2-A1")) {
                    String coil = coilCkt.get(t);
                    ctl.add(t + " coil" + (coil != null ? " (see " + coil + ")" : " (coil not found in this document)"));
                    links.add("P-004");
                }
            }
            if (!ctl.isEmpty()) card.control = String.join("; ", ctl);
            links.add("P-003");
            links.add("P-009");
            links.add("P-010");
            links.add(docId);
            card.links = new ArrayList<>(links);
            card.checks = new ArrayList<>(); // checks are built live from the path
            out.cards.add(card);
        }

        // component cards, one per tag
        Map<String, Card> comps = new LinkedHashMap<>();
        Map<String, Set<Integer>> compPages = new HashMap<>();
        for (PageResult pr : pages) {
            for (Elem e : pr.elems) {
                if (e.tag.equals("?")) continue;
                String id = "C-" + code + "-" + safe(e.tag);
                Card c = comps.get(id);
                if (c == null) {
                    c = new Card();
                    c.id = id;
                    c.title = e.tag + " " + (e.desc.isEmpty() ? e.kind : e.desc);
                    Set<String> tags = new LinkedHashSet<>();
                    tags.add(e.tag);
                    tags.addAll(kindWords(e.kind));
                    tags.addAll(words(e.desc));
                    c.tags = new ArrayList<>(tags);
                    c.user = true;
                    c.links = new ArrayList<>(Collections.singletonList(docId));
                    comps.put(id, c);
                    compPages.put(id, new java.util.TreeSet<>());
                }
                compPages.get(id).add(pr.page);
                if (!e.desc.isEmpty() && c.title.equals(e.tag + " " + firstKind(c))) c.title = e.tag + " " + e.desc;
                StringBuilder sb = new StringBuilder(c.simple);
                if (sb.length() > 0) sb.append("\n");
                sb.append("p.").append(pr.page).append(" ").append(e.label).append(" (").append(e.kind).append(")");
                for (String pi : e.pinInfo) sb.append("\n   ").append(pi);
                c.simple = sb.toString();
                if (!c.tags.contains(e.kind)) for (String kw : kindWords(e.kind)) if (!c.tags.contains(kw)) c.tags.add(kw);
            }
        }
        for (Map.Entry<String, Card> ce : comps.entrySet()) {
            Card c = ce.getValue();
            List<String> pg = new ArrayList<>();
            for (int p : compPages.get(ce.getKey())) pg.add(String.valueOf(p));
            c.source = shortName(fileName) + " page " + String.join(", ", pg);
            c.simple = "Read automatically from the drawing; check against the page.\n" + c.simple;
            out.cards.add(c);
        }
        out.parts = comps.size();

        // document card + report
        StringBuilder rep = new StringBuilder();
        int withWiring = 0;
        List<String> empty = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        for (PageResult pr : pages) {
            if (pr.symbols > 0) withWiring++; else empty.add(String.valueOf(pr.page));
            issues.addAll(pr.issues);
        }
        rep.append("Read ").append(pages.size()).append(" page(s), ").append(withWiring).append(" with wiring.\n");
        rep.append("Found ").append(out.parts).append(" parts and ").append(out.circuits).append(" circuits (")
                .append(out.complete).append(" complete supply → 0V).\n");
        if (!issues.isEmpty()) {
            rep.append("\nUnreadable items (").append(issues.size()).append("):\n");
            for (int k = 0; k < Math.min(8, issues.size()); k++) rep.append("• ").append(issues.get(k)).append("\n");
            if (issues.size() > 8) rep.append("• … and ").append(issues.size() - 8).append(" more\n");
        }
        int scanned = 0;
        for (PageResult pr : pages) if (pr.scanned) scanned++;
        if (scanned > 0 && scanned == pages.size()) {
            rep.setLength(0);
            rep.append("This PDF is scanned pictures of the pages (").append(scanned).append(" page(s)).\n")
               .append("It has no lines or text inside, so the drawing reader can't read it.\n\n")
               .append("What you can do: send the PDF to Claude once to turn it into cards, then import them here (Cards → Import file).\n");
        } else if (scanned > 0) {
            rep.append("\n").append(scanned).append(" page(s) are scanned pictures and were skipped.\n");
        } else if (withWiring == 0) rep.append("\nNo wiring found on any page.\n");
        rep.append("\nAlways check terminal numbers against the drawing before relying on them.");
        out.report = rep.toString().trim();

        Card doc = new Card();
        doc.id = docId;
        doc.title = shortName(fileName);
        doc.tags = new ArrayList<>(words(shortName(fileName).replaceAll("[_.\\-]", " ")));
        doc.tags.add(code.toLowerCase(Locale.US));
        doc.simple = "Drawing read automatically. " + out.report.replace("\n", " ");
        out.scannedOnly = scanned > 0 && scanned == pages.size();
        doc.source = shortName(fileName);
        doc.user = true;
        out.cards.add(doc);
        return out;
    }

    // ---------- helpers ----------

    private static List<int[]> pairPins(List<Integer> nets, Map<Integer, String> names) {
        List<int[]> pairs = new ArrayList<>();
        if (nets.size() == 2) { pairs.add(new int[]{nets.get(0), nets.get(1)}); return pairs; }
        if (nets.size() < 2) return pairs;
        Set<Integer> used = new java.util.HashSet<>();
        for (int a : nets) {
            if (used.contains(a)) continue;
            String na = names.get(a);
            for (int b : nets) {
                if (b == a || used.contains(b)) continue;
                if (partners(na, names.get(b))) {
                    pairs.add(new int[]{a, b});
                    used.add(a); used.add(b);
                    break;
                }
            }
        }
        return pairs;
    }

    /** Usual pin pairs: 13-14, 11-12, A1-A2, 1-2, 3-4, X1-X2, same number on both sides (terminal). */
    static boolean partners(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        String pa = a.replaceAll("\\d+$", ""), pb = b.replaceAll("\\d+$", "");
        String da = a.substring(pa.length()), db = b.substring(pb.length());
        if (!pa.equals(pb) || da.isEmpty() || db.isEmpty()) return false;
        int x = Integer.parseInt(da), y = Integer.parseInt(db);
        int lo = Math.min(x, y), hi = Math.max(x, y);
        // 1-2, 3-4 (poles); 11-12, 13-14, 21-22 ... (contacts: same tens digit)
        return hi - lo == 1 && lo % 2 == 1 && lo / 10 == hi / 10;
    }

    static String kindOf(String tag, String p1, String p2, boolean big) {
        String L = tag.replaceAll("[^A-Z].*$", "");
        boolean coilPins = (p1 + p2).matches("A1A2|A2A1");
        if (big) return "module";
        if (L.isEmpty()) return "part";
        if (coilPins && (L.startsWith("K") || L.startsWith("Q") || L.startsWith("Y"))) return "coil";
        switch (L.charAt(0)) {
            case 'H': return "lamp";
            case 'E': return "lamp";
            case 'M': return "motor";
            case 'Y': return "valve";
            case 'P': return "meter";
            case 'F': return "fuse";
            case 'Q': return "breaker";
            case 'K': return "contact";
            case 'S': return "switch";
            case 'X': return "terminal";
            case 'B': return "sensor";
            case 'R': return "resistor";
            case 'V': return "diode";
            case 'A': return "module";
            case 'W': return "cable";
            case 'G': return "supply";
            default: return "part";
        }
    }

    private static String firstKind(Card c) {
        int nl = c.simple.indexOf('(');
        if (nl < 0) return c.title.substring(c.title.indexOf(' ') + 1);
        return c.simple.substring(nl + 1, c.simple.indexOf(')', nl));
    }

    private static boolean isLoadKind(String k) {
        return k.equals("lamp") || k.equals("coil") || k.equals("motor") || k.equals("valve") || k.equals("meter");
    }

    private static List<String> kindWords(String k) {
        switch (k) {
            case "lamp": return Arrays.asList("lamp", "light", "indicator");
            case "coil": return Arrays.asList("relay", "coil");
            case "contact": return Arrays.asList("relay", "contact");
            case "motor": return Collections.singletonList("motor");
            case "valve": return Arrays.asList("valve", "solenoid");
            case "fuse": return Collections.singletonList("fuse");
            case "breaker": return Arrays.asList("breaker", "fuse");
            case "switch": return Collections.singletonList("switch");
            case "terminal": return Collections.singletonList("terminal");
            case "sensor": return Collections.singletonList("sensor");
            case "module": return Arrays.asList("module", "plc");
            default: return Collections.singletonList(k);
        }
    }

    private static List<String> words(String s) {
        List<String> r = new ArrayList<>();
        if (s == null) return r;
        for (String w : s.toLowerCase(Locale.US).split("[^a-z0-9]+")) if (w.length() > 2 && !r.contains(w)) r.add(w);
        return r;
    }

    /** Joins words that sit next to each other on one line ("Engine" "running" → one phrase). */
    private static List<List<PageGeom.Word>> phrases(List<PageGeom.Word> ws) {
        List<PageGeom.Word> l = new ArrayList<>(ws);
        l.sort((a, b) -> Math.abs(a.y - b.y) > a.h * 0.4f ? Float.compare(b.y, a.y) : Float.compare(a.x, b.x));
        List<List<PageGeom.Word>> out = new ArrayList<>();
        List<PageGeom.Word> cur = null;
        PageGeom.Word last = null;
        for (PageGeom.Word w : l) {
            boolean join = last != null && Math.abs(w.y - last.y) <= last.h * 0.4f
                    && (w.x - last.x) - (w.w + last.w) / 2f <= last.h * 1.2f && w.x > last.x;
            if (!join) { cur = new ArrayList<>(); out.add(cur); }
            cur.add(w);
            last = w;
        }
        return out;
    }

    private static String joinDesc(List<PageGeom.Word> ws) {
        if (ws == null || ws.isEmpty()) return "";
        List<PageGeom.Word> l = new ArrayList<>(ws);
        // reading order: top to bottom (PDF y grows upward), then left to right
        l.sort((a, b) -> Math.abs(a.y - b.y) > a.h * 0.5f ? Float.compare(b.y, a.y) : Float.compare(a.x, b.x));
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < Math.min(6, l.size()); k++) { if (sb.length() > 0) sb.append(' '); sb.append(l.get(k).text.trim()); }
        return sb.toString();
    }

    static String tagName(String t) {
        String u = t.trim().toUpperCase(Locale.US);
        int d = u.lastIndexOf('-');
        if (d >= 0 && d < u.length() - 1) u = u.substring(d + 1);
        return u.replaceAll("^[=+]+", "");
    }

    static String potName(String t) {
        String u = t.trim().toUpperCase(Locale.US).replace(" ", "");
        if (u.startsWith("+") && u.length() > 1 && Character.isDigit(u.charAt(1))) u = u.substring(1);
        u = u.replace("VDC", "V").replace("VAC", "V AC");
        if (u.matches("^\\d+([.,]\\d+)?$")) u = u + "V";
        return u;
    }

    static boolean isGroundName(String u) {
        u = u.toUpperCase(Locale.US);
        return u.equals("0V") || u.equals("M") || u.equals("GND") || u.equals("L-") || u.equals("PE") || u.equals("N")
                || u.equals("-UB") || u.equals("UB-") || u.equals("BAT-") || u.equals("31") || u.equals("KL31") || u.equals("KL.31") || u.startsWith("-");
    }

    /** The short code used in card IDs for this file name, e.g. CKT-CODE-H5. */
    public static String docCodeOf(String fileName) { return docCode(fileName); }

    static String docCode(String fileName) {
        String base = shortName(fileName).replaceAll("\\.[A-Za-z0-9]+$", "").toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        if (base.isEmpty()) base = "DRAW";
        return base.length() > 10 ? base.substring(0, 10) : base;
    }

    static String shortName(String fileName) {
        String f = fileName == null ? "drawing.pdf" : fileName;
        int s = Math.max(f.lastIndexOf('/'), f.lastIndexOf('\\'));
        return s >= 0 ? f.substring(s + 1) : f;
    }

    private static String safe(String t) { return t.replaceAll("[^A-Za-z0-9]", ""); }

    private static float medianHeight(PageGeom g) {
        if (g.words.isEmpty()) return 7f;
        float[] hs = new float[g.words.size()];
        for (int k = 0; k < hs.length; k++) hs[k] = g.words.get(k).h;
        Arrays.sort(hs);
        float m = hs[hs.length / 2];
        return Math.max(2f, Math.min(30f, m));
    }

    private static float[] bbox(float[] p) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (int k = 0; k + 1 < p.length; k += 2) {
            x0 = Math.min(x0, p[k]); x1 = Math.max(x1, p[k]);
            y0 = Math.min(y0, p[k + 1]); y1 = Math.max(y1, p[k + 1]);
        }
        return new float[]{x0, y0, x1, y1};
    }

    private static float[] norm(float x1, float y1, float x2, float y2) {
        if (x2 < x1 || (x2 == x1 && y2 < y1)) return new float[]{x2, y2, x1, y1};
        return new float[]{x1, y1, x2, y2};
    }

    /** Rectangles drawn as four separate lines (coils, fuses) are symbols, not wires. */
    private static void findLineRectangles(List<float[]> wires, List<float[]> parts, float tol, float symMax) {
        List<Integer> hor = new ArrayList<>(), ver = new ArrayList<>();
        for (int i = 0; i < wires.size(); i++) {
            float[] w = wires.get(i);
            if (Math.abs(w[1] - w[3]) <= tol) hor.add(i); else ver.add(i);
        }
        Set<Integer> remove = new java.util.HashSet<>();
        for (int a = 0; a < hor.size(); a++) {
            float[] t = wires.get(hor.get(a));
            if (t[2] - t[0] > symMax) continue;
            for (int b = a + 1; b < hor.size(); b++) {
                float[] u = wires.get(hor.get(b));
                if (Math.abs(t[0] - u[0]) > tol || Math.abs(t[2] - u[2]) > tol) continue;
                float y0 = Math.min(t[1], u[1]), y1 = Math.max(t[1], u[1]);
                if (y1 - y0 < tol * 2 || y1 - y0 > symMax) continue;
                int left = -1, right = -1;
                for (int v : ver) {
                    float[] w = wires.get(v);
                    if (Math.abs(w[1] - y0) <= tol && Math.abs(w[3] - y1) <= tol) {
                        if (Math.abs(w[0] - t[0]) <= tol) left = v;
                        else if (Math.abs(w[0] - t[2]) <= tol) right = v;
                    }
                }
                if (left >= 0 && right >= 0) {
                    remove.add(hor.get(a)); remove.add(hor.get(b)); remove.add(left); remove.add(right);
                    parts.add(new float[]{t[0], y0, t[2], y1});
                }
            }
        }
        if (remove.isEmpty()) return;
        List<float[]> keep = new ArrayList<>();
        for (int i = 0; i < wires.size(); i++) if (!remove.contains(i)) keep.add(wires.get(i));
        wires.clear();
        wires.addAll(keep);
    }

    private static List<float[]> cluster(List<float[]> boxes, float gap) {
        int n = boxes.size();
        int[] uf = new int[n];
        for (int i = 0; i < n; i++) uf[i] = i;
        for (int i = 0; i < n; i++) {
            float[] a = boxes.get(i);
            for (int j = i + 1; j < n; j++) {
                float[] b = boxes.get(j);
                if (a[0] - gap <= b[2] && b[0] - gap <= a[2] && a[1] - gap <= b[3] && b[1] - gap <= a[3]) union(uf, i, j);
            }
        }
        Map<Integer, float[]> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int r = find(uf, i);
            float[] b = boxes.get(i);
            float[] c = m.get(r);
            if (c == null) m.put(r, new float[]{b[0], b[1], b[2], b[3]});
            else { c[0] = Math.min(c[0], b[0]); c[1] = Math.min(c[1], b[1]); c[2] = Math.max(c[2], b[2]); c[3] = Math.max(c[3], b[3]); }
        }
        return new ArrayList<>(m.values());
    }

    /** Cuts a straight wire where it passes through small symbols; remembers which symbol each cut end touches. */
    private static void clip(float[] w, List<float[]> syms, int nSmall, float tol, List<float[]> out, List<int[]> ends) {
        boolean horiz = Math.abs(w[1] - w[3]) <= Math.abs(w[0] - w[2]);
        float a0 = horiz ? w[0] : w[1], a1 = horiz ? w[2] : w[3];
        float c = horiz ? (w[1] + w[3]) / 2 : (w[0] + w[2]) / 2;
        // removed intervals along the wire, with symbol index
        List<float[]> cuts = new ArrayList<>();
        for (int s = 0; s < nSmall; s++) {
            float[] b = syms.get(s);
            float lo = (horiz ? b[0] : b[1]) - tol, hi = (horiz ? b[2] : b[3]) + tol;
            float clo = (horiz ? b[1] : b[0]) - tol, chi = (horiz ? b[3] : b[2]) + tol;
            if (c < clo || c > chi) continue;
            float s0 = Math.max(a0, lo), s1 = Math.min(a1, hi);
            if (s1 <= s0) continue;
            cuts.add(new float[]{s0, s1, s});
        }
        cuts.sort((x, y) -> Float.compare(x[0], y[0]));
        float start = a0;
        int startSym = -1;
        for (float[] ct : cuts) {
            if (ct[0] > start + tol) addPiece(horiz, c, start, ct[0], startSym, (int) ct[2], out, ends);
            if (ct[1] > start) { start = ct[1]; startSym = (int) ct[2]; }
        }
        if (a1 > start + tol) addPiece(horiz, c, start, a1, startSym, -1, out, ends);
    }

    private static void addPiece(boolean horiz, float c, float a, float b, int s0, int s1, List<float[]> out, List<int[]> ends) {
        out.add(horiz ? new float[]{a, c, b, c} : new float[]{c, a, c, b});
        ends.add(new int[]{s0, s1});
    }

    /** True when an end of a lies on b (end-to-end or T-joint). */
    private static boolean touches(float[] a, float[] b, float tol) {
        return segDist(b, a[0], a[1]) <= tol || segDist(b, a[2], a[3]) <= tol;
    }

    private static float segDist(float[] s, float px, float py) {
        float dx = s[2] - s[0], dy = s[3] - s[1];
        float l2 = dx * dx + dy * dy;
        float t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - s[0]) * dx + (py - s[1]) * dy) / l2));
        return (float) Math.hypot(px - (s[0] + t * dx), py - (s[1] + t * dy));
    }

    private static float boxDist(float[] b, float px, float py) {
        float dx = Math.max(Math.max(b[0] - px, 0), px - b[2]);
        float dy = Math.max(Math.max(b[1] - py, 0), py - b[3]);
        return (float) Math.hypot(dx, dy);
    }

    private static int find(int[] uf, int i) {
        while (uf[i] != i) { uf[i] = uf[uf[i]]; i = uf[i]; }
        return i;
    }

    private static void union(int[] uf, int a, int b) {
        int ra = find(uf, a), rb = find(uf, b);
        if (ra != rb) uf[ra] = rb;
    }
}

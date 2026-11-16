package com.fieldhelper.diag.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Holds all cards and finds them by tags, IDs and words. */
public class CardIndex {

    public static class Hit {
        public final Card card;
        public final int score;
        public Hit(Card c, int s) { card = c; score = s; }
    }

    private final Map<String, Card> byId = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> weights = new HashMap<>();
    /** Word pairs in each card title, e.g. "slot 6", "plug x9": matching the pair beats matching the words apart. */
    private final Map<String, Set<String>> pairs = new HashMap<>();
    private static final int PAIR_BONUS = 8;

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "the", "a", "an", "is", "are", "was", "of", "in", "on", "at", "to", "it", "its", "and", "or",
            "my", "what", "why", "how", "does", "do", "i", "me", "this", "that", "with", "for", "when",
            "which", "can", "could", "be", "will", "please", "tell", "about", "show", "explain", "there",
            "module", "panel", "issue", "problem", "check"));

    /** Words that mean the same thing are folded to one word. */
    private static final Map<String, String> SYN = new HashMap<>();
    static {
        for (String w : new String[]{"glow", "glowing", "glows", "lit", "light", "lights", "illuminate", "lighting", "lamp", "lamps", "indicator", "bulb"})
            SYN.put(w, "light");
        for (String w : new String[]{"not", "no", "dead", "fail", "failed", "fails", "failure", "faulty", "fault", "faults",
                "broken", "dont", "doesnt", "isnt", "wont", "nothing", "stuck", "trip", "trips", "tripped", "blown"})
            SYN.put(w, "fault");
        for (String w : new String[]{"relay", "relays", "contactor", "contactors"}) SYN.put(w, "relay");
        for (String w : new String[]{"fuse", "fuses", "mcb", "breaker", "breakers"}) SYN.put(w, "fuse");
        for (String w : new String[]{"ground", "earth", "gnd", "0v", "negative", "return"}) SYN.put(w, "ground");
        for (String w : new String[]{"supply", "power", "24v", "voltage", "volts", "positive"}) SYN.put(w, "supply");
        for (String w : new String[]{"run", "running", "runs", "started", "start", "starting", "crank", "cranking"}) SYN.put(w, "run");
        for (String w : new String[]{"sensor", "sensors", "transmitter", "transducer", "sender"}) SYN.put(w, "sensor");
        for (String w : new String[]{"plc", "controller", "ecu", "output", "outputs", "do"}) SYN.put(w, "plc");
        for (String w : new String[]{"ohm", "ohms", "ohm's", "ohmslaw"}) SYN.put(w, "ohm");
        for (String w : new String[]{"diode", "diodes", "freewheel", "flyback"}) SYN.put(w, "diode");
        for (String w : new String[]{"can", "canbus", "feldbus", "fieldbus"}) SYN.put(w, "canbus");
        // abbreviations common on wiring diagrams
        SYN.put("emerg", "emergency"); SYN.put("emergancy", "emergency");
        SYN.put("temp", "temperature"); SYN.put("press", "pressure"); SYN.put("pres", "pressure");
        SYN.put("instr", "instrument"); SYN.put("instruments", "instrument"); SYN.put("sens", "sensor");
        SYN.put("mon", "monitoring"); SYN.put("ctrl", "control"); SYN.put("sw", "switch");
    }

    public void clear() { byId.clear(); weights.clear(); pairs.clear(); }

    public void add(Card c) {
        if (c.id.isEmpty()) return;
        String key = c.id.toUpperCase();
        byId.put(key, c);
        Map<String, Integer> w = new HashMap<>();
        put(w, c.id, 8);
        for (String t : c.tags) put(w, t, 5);
        put(w, c.title, 3);
        put(w, c.path, 2);
        put(w, c.load, 3);
        put(w, c.control, 2);
        put(w, c.simple, 1);
        for (String s : c.checks) put(w, s, 1);
        weights.put(key, w);
        pairs.put(key, pairsOf(c.title));
    }

    public void addAll(Collection<Card> cards) { for (Card c : cards) add(c); }

    public boolean remove(String id) {
        String key = id.toUpperCase();
        weights.remove(key);
        pairs.remove(key);
        return byId.remove(key) != null;
    }

    private static Set<String> pairsOf(String text) {
        Set<String> r = new HashSet<>();
        List<String> t = tokens(text, true);
        for (int k = 0; k + 1 < t.size(); k++) r.add(t.get(k) + " " + t.get(k + 1));
        return r;
    }

    private static void put(Map<String, Integer> w, String text, int weight) {
        for (String t : tokens(text, false)) {
            Integer old = w.get(t);
            if (old == null || old < weight) w.put(t, weight);
        }
    }

    public Card get(String id) {
        if (id == null) return null;
        String key = id.trim().toUpperCase();
        int sp = key.indexOf(' ');
        if (sp > 0) key = key.substring(0, sp);
        return byId.get(key);
    }

    public List<Card> all() { return new ArrayList<>(byId.values()); }

    public int size() { return byId.size(); }

    /** Cards that link to this ID ("used in"). */
    public List<Card> usedBy(String id) {
        List<Card> r = new ArrayList<>();
        for (Card c : byId.values())
            for (String l : c.links)
                if (sameId(l, id)) { r.add(c); break; }
        return r;
    }

    public static boolean sameId(String linkText, String id) {
        String a = linkText.trim().toUpperCase();
        int sp = a.indexOf(' ');
        if (sp > 0) a = a.substring(0, sp);
        return a.equals(id.trim().toUpperCase());
    }

    /** Splits text into lower-case search words, with synonyms folded. */
    public static List<String> tokens(String text, boolean dropStop) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return new ArrayList<>(out);
        String t = text.toLowerCase().replace("→", " ").replace("->", " ").replace("←", " ").replace("<-", " ")
                .replace("’", "").replace("'", "")
                .replaceAll("\\bcan[\\s-]+(bus|h|l|high|low)\\b", "canbus $1"); // "CAN bus" is not the verb "can"
        for (String raw : t.split("[^a-z0-9:.\\-+_]+")) {
            String w = trimPunct(raw);
            if (w.isEmpty()) continue;
            addWord(out, w, dropStop);
            if (w.contains(":") || w.contains("-")) {
                for (String part : w.split("[:\\-]")) {
                    part = trimPunct(part);
                    if (part.length() > 1 || part.matches("[0-9]")) addWord(out, part, dropStop);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static void addWord(Set<String> out, String w, boolean dropStop) {
        if (dropStop && STOP.contains(w)) return;
        String s = SYN.get(w);
        out.add(s != null ? s : w);
    }

    private static String trimPunct(String w) {
        int a = 0, b = w.length();
        while (a < b && ":.-+_".indexOf(w.charAt(a)) >= 0) a++;
        while (b > a && ":.-+_".indexOf(w.charAt(b - 1)) >= 0) b--;
        return w.substring(a, b);
    }

    public List<Hit> search(String query, int limit) {
        List<String> q = tokens(query, true);
        boolean faultWords = q.contains("fault");
        Set<String> qPairs = pairsOf(query);
        List<Hit> hits = new ArrayList<>();
        if (q.isEmpty()) return hits;
        for (Map.Entry<String, Card> e : byId.entrySet()) {
            Map<String, Integer> w = weights.get(e.getKey());
            int score = 0, matched = 0;
            for (String t : q) {
                Integer v = w.get(t);
                if (v != null) { score += v; matched++; }
            }
            if (matched == 0) continue;
            score += matched * 2;
            Set<String> cp = pairs.get(e.getKey());
            if (cp != null) for (String p : qPairs) if (cp.contains(p)) score += PAIR_BONUS;
            Card c = e.getValue();
            String type = c.type();
            if (faultWords && type.equals("Fault")) score += 6;
            // the document summary card ranks below its sheets; sheet cards (DOC-...-P5) are not penalised
            if (type.equals("Source") && !c.id.toUpperCase().matches("^DOC-.+-P\\d+$")) score -= 3;
            hits.add(new Hit(c, score));
        }
        hits.sort((x, y) -> y.score - x.score);
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }
}

package com.fieldhelper.diag.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds answers only from index cards, and lists every card it used. */
public class Answerer {
    public static final int MIN_SCORE = 7;
    /** With the donor model: minimum meaning-similarity for an answer, and the weight of meaning vs words. */
    public static final float MIN_SIMILARITY = 0.30f;
    private static final float MEANING_WEIGHT = 50f;
    private static final int STRONG_WORDS = 15;
    private final CardIndex index;
    private SemanticSearch semantic;

    public Answerer(CardIndex index) { this.index = index; }

    public void setSemantic(SemanticSearch s) { semantic = s; }

    /**
     * Ranks cards. Words only, or (when the donor model is ready) words + meaning together:
     * score = word score + 50 × (similarity − 0.30). A card must also be close in meaning
     * (similarity ≥ 0.30) unless the words match strongly (e.g. an exact tag like K12).
     */
    private List<CardIndex.Hit> rank(String query, boolean[] byMeaning) {
        if (semantic == null || !semantic.ready()) {
            List<CardIndex.Hit> hits = index.search(query, 6);
            if (hits.isEmpty() || hits.get(0).score < MIN_SCORE) return new ArrayList<>();
            return hits;
        }
        java.util.Map<String, Integer> words = new java.util.HashMap<>();
        for (CardIndex.Hit h : index.search(query, 40)) words.put(h.card.id.toUpperCase(), h.score);
        java.util.Map<String, Float> sims = new java.util.HashMap<>();
        for (SemanticSearch.Match m : semantic.search(query, 60)) sims.put(m.cardId.toUpperCase(), m.similarity);
        Set<String> ids = new LinkedHashSet<>(words.keySet());
        ids.addAll(sims.keySet());
        List<CardIndex.Hit> out = new ArrayList<>();
        java.util.Map<CardIndex.Hit, float[]> parts = new java.util.HashMap<>();
        for (String id : ids) {
            Card c = index.get(id);
            if (c == null) continue;
            int w = words.getOrDefault(id, 0);
            float s = sims.getOrDefault(id, 0f);
            int fused = Math.round(w + MEANING_WEIGHT * (s - MIN_SIMILARITY));
            CardIndex.Hit h = new CardIndex.Hit(c, fused);
            out.add(h);
            parts.put(h, new float[]{w, s});
        }
        out.sort((a, b) -> b.score - a.score);
        if (out.isEmpty()) return out;
        float[] top = parts.get(out.get(0));
        boolean ok = out.get(0).score >= MIN_SCORE && (top[1] >= MIN_SIMILARITY || top[0] >= STRONG_WORDS);
        if (!ok) {
            // keep only close matches for the "Not in index" hint
            List<CardIndex.Hit> near = new ArrayList<>();
            for (CardIndex.Hit h : out) if (parts.get(h)[1] >= 0.25f && near.size() < 3) near.add(new CardIndex.Hit(h.card, 0));
            return near;
        }
        byMeaning[0] = top[0] < MIN_SCORE;
        return out.size() > 6 ? new ArrayList<>(out.subList(0, 6)) : out;
    }

    public Reply answer(String query) {
        boolean[] byMeaning = {false};
        List<CardIndex.Hit> hits = rank(query, byMeaning);
        if (hits.isEmpty() || hits.get(0).score < MIN_SCORE) {
            Reply r = new Reply("Not in index.\nI only answer from my cards, so I won't guess. "
                    + "Add a card for this in the Cards tab (circuit path, fault checks, source page)"
                    + (hits.isEmpty() ? "." : ", or try one of these close matches:\n" + list(hits, 3)));
            r.kind = "none";
            return r;
        }
        Card top = hits.get(0).card;
        Set<String> used = new LinkedHashSet<>();
        StringBuilder b = new StringBuilder();
        switch (top.type()) {
            case "Fault": fault(top, b, used); break;
            case "Circuit": circuit(top, b, used, true); break;
            case "Component": component(top, b, used); break;
            default: general(top, b, used);
        }
        List<CardIndex.Hit> others = new ArrayList<>();
        for (CardIndex.Hit h : hits.subList(1, hits.size()))
            if (!used.contains(h.card.id) && h.score >= MIN_SCORE && h.score * 2 >= hits.get(0).score) others.add(h);
        if (!others.isEmpty()) b.append("\n\nAlso related:\n").append(list(others, 3));
        if (byMeaning[0]) b.append("\n\n(Found by meaning: your words didn't match the card exactly. Check it's the right card.)");
        Reply r = new Reply(b.toString().trim());
        r.refs.addAll(refs(used));
        r.kind = top.type();
        return r;
    }

    private String list(List<CardIndex.Hit> hits, int max) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < Math.min(max, hits.size()); k++)
            b.append("• ").append(hits.get(k).card.id).append("  ").append(hits.get(k).card.title).append("\n");
        return b.toString().trim();
    }

    private void fault(Card f, StringBuilder b, Set<String> used) {
        used.add(f.id);
        b.append("Symptom: ").append(f.title).append("\n");
        if (!f.simple.isEmpty()) b.append(f.simple).append("\n");
        Card ckt = firstLinked(f, "Circuit");
        if (ckt != null) {
            b.append("\nHow it should work:\n");
            circuit(ckt, b, used, f.checks.isEmpty());
        }
        if (!f.checks.isEmpty()) {
            b.append("\nCheck in this order:\n");
            for (int k = 0; k < f.checks.size(); k++) b.append(k + 1).append(") ").append(f.checks.get(k)).append("\n");
        }
        principles(f, ckt, b, used);
    }

    private void circuit(Card c, StringBuilder b, Set<String> used, boolean withChecks) {
        used.add(c.id);
        b.append(c.title).append("\n");
        if (!c.path.isEmpty()) b.append("Path: ").append(c.path.replace("->", "→")).append("\n");
        if (!c.control.isEmpty()) b.append("Switched by: ").append(c.control).append("\n");
        if (!c.simple.isEmpty()) b.append(c.simple).append("\n");
        if (withChecks && !c.path.isEmpty()) {
            List<String> chk = CircuitTracer.checks(c.path, c.load, c.control);
            if (!chk.isEmpty()) {
                b.append("\nIf it doesn't work, check in this order:\n");
                for (int k = 0; k < chk.size(); k++) b.append(k + 1).append(") ").append(chk.get(k)).append("\n");
            }
            List<String> faults = new ArrayList<>();
            for (Card x : index.usedBy(c.id)) if (x.type().equals("Fault") && !used.contains(x.id)) faults.add(x.id + " " + x.title);
            if (!faults.isEmpty()) b.append("Fault cards: ").append(String.join("; ", faults)).append("\n");
            principles(c, null, b, used);
        }
    }

    private void component(Card c, StringBuilder b, Set<String> used) {
        used.add(c.id);
        b.append(c.title).append("\n");
        if (!c.simple.isEmpty()) b.append(c.simple).append("\n");
        List<Card> in = index.usedBy(c.id);
        List<String> ckts = new ArrayList<>();
        for (Card x : in) if (x.type().equals("Circuit")) { ckts.add(x.id + " " + x.title); used.add(x.id); }
        if (!ckts.isEmpty()) b.append("\nUsed in: ").append(String.join("; ", ckts)).append("\n");
        List<String> linked = new ArrayList<>();
        for (String l : c.links) {
            Card x = index.get(l);
            if (x != null && !x.type().equals("Principle") && !used.contains(x.id)) linked.add(x.id + " " + x.title);
        }
        if (!linked.isEmpty()) b.append("Linked to: ").append(String.join("; ", linked)).append("\n");
        principles(c, null, b, used);
    }

    private void general(Card c, StringBuilder b, Set<String> used) {
        used.add(c.id);
        b.append(c.title).append("\n");
        if (!c.simple.isEmpty()) b.append(c.simple).append("\n");
        if (!c.checks.isEmpty()) {
            b.append("\n");
            for (int k = 0; k < c.checks.size(); k++) b.append(k + 1).append(") ").append(c.checks.get(k)).append("\n");
        }
        List<String> linked = new ArrayList<>();
        for (String l : c.links) {
            Card x = index.get(l);
            if (x != null) linked.add(x.id + " " + x.title);
        }
        if (!linked.isEmpty()) b.append("\nSee also: ").append(String.join("; ", linked)).append("\n");
    }

    private void principles(Card a, Card b2, StringBuilder b, Set<String> used) {
        List<Card> ps = new ArrayList<>();
        for (Card src : new Card[]{a, b2}) {
            if (src == null) continue;
            for (String l : src.links) {
                Card p = index.get(l);
                if (p != null && p.type().equals("Principle") && !used.contains(p.id) && !ps.contains(p)) ps.add(p);
            }
        }
        if (ps.isEmpty()) return;
        b.append("\nPrinciple reminder:\n");
        for (Card p : ps) {
            used.add(p.id);
            b.append("• ").append(p.title).append(": ").append(firstSentence(p.simple)).append("\n");
        }
    }

    private Card firstLinked(Card c, String type) {
        for (String l : c.links) {
            Card x = index.get(l);
            if (x != null && x.type().equals(type)) return x;
        }
        return null;
    }

    private static String firstSentence(String s) {
        int d = s.indexOf(". ");
        return d > 0 ? s.substring(0, d + 1) : s;
    }

    /** Card IDs used plus their source pages, e.g. "F-021", "DOC-02 sheet 7". */
    private List<String> refs(Set<String> used) {
        Set<String> r = new LinkedHashSet<>(used);
        for (String id : used) {
            Card c = index.get(id);
            if (c != null && !c.source.isEmpty())
                for (String s : c.source.split(";")) if (!s.trim().isEmpty()) r.add(s.trim());
        }
        return new ArrayList<>(r);
    }
}

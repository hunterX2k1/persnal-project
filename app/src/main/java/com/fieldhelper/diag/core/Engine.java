package com.fieldhelper.diag.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The helper's brain. Sends each question to the right specialist:
 * calculators, maths, or the card index (answers only from cards, with references).
 * Has no Android code, so it can be tested on any computer.
 */
public class Engine {
    public final CardIndex index = new CardIndex();
    private final Answerer answerer = new Answerer(index);

    /** Plugs in the donor model (meaning search). Null = words only. */
    public void setSemantic(SemanticSearch s) { answerer.setSemantic(s); }
    private final List<Card> userCards = new ArrayList<>();

    /** Loads one JSON file of cards: either [ {...}, ... ] or { "cards": [ ... ] }. Returns how many. */
    @SuppressWarnings("unchecked")
    public int loadCards(String json, boolean user) {
        Object root = Json.parse(json);
        List<Object> list;
        if (root instanceof Map) list = (List<Object>) ((Map<String, Object>) root).get("cards");
        else list = (List<Object>) root;
        int n = 0;
        if (list == null) return 0;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Card c = Card.fromMap((Map<String, Object>) o);
            if (c.id.isEmpty()) continue;
            if (user) { c.user = true; replaceUser(c); }
            index.add(c);
            n++;
        }
        return n;
    }

    private void replaceUser(Card c) {
        for (int k = 0; k < userCards.size(); k++)
            if (userCards.get(k).id.equalsIgnoreCase(c.id)) { userCards.set(k, c); return; }
        userCards.add(c);
    }

    /** Adds or replaces a card made in the editor. */
    public void saveUserCard(Card c) {
        c.user = true;
        replaceUser(c);
        index.add(c);
    }

    public boolean deleteUserCard(String id) {
        for (int k = 0; k < userCards.size(); k++) {
            if (userCards.get(k).id.equalsIgnoreCase(id)) {
                userCards.remove(k);
                index.remove(id);
                return true;
            }
        }
        return false;
    }

    /** Removes a drawing's DOC card and every card read from it (IDs ...-CODE-...). Returns how many. */
    public int deleteDocument(String code) {
        String c = code.toUpperCase(Locale.US);
        List<String> ids = new ArrayList<>();
        for (Card x : userCards) {
            String u = x.id.toUpperCase(Locale.US);
            if (u.equals("DOC-" + c) || u.startsWith("DOC-" + c + "-P") || u.startsWith("CKT-" + c + "-") || u.startsWith("C-" + c + "-")) ids.add(x.id);
        }
        for (String id : ids) deleteUserCard(id);
        return ids.size();
    }

    /** All user cards as JSON, to save to phone storage. */
    public String userCardsJson() {
        List<Object> l = new ArrayList<>();
        for (Card c : userCards) l.add(c.toMap());
        return Json.write(l);
    }

    public Reply ask(String question) {
        String q = question == null ? "" : question.trim();
        String low = q.toLowerCase(Locale.US);
        if (q.isEmpty() || low.equals("help") || low.equals("?")) return help();
        try {
            if (low.startsWith("drop")) return ElecCalc.drop(q);
            if (low.startsWith("4-20") || low.startsWith("ma ")) return ElecCalc.scale(q);
            if (low.startsWith("conv")) return ElecCalc.convert(q);
            if (low.startsWith("trace ")) return trace(q.substring(6));
            if (low.startsWith("card ")) return showCard(q.substring(5).trim());
            if (MathEval.looksLikeMath(q)) {
                try {
                    String e = q.startsWith("=") ? q.substring(1) : q;
                    Reply r = new Reply(e.trim() + " = " + MathEval.fmt(MathEval.eval(e)));
                    r.kind = "math";
                    return r;
                } catch (RuntimeException ignored) { /* not maths after all: fall through */ }
            }
            if (ElecCalc.isOhm(q) && (ElecCalc.onlyValues(q) || answerer.answer(q).kind.equals("none"))) return ElecCalc.ohm(q);
            return answerer.answer(q);
        } catch (RuntimeException e) {
            Reply r = new Reply("Sorry, I couldn't handle that: " + e.getMessage());
            r.kind = "error";
            return r;
        }
    }

    /** "trace 24V -> F1 -> S1 -> H1 -> 0V" : instant check list for any path you type. */
    public Reply trace(String path) {
        List<String> chk = CircuitTracer.checks(path, "", "");
        if (chk.isEmpty()) return new Reply("Type a path with arrows, e.g. trace 24V -> F1 -> S1 -> H1 -> 0V");
        StringBuilder b = new StringBuilder(CircuitTracer.worksWhen(path, "", "")).append("\n\nIf it doesn't work, check in this order:\n");
        for (int k = 0; k < chk.size(); k++) b.append(k + 1).append(") ").append(chk.get(k)).append("\n");
        Reply r = new Reply(b.toString().trim());
        r.refs.add("P-003");
        r.kind = "trace";
        return r;
    }

    public Reply showCard(String id) {
        Card c = index.get(id);
        if (c == null) return new Reply("No card " + id + ".");
        StringBuilder b = new StringBuilder(c.id + " · " + c.type() + (c.user ? " (yours)" : "") + "\n" + c.title + "\n");
        if (!c.tags.isEmpty()) b.append("Tags: ").append(String.join(", ", c.tags)).append("\n");
        if (!c.simple.isEmpty()) b.append("\n").append(c.simple).append("\n");
        if (!c.path.isEmpty()) b.append("\nPath: ").append(c.path).append("\n");
        if (!c.load.isEmpty()) b.append("Load: ").append(c.load).append("\n");
        if (!c.control.isEmpty()) b.append("Switched by: ").append(c.control).append("\n");
        if (!c.checks.isEmpty()) {
            b.append("\nChecks:\n");
            for (int k = 0; k < c.checks.size(); k++) b.append(k + 1).append(") ").append(c.checks.get(k)).append("\n");
        }
        if (!c.links.isEmpty()) b.append("\nLinks: ").append(String.join(", ", c.links)).append("\n");
        List<String> back = new ArrayList<>();
        for (Card x : index.usedBy(c.id)) back.add(x.id);
        if (!back.isEmpty()) b.append("Linked from: ").append(String.join(", ", back)).append("\n");
        if (!c.source.isEmpty()) b.append("Source: ").append(c.source).append("\n");
        Reply r = new Reply(b.toString().trim());
        r.kind = "card";
        return r;
    }

    public Reply help() {
        Reply r = new Reply("I answer only from my index cards and show which cards I used.\n\n"
                + "Ask about a symptom:  H5 lamp not glowing\n"
                + "Ask about a part:  what does K12 do\n"
                + "Ask about a principle:  how does a relay work\n"
                + "Trace any path:  trace 24V -> F1 -> S1 -> H1 -> 0V\n"
                + "Read your wiring PDFs:  Cards tab → Read drawing PDF (or Try practice drawing)\n"
                + "Ohm's law:  V=24 R=12   or   24V 5W\n"
                + "Cable drop:  drop L=20 A=1.5 I=5 V=24\n"
                + "4-20 mA:  4-20 12 0 10 bar\n"
                + "Convert:  conv 5 bar psi\n"
                + "Maths:  (24-0.7)/2.2k   sqrt(3)*400*16\n"
                + "Open a card:  card F-001\n\n"
                + "Cards loaded: " + index.size());
        r.kind = "help";
        return r;
    }
}

package com.fieldhelper.diag.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One index card. ID prefix tells the type:
 * P- principle, S- system, C- component, CKT- circuit, F- fault, DOC- source document.
 */
public class Card {
    public String id = "";
    public String title = "";
    public List<String> tags = new ArrayList<>();
    public String simple = "";
    /** Circuits only: "24V -> F3 -> K12:13-14 -> X2:5 -> H5 -> X2:6 -> 0V" */
    public String path = "";
    /** Circuits only: the part that should work (lamp, coil, motor). */
    public String load = "";
    /** Circuits only: what switches the path, e.g. "K12 coil <- PLC DO4". */
    public String control = "";
    /** Fault cards: checks in order. */
    public List<String> checks = new ArrayList<>();
    public List<String> links = new ArrayList<>();
    /** e.g. "DOC-02 sheet 7" */
    public String source = "";
    /** True when the user made it in the card editor. */
    public boolean user = false;

    public String type() {
        String u = id.toUpperCase();
        if (u.startsWith("CKT-")) return "Circuit";
        if (u.startsWith("DOC-")) return "Source";
        if (u.startsWith("P-")) return "Principle";
        if (u.startsWith("S-")) return "System";
        if (u.startsWith("C-")) return "Component";
        if (u.startsWith("F-")) return "Fault";
        return "Card";
    }

    @SuppressWarnings("unchecked")
    public static Card fromMap(Map<String, Object> m) {
        Card c = new Card();
        c.id = str(m.get("id")).trim();
        c.title = str(m.get("title"));
        c.simple = str(m.get("simple"));
        c.path = str(m.get("path"));
        c.load = str(m.get("load"));
        c.control = str(m.get("control"));
        c.source = str(m.get("source"));
        c.tags = list(m.get("tags"));
        c.checks = list(m.get("checks"));
        c.links = list(m.get("links"));
        Object u = m.get("user");
        c.user = u instanceof Boolean && (Boolean) u;
        return c;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("tags", new ArrayList<Object>(tags));
        m.put("simple", simple);
        if (!path.isEmpty()) m.put("path", path);
        if (!load.isEmpty()) m.put("load", load);
        if (!control.isEmpty()) m.put("control", control);
        if (!checks.isEmpty()) m.put("checks", new ArrayList<Object>(checks));
        m.put("links", new ArrayList<Object>(links));
        m.put("source", source);
        if (user) m.put("user", Boolean.TRUE);
        return m;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static List<String> list(Object o) {
        List<String> r = new ArrayList<>();
        if (o instanceof List) {
            for (Object x : (List<?>) o) if (x != null && !x.toString().trim().isEmpty()) r.add(x.toString().trim());
        } else if (o instanceof String) {
            for (String x : ((String) o).split(",")) if (!x.trim().isEmpty()) r.add(x.trim());
        }
        return r;
    }

    /** Splits "a, b, c" typed in the editor. */
    public static List<String> splitList(String text, String sep) {
        List<String> r = new ArrayList<>();
        if (text == null) return r;
        for (String x : text.split(sep)) if (!x.trim().isEmpty()) r.add(x.trim());
        return r;
    }
}

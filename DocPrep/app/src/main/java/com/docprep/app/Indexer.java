package com.docprep.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the cross-part map that keeps context when a document is split:
 * which part holds which pages, where every figure/table/drawing is captioned and mentioned,
 * and where every instrument tag / equipment / document number appears.
 * Locations are compressed (p.40–43) and tag series collapsed (PT-1001…1300) so the index stays small.
 */
final class Indexer {
    private static final Pattern REF = Pattern.compile(
            "(?i)\\b(fig(?:ure)?s?\\.?|table|drawing|dwg\\.?|diagram|sheet|appendix|annex|section|clause)"
                    + "\\s*(?:no\\.?\\s*)?([A-Z]?\\d+(?:[.\\-]\\d+)*[A-Z]?)\\b");
    // ISA-style tags (PT-101, FIC 2001A, XV-305) and single-letter equipment (P-101 pump, V-201 vessel).
    private static final Pattern TAG = Pattern.compile("\\b(?:([A-Z]{2,5})[-_ ]?|([A-Z])-)(\\d{2,5}[A-Z]?)\\b");
    private static final int MAX_TAGS = 5000, MAX_REFS = 1000;

    /** A place in the document: a page number, a section number, or a labelled block (spreadsheet rows). */
    static final class Loc implements Comparable<Loc> {
        final int part, num; final String kind, label;
        Loc(String kind, int num, String label, int part) { this.kind = kind; this.num = num; this.label = label; this.part = part; }
        static Loc page(int n, int part) { return new Loc("p.", n, null, part); }
        static Loc section(int n, int part) { return new Loc("§", n, null, part); }
        static Loc block(int order, String label, int part) { return new Loc("", order, label, part); }
        @Override public int compareTo(Loc o) {
            if (part != o.part) return Integer.compare(part, o.part);
            if (!kind.equals(o.kind)) return kind.compareTo(o.kind);
            return Integer.compare(num, o.num);
        }
        @Override public boolean equals(Object o) { return o instanceof Loc && compareTo((Loc) o) == 0; }
        @Override public int hashCode() { return part * 31 + num; }
    }

    static final class Hit { final TreeSet<Loc> def = new TreeSet<>(), ment = new TreeSet<>(); }

    final Map<String, Hit> refs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    final Map<String, TreeSet<Loc>> tags = new TreeMap<>();
    final Map<String, Loc> toc = new LinkedHashMap<>();
    final List<String> notes = new ArrayList<>();
    private int tagOverflow;

    void addToc(String title, Loc where) { if (toc.size() < 3000) toc.put(title, where); }

    void scan(String text, Loc where) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            Matcher m = REF.matcher(line);
            while (m.find()) {
                String key = normKind(m.group(1)) + " " + m.group(2);
                Hit h = refs.get(key);
                if (h == null) { if (refs.size() >= MAX_REFS) continue; h = new Hit(); refs.put(key, h); }
                // Caption = reference at the start of a line ("Figure 3: Pump control loop")
                if (!trimmed.isEmpty() && m.start() <= line.indexOf(trimmed) + 1) h.def.add(where); else h.ment.add(where);
            }
            Matcher t = TAG.matcher(line);
            while (t.find()) {
                String tag = (t.group(1) != null ? t.group(1) : t.group(2)) + "-" + t.group(3);
                TreeSet<Loc> s = tags.get(tag);
                if (s == null) { if (tags.size() >= MAX_TAGS) { tagOverflow++; continue; } s = new TreeSet<>(); tags.put(tag, s); }
                s.add(where);
            }
        }
    }

    private static String normKind(String k) {
        k = k.toLowerCase().replace(".", "");
        if (k.startsWith("fig")) return "Figure";
        if (k.startsWith("dwg") || k.startsWith("drawing")) return "Drawing";
        return Character.toUpperCase(k.charAt(0)) + k.substring(1);
    }

    /** "p.1–7, 9 (part 1); p.40–43 (part 2)" */
    static String where(TreeSet<Loc> locs, int maxItems) {
        if (locs.isEmpty()) return "–";
        StringBuilder s = new StringBuilder();
        int part = -1, items = 0;
        Loc runStart = null, prev = null;
        List<String> pieces = new ArrayList<>();
        for (Loc l : locs) {
            boolean cont = prev != null && l.part == prev.part && l.kind.equals(prev.kind) && l.label == null && prev.label == null && l.num == prev.num + 1;
            if (!cont && runStart != null) pieces.add(piece(runStart, prev));
            if (prev == null || l.part != prev.part) {
                if (prev != null) { s.append(String.join(", ", pieces)).append(" (part ").append(part).append("); "); pieces.clear(); }
                part = l.part;
            }
            if (!cont) runStart = l;
            prev = l;
            if (++items > maxItems) { pieces.add("…+" + (locs.size() - maxItems) + " more"); runStart = null; break; }
        }
        if (runStart != null) pieces.add(piece(runStart, prev));
        s.append(String.join(", ", pieces)).append(" (part ").append(part).append(")");
        return s.toString();
    }

    private static String piece(Loc a, Loc b) {
        if (a.label != null) return a.label;
        return a == b || a.num == b.num ? a.kind + a.num : a.kind + a.num + "–" + b.num;
    }

    /** Collapses PT-1001, PT-1002 … PT-1300 into "PT-1001…1300" (only same prefix, same digit count, no suffix). */
    private static List<String> collapseTags(List<String> sorted) {
        List<String> out = new ArrayList<>();
        Pattern p = Pattern.compile("([A-Z]+-)(\\d+)");
        int i = 0;
        while (i < sorted.size()) {
            Matcher m = p.matcher(sorted.get(i));
            if (!m.matches()) { out.add(sorted.get(i++)); continue; }
            String pre = m.group(1), digits = m.group(2);
            long start = Long.parseLong(digits), last = start;
            int j = i + 1;
            while (j < sorted.size()) {
                Matcher n = p.matcher(sorted.get(j));
                if (!n.matches() || !n.group(1).equals(pre) || n.group(2).length() != digits.length()
                        || Long.parseLong(n.group(2)) != last + 1) break;
                last++; j++;
            }
            out.add(j - i >= 3 ? pre + digits + "…" + String.format("%0" + digits.length() + "d", last) : sorted.get(i));
            if (j - i < 3) j = i + 1;
            i = j;
        }
        return out;
    }

    /** Renders the index, shrinking it step by step until it fits the token budget. */
    String render(String title, String sourceInfo, List<PartWriter.Part> parts, String settings, long tokenBudget) {
        String s = null;
        for (int level = 0; level <= 3; level++) {
            s = render(title, sourceInfo, parts, settings, level);
            if (s.length() / 4 <= tokenBudget) break;
        }
        return s;
    }

    private String render(String title, String sourceInfo, List<PartWriter.Part> parts, String settings, int level) {
        StringBuilder s = new StringBuilder();
        s.append("# INDEX – ").append(title).append("\n\n").append(sourceInfo).append("\n").append(settings).append("\n\n");
        s.append("## How to read this package (for Claude)\n");
        s.append("- The original document was split into **").append(parts.size())
         .append(" part(s)**. Every part contains this same INDEX.md, so you always have the map of the whole document.\n");
        s.append("- `document.md` holds this part's content **in original reading order**. `## Page N` / `## Section N` headings mark where each original page/section starts.\n");
        s.append("- `**[FIGURE …]**` lines show exactly where an image belongs. The file in `images/` is the picture/drawing/table **from that same place**; the text right after it comes from the same page, so labels in the image match the words.\n");
        s.append("- `_t1…_t4` images are zoomed tiles (top-left, top-right, bottom-left, bottom-right) of a large drawing for reading small print.\n");
        s.append("- If something refers to a page, figure or tag in **another part**, the lists below say which part has it – ask the user to upload that part.\n");
        s.append("- `manifest.json` lists a SHA-256 fingerprint of every file; check them to confirm nothing was changed.\n\n");

        s.append("## Parts\n| Part | File | Covers | Approx. tokens | Size |\n|---|---|---|---|---|\n");
        for (PartWriter.Part p : parts) {
            s.append("| ").append(p.no).append(" | ").append(p.zipName).append(" | ").append(p.first)
             .append(p.first.equals(p.last) ? "" : " → " + p.last).append(" | ~").append(p.tokens / 1000).append("k | ")
             .append(String.format(java.util.Locale.US, "%.1f MB", p.zipBytes / 1048576.0)).append(" |\n");
        }
        if (!toc.isEmpty()) {
            s.append("\n## Table of contents\n");
            int n = 0;
            for (Map.Entry<String, Loc> e : toc.entrySet()) {
                if (level >= 3 && ++n > 150) { s.append("- … (").append(toc.size() - 150).append(" more entries omitted to save space)\n"); break; }
                TreeSet<Loc> one = new TreeSet<>(); one.add(e.getValue());
                s.append("- ").append(e.getKey()).append(" — ").append(where(one, 1)).append("\n");
            }
        }
        if (!refs.isEmpty()) {
            boolean mentions = level < 2;
            s.append("\n## Figures, tables, drawings, sections\n");
            s.append(mentions ? "| Item | Caption at | Mentioned at |\n|---|---|---|\n" : "| Item | Caption at |\n|---|---|\n");
            for (Map.Entry<String, Hit> e : refs.entrySet()) {
                s.append("| ").append(e.getKey()).append(" | ").append(where(e.getValue().def, 20));
                if (mentions) s.append(" | ").append(where(e.getValue().ment, 30));
                s.append(" |\n");
            }
        }
        if (!tags.isEmpty()) {
            s.append("\n## Tags & codes (instrument tags, equipment, document numbers)\n");
            if (level >= 1) {
                s.append("_").append(tags.size()).append(" tags found; list omitted to keep this index small – search document.md of each part._\n");
            } else {
                // Group tags that appear in exactly the same places, then collapse numeric series.
                Map<String, List<String>> byPlace = new TreeMap<>();
                Map<String, TreeSet<Loc>> placeLocs = new TreeMap<>();
                for (Map.Entry<String, TreeSet<Loc>> e : tags.entrySet()) {
                    String k = where(e.getValue(), 25);
                    List<String> l = byPlace.get(k);
                    if (l == null) { l = new ArrayList<>(); byPlace.put(k, l); placeLocs.put(k, e.getValue()); }
                    l.add(e.getKey());
                }
                for (Map.Entry<String, List<String>> e : byPlace.entrySet()) {
                    s.append("- ").append(String.join(", ", collapseTags(e.getValue()))).append(" → ").append(e.getKey()).append("\n");
                }
                if (tagOverflow > 0) s.append("- … more tags not listed (limit reached)\n");
            }
        }
        if (!notes.isEmpty()) {
            s.append("\n## Processing notes\n");
            for (String n : notes) s.append("- ").append(n).append("\n");
        }
        return s.toString();
    }
}

package com.fieldhelper.diag.diagram;

import com.fieldhelper.diag.ai.PaddleOcr;
import com.fieldhelper.diag.core.Card;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns OCR text of scanned pages into one searchable card per sheet.
 * Text repeated on most sheets (title block, notes in the margin) is dropped so each card keeps
 * what is special to its sheet: sheet title, column headings, plug/pin/signal names.
 * OCR can misread characters, so every card says to check against the page.
 */
public final class ScanReader {
    private ScanReader() {}

    public static final class ScanPage {
        public final int page, width, height;
        public final List<PaddleOcr.Word> words;
        public ScanPage(int page, int width, int height, List<PaddleOcr.Word> words) {
            this.page = page; this.width = width; this.height = height; this.words = words;
        }
    }

    /** A line of text: words next to each other on the same row. */
    static final class Line {
        final StringBuilder text = new StringBuilder();
        int x0, y0, x1, y1;
        boolean vertical;
        String norm() { return text.toString().toLowerCase(Locale.US).replaceAll("[^a-z0-9]", ""); }
    }

    public static List<Card> toCards(String fileName, String code, List<ScanPage> pages) {
        List<Card> out = new ArrayList<>();
        Map<Integer, List<Line>> linesByPage = new HashMap<>();
        Map<String, Integer> seenOn = new HashMap<>();
        for (ScanPage p : pages) {
            List<Line> lines = lines(p.words);
            linesByPage.put(p.page, lines);
            Set<String> once = new LinkedHashSet<>();
            for (Line l : lines) if (l.norm().length() >= 3) once.add(l.norm());
            for (String n : once) seenOn.merge(n, 1, Integer::sum);
        }
        int limit = Math.max(3, (int) Math.ceil(pages.size() * 0.5));
        String docId = "DOC-" + code;
        String shortName = DiagramReader.shortName(fileName);
        StringBuilder toc = new StringBuilder();

        for (ScanPage p : pages) {
            List<Line> keep = new ArrayList<>();
            for (Line l : linesByPage.get(p.page)) {
                String n = l.norm();
                if (n.length() < 1) continue;
                if (pages.size() >= 3 && n.length() >= 3 && seenOn.getOrDefault(n, 0) >= limit) continue; // boilerplate
                if (n.length() == 1 && (l.x0 < p.width * 0.02 || l.x1 > p.width * 0.98 || l.y0 < p.height * 0.015)) continue; // frame grid letters
                keep.add(l);
            }
            // sheet title: text in the title block (bottom right); headings: top of the sheet
            List<String> title = new ArrayList<>(), headings = new ArrayList<>();
            for (Line l : keep) {
                boolean letters = l.text.toString().matches(".*[A-Za-z]{3,}.*");
                if (!letters || l.vertical) continue;
                if (l.y0 > p.height * 0.88 && l.x0 > p.width * 0.55) title.add(l.text.toString());
                else if (l.y1 < p.height * 0.12) headings.add(l.text.toString());
            }
            String t = title.isEmpty() ? (headings.isEmpty() ? "" : String.join(" / ", headings.subList(0, Math.min(3, headings.size())))) : pickTitle(title);
            Card c = new Card();
            c.id = docId + "-P" + p.page;
            Set<String> hs = new LinkedHashSet<>();
            for (String hd : headings) if (!t.contains(hd)) hs.add(hd.trim());
            String hl = String.join(", ", hs);
            if (hl.length() > 200) hl = hl.substring(0, 200).replaceAll(",[^,]*$", "") + " …";
            c.title = "Sheet " + p.page + (t.isEmpty() ? "" : ": " + t) + (hl.isEmpty() ? "" : " (" + hl + ")");
            Set<String> tags = new LinkedHashSet<>();
            for (String s : title) tags.addAll(words(s));
            for (String s : headings) tags.addAll(words(s));
            for (Line l : keep) {
                String s = l.text.toString();
                for (String w : s.split("[\\s,;()]+")) {
                    String u = w.toUpperCase(Locale.US);
                    if (u.matches("^[-+=][A-Z]{1,4}\\d{1,4}(\\.\\d+)?$") || u.matches("^[A-Z]{2,}\\d?_[A-Z0-9_+-]+$")) tags.add(u.toLowerCase(Locale.US));
                    // standard device letters (IEC 81346): -F070.2 is a fuse, -K12 a relay, -X2 a plug/terminal ...
                    if (u.matches("^-[A-Z]\\d{1,4}(\\.\\d+)?$")) for (String kw : deviceWords(u.charAt(1))) tags.add(kw);
                }
            }
            tags.add(code.toLowerCase(Locale.US));
            List<String> tl = new ArrayList<>(tags);
            c.tags = tl.size() > 40 ? new ArrayList<>(tl.subList(0, 40)) : tl;
            StringBuilder sb = new StringBuilder("Scanned sheet read by OCR: letters can be misread, check against " + shortName + " page " + p.page + ".");
            for (Line l : keep) sb.append("\n").append(l.text);
            c.simple = sb.toString();
            c.source = shortName + " page " + p.page;
            c.links = new ArrayList<>();
            c.links.add(docId);
            c.user = true;
            out.add(c);
            toc.append("\n").append(p.page).append(") ").append(t.isEmpty() ? "(no title read)" : t);
        }

        Card doc = new Card();
        doc.id = docId;
        doc.title = shortName;
        doc.tags = new ArrayList<>(words(shortName.replaceAll("[_.\\-]", " ")));
        doc.tags.add(code.toLowerCase(Locale.US));
        doc.simple = "Scanned drawing, " + pages.size() + " sheet(s) read by OCR (text only, wires are not followed). Sheets:" + toc;
        doc.source = shortName;
        doc.user = true;
        out.add(doc);
        return out;
    }

    /** Prefers the English line of a German/English pair (the line with more English words). */
    private static String pickTitle(List<String> title) {
        String best = title.get(0);
        int bestScore = -1;
        for (String s : title) {
            String u = s.toUpperCase(Locale.US);
            int score = 0;
            for (String w : new String[]{"PLUG", "SLOT", "POWER", "SUPPLY", "BUS", "FIELD", "INSTRUCTIONS", "CONTENTS", "BOARD", "INSTRUMENT", "SENSOR", "OUTPUT", "INPUT", "ENGINE", "PUMP", "VALVE", "WIRING"})
                if (u.contains(w)) score += 2;
            score += Math.min(4, s.length() / 10);
            if (score > bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    /** Groups horizontal words into lines (same row, small gap); sideways words stay on their own. */
    static List<Line> lines(List<PaddleOcr.Word> ws) {
        List<PaddleOcr.Word> hs = new ArrayList<>();
        List<Line> out = new ArrayList<>();
        for (PaddleOcr.Word w : ws) {
            if (w.vertical) {
                Line l = new Line();
                l.text.append(w.text); l.x0 = w.x0; l.y0 = w.y0; l.x1 = w.x1; l.y1 = w.y1; l.vertical = true;
                out.add(l);
            } else hs.add(w);
        }
        hs.sort((a, b) -> Math.abs(cy(a) - cy(b)) > (a.y1 - a.y0) * 0.5 ? Integer.compare(cy(a), cy(b)) : Integer.compare(a.x0, b.x0));
        Line cur = null;
        for (PaddleOcr.Word w : hs) {
            int h = w.y1 - w.y0;
            boolean join = cur != null && !cur.vertical && Math.abs(cy(w) - (cur.y0 + cur.y1) / 2) <= h * 0.5 && w.x0 - cur.x1 <= h * 1.5 && w.x0 > cur.x0;
            if (!join) {
                cur = new Line();
                cur.x0 = w.x0; cur.y0 = w.y0; cur.x1 = w.x1; cur.y1 = w.y1;
                out.add(cur);
            } else cur.text.append(' ');
            cur.text.append(w.text);
            cur.x1 = Math.max(cur.x1, w.x1); cur.y0 = Math.min(cur.y0, w.y0); cur.y1 = Math.max(cur.y1, w.y1);
        }
        out.sort((a, b) -> Math.abs(a.y0 - b.y0) > 10 ? Integer.compare(a.y0, b.y0) : Integer.compare(a.x0, b.x0));
        return out;
    }

    static String[] deviceWords(char letter) {
        switch (letter) {
            case 'F': return new String[]{"fuse"};
            case 'Q': return new String[]{"breaker", "fuse"};
            case 'K': return new String[]{"relay"};
            case 'X': return new String[]{"plug", "terminal", "connector"};
            case 'W': return new String[]{"cable"};
            case 'S': return new String[]{"switch"};
            case 'H': return new String[]{"lamp"};
            case 'B': return new String[]{"sensor"};
            case 'Y': return new String[]{"valve", "solenoid"};
            case 'M': return new String[]{"motor"};
            case 'G': return new String[]{"supply"};
            default: return new String[0];
        }
    }

    private static int cy(PaddleOcr.Word w) { return (w.y0 + w.y1) / 2; }

    private static List<String> words(String s) {
        List<String> r = new ArrayList<>();
        for (String w : s.toLowerCase(Locale.US).split("[^a-z0-9_+\\-]+")) if (w.length() > 2 && !r.contains(w)) r.add(w);
        return r;
    }
}

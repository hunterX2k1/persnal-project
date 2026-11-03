package com.docprep.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * PDF → pages in reading order. Each page keeps its text; pages with drawings, tables, pictures or scans
 * also get a page image placed right where the page starts, so text and image stay together.
 */
final class PdfSource {
    interface Progress { void update(String msg, int done, int total); boolean cancelled(); }

    private PdfSource() {}

    static void process(File pdf, File tempDir, Options opt, PartWriter w, Indexer idx, Progress pr) throws Exception {
        tempDir.mkdirs();
        // Up to 64 MB in memory, the rest in scratch files in our own folder (the system temp dir may not exist).
        MemoryUsageSetting mem = MemoryUsageSetting.setupMixed(64L * 1024 * 1024).setTempDir(tempDir);
        try (PDDocument doc = PDDocument.load(pdf, mem)) {
            int n = doc.getNumberOfPages();
            idx.note("PDF with " + n + " pages.");
            idx.content(n + " pages");

            // ---- Pass 1: text + "does this page have graphics?" ----
            String[] text = new String[n];
            boolean[] graphic = new boolean[n];
            PDFTextStripper st = new PDFTextStripper();
            st.setSortByPosition(true);
            for (int i = 0; i < n; i++) {
                if (pr.cancelled()) return;
                pr.update("Reading text, page " + (i + 1) + " of " + n, i, n * 2);
                st.setStartPage(i + 1);
                st.setEndPage(i + 1);
                try { text[i] = st.getText(doc); } catch (Exception e) { text[i] = ""; }
                try {
                    GraphicsCounter gc = new GraphicsCounter(doc.getPage(i));
                    gc.processPage(doc.getPage(i));
                    boolean scanned = text[i].trim().length() < 40 && (gc.bigImages > 0 || gc.segments > 0);
                    graphic[i] = gc.bigImages > 0 || gc.segments >= 80 || scanned;
                } catch (Exception e) {
                    graphic[i] = true;   // unsure: keep a picture of it
                }
            }

            // ---- Repeated header/footer lines (company name, "Page 3 of 90", revision boxes) ----
            Set<String> repeated = findRepeated(text);
            if (!repeated.isEmpty()) {
                StringBuilder ex = new StringBuilder("Removed repeated header/footer lines (shown once here): ");
                int k = 0;
                for (String r : repeated) { if (k++ > 0) ex.append(" | "); ex.append('“').append(r.substring(2)).append('”'); if (k >= 8) break; }
                idx.note(ex.toString());
            }

            // ---- Bookmarks → table of contents ----
            Map<Integer, List<String>> bookmarks = new TreeMap<>();
            try {
                if (doc.getDocumentCatalog().getDocumentOutline() != null) {
                    collectOutline(doc, doc.getDocumentCatalog().getDocumentOutline(), "", bookmarks, 0);
                }
            } catch (Exception ignored) {}

            // ---- Pass 2: render graphic pages, write units ----
            ParcelFileDescriptor fd = null;
            PdfRenderer native_ = null;
            try {
                fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
                native_ = new PdfRenderer(fd);
            } catch (Exception e) {
                idx.note("Android's PDF renderer could not open this file (protected?); used the slower built-in renderer.");
            }
            PDFRenderer fallback = null;
            String heading = null;
            int pageImages = 0;
            for (int i = 0; i < n; i++) {
                if (pr.cancelled()) return;
                pr.update("Building page " + (i + 1) + " of " + n, n + i, n * 2);
                List<String> marks = bookmarks.get(i);
                if (marks != null && !marks.isEmpty()) heading = marks.get(marks.size() - 1).trim();

                StringBuilder md = new StringBuilder();
                md.append("<!-- page ").append(i + 1).append(" -->\n## ").append(idx.label("Page " + (i + 1))).append("\n");
                if (marks != null) for (String m : marks) md.append("_Bookmark: ").append(m.trim()).append("_\n");
                md.append('\n');
                Unit u = new Unit(idx.label("Page " + (i + 1)), "");
                if (graphic[i]) {
                    if (opt.imageMode == Options.IMG_NONE) {
                        md.append("**[FIGURE ").append(idx.fig("p" + (i + 1))).append("]** _Drawing/table/picture on this page omitted (text-only mode)._\n\n");
                    } else {
                        Bitmap big = null;
                        try {
                            int edge = opt.imageMode == Options.IMG_HIGH ? Options.LONG_EDGE * 2 : Options.LONG_EDGE;
                            if (native_ != null) {
                                big = renderNative(native_, i, edge);
                            } else {
                                if (fallback == null) fallback = new PDFRenderer(doc);
                                PDRectangle box = doc.getPage(i).getCropBox();
                                float scale = edge / Math.max(box.getWidth(), box.getHeight());
                                big = fallback.renderImage(i, scale);
                            }
                        } catch (Throwable e) {      // includes OutOfMemoryError on huge sheets
                            idx.note("Page " + (i + 1) + ": could not render image (" + e.getClass().getSimpleName() + ").");
                        }
                        if (big != null) {
                            String base = idx.img(String.format(java.util.Locale.US, "p%04d", i + 1));
                            Images.addPageImages(big, base, opt.imageMode == Options.IMG_HIGH, u.images);
                            big.recycle();
                            pageImages++;
                            md.append("**[FIGURE ").append(idx.fig("p" + (i + 1))).append("]** Page image: `images/").append(base).append(".jpg`");
                            if (u.images.size() > 1) {
                                md.append(" · zoom tiles: ");
                                for (int t = 1; t < u.images.size(); t++) md.append(t > 1 ? ", " : "").append("`images/").append(u.images.get(t).name).append('`');
                            }
                            md.append("\n_This image shows the drawing/table/picture on page ").append(i + 1)
                              .append("; the text below is from the same page, so its words and the labels in the image belong together._\n\n");
                        }
                    }
                }
                String clean = clean(text[i], repeated);
                md.append(clean.isEmpty() ? "_(no text on this page)_" : clean);
                Unit unit = new Unit(u.label, md.toString());
                unit.images.addAll(u.images);
                unit.heading = heading;
                int part = w.add(unit);
                Indexer.Loc where = idx.page(i + 1, part);
                idx.scan(clean, where);
                if (marks != null) for (String m : marks) idx.addToc(m, where);
            }
            if (native_ != null) native_.close();
            if (fd != null) fd.close();
            idx.note(pageImages + " of " + n + " pages contain drawings/tables/pictures and were included as images.");
        }
    }

    private static Bitmap renderNative(PdfRenderer r, int i, int edge) {
        try (PdfRenderer.Page p = r.openPage(i)) {
            float s = edge / (float) Math.max(p.getWidth(), p.getHeight());
            int bw = Math.max(1, Math.round(p.getWidth() * s)), bh = Math.max(1, Math.round(p.getHeight() * s));
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            new Canvas(b).drawColor(Color.WHITE);
            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
            return b;
        }
    }

    private static void collectOutline(PDDocument doc, PDOutlineNode node, String indent,
                                       Map<Integer, List<String>> out, int depth) throws IOException {
        if (depth > 6) return;
        for (PDOutlineItem it = node.getFirstChild(); it != null; it = it.getNextSibling()) {
            PDPage p = null;
            try { p = it.findDestinationPage(doc); } catch (Exception ignored) {}
            if (p != null && it.getTitle() != null) {
                int idx = doc.getPages().indexOf(p);
                if (idx >= 0) {
                    List<String> l = out.get(idx);
                    if (l == null) { l = new ArrayList<>(); out.put(idx, l); }
                    l.add(indent + it.getTitle());
                }
            }
            collectOutline(doc, it, indent + "  ", out, depth + 1);
        }
    }

    private static String norm(String line) {
        return line.trim().replaceAll("\\d+", "#").replaceAll("\\s+", " ");
    }

    private static final java.util.regex.Pattern PAGE_LINE = java.util.regex.Pattern.compile(
            "(?i)^(page|pg\\.?|sheet|seite|p\\.)?\\s*#\\s*((of|/|von)\\s*#)?$|^-\\s*#\\s*-$|.*\\bpage\\s*#(\\s*(of|/)\\s*#)?\\b.*");

    /**
     * Header/footer lines: among the first/last 3 lines of a page, repeated on at least half the pages (min 3),
     * and either word-for-word identical (title blocks, company name) or a page-number line ("Page 3 of 90").
     * Numbered body text ("Requirement 12.3: …") differs from page to page and is never removed.
     */
    private static Set<String> findRepeated(String[] pages) {
        Map<String, Integer> exact = new HashMap<>(), normed = new HashMap<>();
        for (String p : pages) {
            Set<String> seenE = new HashSet<>(), seenN = new HashSet<>();
            for (String l : edgeLines(p)) {
                String e = l.trim().replaceAll("\\s+", " "), n = norm(l);
                if (e.length() >= 3 && seenE.add(e)) exact.put(e, exact.containsKey(e) ? exact.get(e) + 1 : 1);
                if (seenN.add(n)) normed.put(n, normed.containsKey(n) ? normed.get(n) + 1 : 1);
            }
        }
        Set<String> rep = new HashSet<>();
        int min = Math.max(3, pages.length / 2);
        for (Map.Entry<String, Integer> e : exact.entrySet()) if (e.getValue() >= min) rep.add("E:" + e.getKey());
        for (Map.Entry<String, Integer> e : normed.entrySet()) {
            if (e.getValue() >= min && e.getKey().length() <= 40 && PAGE_LINE.matcher(e.getKey()).matches()) rep.add("N:" + e.getKey());
        }
        return rep;
    }

    private static boolean isRepeated(String line, Set<String> rep) {
        return rep.contains("E:" + line.trim().replaceAll("\\s+", " ")) || rep.contains("N:" + norm(line));
    }

    private static List<String> edgeLines(String p) {
        List<String> lines = new ArrayList<>();
        for (String l : p.split("\n")) if (!l.trim().isEmpty()) lines.add(l);
        List<String> e = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) if (i < 3 || i >= lines.size() - 3) e.add(lines.get(i));
        return e;
    }

    private static String clean(String text, Set<String> repeated) {
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\n")) lines.add(l.replaceAll("\\s+$", ""));
        List<Integer> nonEmpty = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) if (!lines.get(i).trim().isEmpty()) nonEmpty.add(i);
        Set<Integer> drop = new HashSet<>();
        for (int k = 0; k < nonEmpty.size(); k++) {
            if (k < 3 || k >= nonEmpty.size() - 3) {
                int i = nonEmpty.get(k);
                if (isRepeated(lines.get(i), repeated)) drop.add(i);
            }
        }
        StringBuilder s = new StringBuilder();
        int blank = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (drop.contains(i)) continue;
            if (lines.get(i).trim().isEmpty()) { if (++blank > 1) continue; } else blank = 0;
            s.append(lines.get(i)).append('\n');
        }
        return s.toString().trim();
    }

    /** Counts drawing operations to tell drawings/tables/pictures apart from plain text pages. */
    static final class GraphicsCounter extends PDFGraphicsStreamEngine {
        int segments, bigImages;
        private final float pageArea;
        private final PointF cur = new PointF();

        GraphicsCounter(PDPage page) {
            super(page);
            PDRectangle r = page.getCropBox();
            pageArea = Math.max(1f, r.getWidth() * r.getHeight());
        }

        @Override public void drawImage(PDImage pdImage) {
            Matrix m = getGraphicsState().getCurrentTransformationMatrix();
            float area = Math.abs(m.getScalingFactorX() * m.getScalingFactorY());
            if (area / pageArea > 0.03f) bigImages++;   // ignore small logos/stamps repeated on every page
        }
        @Override public void appendRectangle(PointF p0, PointF p1, PointF p2, PointF p3) { segments += 4; }
        @Override public void clip(Path.FillType t) {}
        @Override public void moveTo(float x, float y) { cur.set(x, y); }
        @Override public void lineTo(float x, float y) { segments++; cur.set(x, y); }
        @Override public void curveTo(float a, float b, float c, float d, float x, float y) { segments++; cur.set(x, y); }
        @Override public PointF getCurrentPoint() { return cur; }
        @Override public void closePath() {}
        @Override public void endPath() {}
        @Override public void strokePath() {}
        @Override public void fillPath(Path.FillType t) {}
        @Override public void fillAndStrokePath(Path.FillType t) {}
        @Override public void shadingFill(COSName n) { segments += 10; }
        // Text is not a drawing; skip glyph processing to keep the scan fast.
        @Override protected void showText(byte[] string) {}
    }
}

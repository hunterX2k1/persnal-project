package com.fieldhelper.diag.diagram;

import android.graphics.Path;
import android.graphics.PointF;

import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls the exact lines/shapes and positioned words out of one PDF page (Android build, PdfBox-Android).
 * Everything is in PDF user space, so lines and words line up.
 */
public final class PdfExtractor {
    private PdfExtractor() {}

    public static PageGeom page(PDDocument doc, int pageIndex) throws IOException {
        PDPage p = doc.getPage(pageIndex);
        PageGeom g = new PageGeom();
        g.page = pageIndex + 1;
        PDRectangle box = p.getMediaBox();
        g.width = box.getWidth();
        g.height = box.getHeight();
        new Lines(p, g).processPage(p);
        Words w = new Words(g);
        w.setSortByPosition(true);
        w.setStartPage(pageIndex + 1);
        w.setEndPage(pageIndex + 1);
        w.writeText(doc, new StringWriter());
        return g;
    }

    /** Collects painted paths. */
    private static final class Lines extends PDFGraphicsStreamEngine {
        private final PageGeom g;
        private final List<PageGeom.Sub> pending = new ArrayList<>();
        private final List<Float> cur = new ArrayList<>();
        private boolean curClosed, curCurve;
        private float sx, sy, lx, ly;

        Lines(PDPage page, PageGeom g) { super(page); this.g = g; }

        private void flushSub() {
            if (cur.size() >= 4) {
                float[] a = new float[cur.size()];
                for (int i = 0; i < a.length; i++) a[i] = cur.get(i);
                pending.add(new PageGeom.Sub(a, curClosed, curCurve, false));
            }
            cur.clear();
            curClosed = false;
            curCurve = false;
        }

        private void commit(boolean filled) {
            flushSub();
            for (PageGeom.Sub s : pending) { s.filled = filled; g.subs.add(s); }
            pending.clear();
        }

        @Override public void appendRectangle(PointF p0, PointF p1, PointF p2, PointF p3) {
            flushSub();
            pending.add(new PageGeom.Sub(new float[]{p0.x, p0.y, p1.x, p1.y, p2.x, p2.y, p3.x, p3.y, p0.x, p0.y}, true, false, false));
        }
        @Override public void moveTo(float x, float y) { flushSub(); cur.add(x); cur.add(y); sx = x; sy = y; lx = x; ly = y; }
        @Override public void lineTo(float x, float y) {
            if (cur.isEmpty()) { cur.add(lx); cur.add(ly); }
            cur.add(x); cur.add(y); lx = x; ly = y;
        }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            if (cur.isEmpty()) { cur.add(lx); cur.add(ly); }
            cur.add(x1); cur.add(y1); cur.add(x2); cur.add(y2); cur.add(x3); cur.add(y3);
            curCurve = true; lx = x3; ly = y3;
        }
        @Override public PointF getCurrentPoint() { return new PointF(lx, ly); }
        @Override public void closePath() {
            if (!cur.isEmpty()) { cur.add(sx); cur.add(sy); curClosed = true; }
            lx = sx; ly = sy;
        }
        @Override public void endPath() { cur.clear(); pending.clear(); curClosed = false; curCurve = false; }
        @Override public void strokePath() { commit(false); }
        @Override public void fillPath(Path.FillType windingRule) { commit(true); }
        @Override public void fillAndStrokePath(Path.FillType windingRule) { commit(true); }
        @Override public void clip(Path.FillType windingRule) { }
        @Override public void drawImage(PDImage pdImage) { }
        @Override public void shadingFill(COSName shadingName) { }
    }

    /** Collects words with their position in user space. */
    private static final class Words extends PDFTextStripper {
        private final PageGeom g;
        Words(PageGeom g) throws IOException { super(); this.g = g; }

        @Override
        protected void writeString(String text, List<TextPosition> pos) {
            addWords(g, text, pos);
        }
    }

    static void addWords(PageGeom g, String text, List<TextPosition> pos) {
        if (pos == null || pos.isEmpty() || text == null) return;
        // split on spaces inside the chunk, keeping glyph positions
        int start = -1;
        for (int i = 0; i <= pos.size(); i++) {
            boolean space = i == pos.size() || pos.get(i).getUnicode() == null || pos.get(i).getUnicode().trim().isEmpty();
            if (!space && start < 0) start = i;
            if (space && start >= 0) { addWord(g, pos.subList(start, i)); start = -1; }
        }
    }

    private static void addWord(PageGeom g, List<TextPosition> ps) {
        StringBuilder sb = new StringBuilder();
        for (TextPosition t : ps) sb.append(t.getUnicode());
        String s = sb.toString().trim();
        if (s.isEmpty()) return;
        TextPosition a = ps.get(0), b = ps.get(ps.size() - 1);
        Matrix ma = a.getTextMatrix(), mb = b.getTextMatrix();
        float dx = ma.getValue(0, 0), dy = ma.getValue(0, 1);
        float len = (float) Math.hypot(dx, dy);
        if (len == 0) { dx = 1; dy = 0; } else { dx /= len; dy /= len; }
        float h = a.getFontSizeInPt() * 0.7f;
        if (h <= 0) h = Math.abs(a.getHeightDir());
        if (h <= 0) h = 5f;
        float x0 = ma.getTranslateX(), y0 = ma.getTranslateY();
        float x1 = mb.getTranslateX() + dx * b.getWidthDirAdj(), y1 = mb.getTranslateY() + dy * b.getWidthDirAdj();
        float cx = (x0 + x1) / 2 - dy * h / 2, cy = (y0 + y1) / 2 + dx * h / 2;
        g.words.add(new PageGeom.Word(s, cx, cy, h, (float) Math.hypot(x1 - x0, y1 - y0)));
    }
}

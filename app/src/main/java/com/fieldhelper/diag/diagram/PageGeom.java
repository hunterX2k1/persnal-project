package com.fieldhelper.diag.diagram;

import java.util.ArrayList;
import java.util.List;

/**
 * Raw drawing content of one PDF page: the painted lines/shapes and the words with their positions.
 * Filled by a PDF extractor; read by {@link DiagramReader}. Coordinates are PDF points (any orientation).
 */
public final class PageGeom {
    public int page;          // 1-based page number
    public float width, height;
    /** Pictures painted on the page (a scanned page is one big picture). */
    public int images;
    public final List<Sub> subs = new ArrayList<>();
    public final List<Word> words = new ArrayList<>();

    /** One painted sub-path: points x0,y0,x1,y1,... */
    public static final class Sub {
        public float[] pts;
        public boolean closed;   // closed shape (rectangle, polygon, circle)
        public boolean curve;    // contains curves (circles, arcs)
        public boolean filled;   // painted with fill (junction dots, arrow heads)
        public Sub(float[] pts, boolean closed, boolean curve, boolean filled) {
            this.pts = pts; this.closed = closed; this.curve = curve; this.filled = filled;
        }
    }

    /** One word of text: its center point and letter height. */
    public static final class Word {
        public final String text;
        public final float x, y, h;
        /** Word length along its baseline (0 if unknown). */
        public float w;
        public Word(String text, float x, float y, float h) { this.text = text; this.x = x; this.y = y; this.h = h; }
        public Word(String text, float x, float y, float h, float w) { this(text, x, y, h); this.w = w; }
    }
}

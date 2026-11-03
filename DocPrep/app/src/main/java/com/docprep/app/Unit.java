package com.docprep.app;

import java.util.ArrayList;
import java.util.List;

/** One piece of the document in reading order: a PDF page, a Word section, or a block of spreadsheet rows. */
final class Unit {
    static final class Img {
        final String name; final byte[] jpeg; final int w, h;
        Img(String name, byte[] jpeg, int w, int h) { this.name = name; this.jpeg = jpeg; this.w = w; this.h = h; }
        /** Rough Claude token cost of an image: width*height/750. */
        long tokens() { return (long) w * h / 750; }
    }

    final String label;        // e.g. "Page 12", "Section 4", "Sheet 'IO List' rows 1-300"
    final String md;           // markdown text of this unit (already contains figure markers)
    final List<Img> images = new ArrayList<>();
    String heading;            // section/bookmark title this unit belongs to (for context carry-over)

    Unit(String label, String md) { this.label = label; this.md = md; }

    long bytes() {
        long b = md.length() * 2L;   // utf-8 upper-ish bound
        for (Img i : images) b += i.jpeg.length;
        return b;
    }

    long tokens() {
        long t = md.length() / 4;
        for (Img i : images) t += i.tokens();
        return t;
    }
}

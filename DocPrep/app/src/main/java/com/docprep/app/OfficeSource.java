package com.docprep.app;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Word (.docx) and Excel (.xlsx) → units, read directly from their XML. */
final class OfficeSource {
    private OfficeSource() {}

    // ------------------------------------------------------------------ Word
    static void docx(File f, Options opt, PartWriter w, Indexer idx, PdfSource.Progress pr) throws Exception {
        try (ZipFile z = new ZipFile(f)) {
            Map<String, String> rels = rels(z, "word/_rels/document.xml.rels", "word/");
            XmlPullParser x = parser(z, "word/document.xml");
            Section sec = new Section(1, null);
            StringBuilder para = new StringBuilder();
            String style = null;
            List<List<String>> table = null;
            List<String> row = null;
            StringBuilder cell = null;
            int tableDepth = 0, figNo = 0, sectionNo = 1, paraCount = 0;
            String pendingAlt = null;
            boolean inText = false;

            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                if (pr.cancelled()) return;
                String name = x.getName();
                if (ev == XmlPullParser.START_TAG) {
                    switch (name) {
                        case "w:p": para.setLength(0); style = null; break;
                        case "w:pStyle": style = x.getAttributeValue(null, "w:val"); break;
                        case "w:t": inText = true; break;
                        case "w:tab": para.append('\t'); break;
                        case "w:br": case "w:cr": para.append('\n'); break;
                        case "w:tbl": if (tableDepth++ == 0) table = new ArrayList<>(); break;
                        case "w:tr": if (tableDepth == 1) row = new ArrayList<>(); break;
                        case "w:tc": if (tableDepth == 1) cell = new StringBuilder(); break;
                        case "wp:docPr": {
                            String d = x.getAttributeValue(null, "descr");
                            if (d == null || d.isEmpty()) d = x.getAttributeValue(null, "title");
                            pendingAlt = d;
                            break;
                        }
                        case "a:blip": case "v:imagedata": {
                            String id = x.getAttributeValue(null, "r:embed");
                            if (id == null) id = x.getAttributeValue(null, "r:id");
                            String target = id == null ? null : rels.get(id);
                            if (target != null && opt.imageMode != Options.IMG_NONE) {
                                ZipEntry e = z.getEntry(target);
                                Unit.Img img = e == null ? null : Images.fromBytes(
                                        idx.img(String.format(java.util.Locale.US, "fig%04d.jpg", figNo + 1)), read(z, e));
                                figNo++;
                                String marker;
                                if (img != null) {
                                    sec.images.add(img);
                                    marker = "\n**[FIGURE " + idx.fig(String.valueOf(figNo)) + "]** `images/" + img.name + "`"
                                            + (pendingAlt != null && !pendingAlt.isEmpty() ? " – alt text: " + pendingAlt : "")
                                            + " _(the picture sits exactly here in the document; the surrounding text describes it)_\n";
                                } else {
                                    marker = "\n**[FIGURE " + idx.fig(String.valueOf(figNo)) + "]** _(image format not supported on the phone: " + target + ")_\n";
                                }
                                if (cell != null) cell.append(marker.trim()).append(' '); else para.append(marker);
                            } else if (target != null) {
                                figNo++;
                                para.append("\n**[FIGURE ").append(figNo).append("]** _(omitted – text-only mode)_\n");
                            }
                            pendingAlt = null;
                            break;
                        }
                    }
                } else if (ev == XmlPullParser.TEXT && inText) {
                    para.append(x.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    switch (name) {
                        case "w:t": inText = false; break;
                        case "w:p": {
                            String text = para.toString();
                            if (cell != null) { if (cell.length() > 0 && text.length() > 0) cell.append("<br>"); cell.append(text.trim()); break; }
                            int level = headingLevel(style);
                            if ((level > 0 && level <= 2 && sec.body.length() > 0) || sec.body.length() > 12000) {
                                emit(sec, w, idx);
                                sec = new Section(++sectionNo, level > 0 ? text.trim() : sec.heading);
                            }
                            if (level > 0) {
                                if (level <= 2 || sec.heading == null) sec.heading = text.trim();
                                sec.body.append(repeat('#', Math.min(6, level + 1))).append(' ').append(text.trim()).append("\n\n");
                                sec.headings.add(text.trim());
                            } else if (!text.trim().isEmpty()) {
                                if (style != null && style.toLowerCase().contains("list")) sec.body.append("- ");
                                sec.body.append(text.trim()).append("\n\n");
                            }
                            if (++paraCount % 200 == 0) pr.update("Reading Word document… " + paraCount + " paragraphs", 0, 0);
                            break;
                        }
                        case "w:tc": if (tableDepth == 1 && cell != null) { row.add(cell.toString().replace("|", "\\|")); cell = null; } break;
                        case "w:tr": if (tableDepth == 1 && row != null) { table.add(row); row = null; } break;
                        case "w:tbl":
                            if (--tableDepth == 0 && table != null) { sec.body.append(markdownTable(table)).append('\n'); table = null; }
                            break;
                    }
                }
            }
            emit(sec, w, idx);
            idx.content(sectionNo + " sections, " + figNo + " figures");
            idx.note("Word document: " + sectionNo + " sections, " + figNo + " figures. Page numbers don't exist in .docx files, so sections are used instead.");
        }
    }

    private static final class Section {
        final int no; String heading; final StringBuilder body = new StringBuilder();
        final List<Unit.Img> images = new ArrayList<>(); final List<String> headings = new ArrayList<>();
        Section(int no, String heading) { this.no = no; this.heading = heading; }
    }

    private static void emit(Section s, PartWriter w, Indexer idx) throws Exception {
        if (s.body.length() == 0 && s.images.isEmpty()) return;
        String label = idx.label("Section " + s.no);
        String body = s.body.toString();
        // The body already starts with its own heading; only label continuation blocks explicitly.
        String md = "<!-- section " + s.no + " -->\n" + (body.startsWith("#") && !idx.multi ? "" : "## " + label
                + (s.heading != null ? (body.startsWith("#") ? "" : " (continued: " + s.heading + ")") : "") + "\n\n") + body;
        Unit u = new Unit(label, md);
        u.images.addAll(s.images);
        u.heading = s.heading;
        int part = w.add(u);
        Indexer.Loc where = idx.section(s.no, part);
        idx.scan(body, where);
        for (String h : s.headings) idx.addToc(h, where);
    }

    private static int headingLevel(String style) {
        if (style == null) return 0;
        String s = style.toLowerCase();
        if (s.equals("title")) return 1;
        if (s.startsWith("heading")) {
            try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (Exception e) { return 1; }
        }
        return 0;
    }

    private static String markdownTable(List<List<String>> t) {
        int cols = 0;
        for (List<String> r : t) cols = Math.max(cols, r.size());
        if (cols == 0) return "";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < t.size(); i++) {
            s.append('|');
            for (int c = 0; c < cols; c++) s.append(' ').append(c < t.get(i).size() ? t.get(i).get(c) : "").append(" |");
            s.append('\n');
            if (i == 0) { s.append('|'); for (int c = 0; c < cols; c++) s.append("---|"); s.append('\n'); }
        }
        return s.toString();
    }

    // ------------------------------------------------------------------ Excel
    static void xlsx(File f, Options opt, PartWriter w, Indexer idx, PdfSource.Progress pr) throws Exception {
        final int ROWS_PER_UNIT = 300;
        try (ZipFile z = new ZipFile(f)) {
            List<String> shared = new ArrayList<>();
            if (z.getEntry("xl/sharedStrings.xml") != null) {
                XmlPullParser x = parser(z, "xl/sharedStrings.xml");
                StringBuilder si = null; boolean inT = false;
                for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                    if (ev == XmlPullParser.START_TAG) {
                        if ("si".equals(x.getName())) si = new StringBuilder();
                        else if ("t".equals(x.getName())) inT = true;
                        else if ("rPh".equals(x.getName())) inT = false;
                    } else if (ev == XmlPullParser.TEXT && inT && si != null) si.append(x.getText());
                    else if (ev == XmlPullParser.END_TAG) {
                        if ("t".equals(x.getName())) inT = false;
                        else if ("si".equals(x.getName())) { shared.add(si.toString()); si = null; }
                    }
                }
            }
            Map<String, String> rels = rels(z, "xl/_rels/workbook.xml.rels", "xl/");
            List<String[]> sheets = new ArrayList<>();   // {name, path}
            XmlPullParser wb = parser(z, "xl/workbook.xml");
            for (int ev = wb.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = wb.next()) {
                if (ev == XmlPullParser.START_TAG && "sheet".equals(wb.getName())) {
                    String rid = wb.getAttributeValue(null, "r:id");
                    if (rid != null && rels.containsKey(rid)) sheets.add(new String[]{wb.getAttributeValue(null, "name"), rels.get(rid)});
                }
            }
            int unitNo = 0;
            for (String[] sh : sheets) {
                if (pr.cancelled()) return;
                pr.update("Reading sheet '" + sh[0] + "'", 0, 0);
                XmlPullParser x = parser(z, sh[1]);
                TreeMap<Integer, TreeMap<Integer, String>> buffer = new TreeMap<>();
                String header = null; int headerRow = -1, maxCol = 0;
                int rowNo = 0, col = 0; String type = null; StringBuilder val = null; boolean inV = false;
                int chunkStart = -1, chunkRows = 0;
                boolean emitted = false;
                for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                    if (ev == XmlPullParser.START_TAG) {
                        switch (x.getName()) {
                            case "row": {
                                String r = x.getAttributeValue(null, "r");
                                rowNo = r != null ? Integer.parseInt(r) : rowNo + 1;
                                col = 0;
                                break;
                            }
                            case "c": {
                                String r = x.getAttributeValue(null, "r");
                                col = r != null ? colIndex(r) : col + 1;
                                type = x.getAttributeValue(null, "t");
                                val = new StringBuilder();
                                break;
                            }
                            case "v": case "t": inV = true; break;
                        }
                    } else if (ev == XmlPullParser.TEXT && inV && val != null) {
                        val.append(x.getText());
                    } else if (ev == XmlPullParser.END_TAG) {
                        switch (x.getName()) {
                            case "v": case "t": inV = false; break;
                            case "c": {
                                String v = val == null ? "" : val.toString();
                                if ("s".equals(type)) { try { v = shared.get(Integer.parseInt(v.trim())); } catch (Exception ignored) {} }
                                else if ("b".equals(type)) v = "1".equals(v) ? "TRUE" : "FALSE";
                                if (!v.isEmpty()) {
                                    TreeMap<Integer, String> row = buffer.get(rowNo);
                                    if (row == null) { row = new TreeMap<>(); buffer.put(rowNo, row); }
                                    row.put(col, v);
                                    maxCol = Math.max(maxCol, col);
                                }
                                val = null;
                                break;
                            }
                            case "row": {
                                if (buffer.containsKey(rowNo)) {
                                    if (header == null) { header = csvRow(rowNo, buffer.get(rowNo), maxCol); headerRow = rowNo; buffer.remove(rowNo); }
                                    else {
                                        if (chunkStart < 0) chunkStart = rowNo;
                                        if (++chunkRows >= ROWS_PER_UNIT) {
                                            unitNo++;
                                            emitRows(sh[0], header, headerRow, buffer, maxCol, w, idx, unitNo);
                                            buffer.clear(); chunkStart = -1; chunkRows = 0; emitted = true;
                                        }
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
                // Remaining rows; or a sheet that only has a header row.
                if (!buffer.isEmpty() || (header != null && !emitted)) { unitNo++; emitRows(sh[0], header, headerRow, buffer, maxCol, w, idx, unitNo); }
            }
            idx.content(sheets.size() + " sheet(s), " + unitNo + " row blocks");
            idx.note("Excel workbook: " + sheets.size() + " sheet(s). Each block repeats the sheet's header row so columns stay understandable; "
                    + "values are shown as stored (dates may appear as numbers, formulas as their last calculated result).");
        }
    }

    private static void emitRows(String sheet, String header, int headerRow, TreeMap<Integer, TreeMap<Integer, String>> rows,
                                 int maxCol, PartWriter w, Indexer idx, int unitNo) throws Exception {
        int first = rows.isEmpty() ? headerRow : rows.firstKey(), last = rows.isEmpty() ? headerRow : rows.lastKey();
        String label = idx.label("Sheet '" + sheet + "' rows " + first + "-" + last);
        StringBuilder body = new StringBuilder();
        body.append("<!-- block ").append(unitNo).append(" -->\n## ").append(label).append("\n\n");
        body.append("Header (row ").append(headerRow).append(") repeated for context. First column = original row number.\n\n```csv\n");
        if (header != null) body.append(header).append('\n');
        for (Map.Entry<Integer, TreeMap<Integer, String>> r : rows.entrySet()) body.append(csvRow(r.getKey(), r.getValue(), maxCol)).append('\n');
        body.append("```\n");
        Unit u = new Unit(label, body.toString());
        u.heading = "Sheet '" + sheet + "'";
        int part = w.add(u);
        Indexer.Loc where = idx.block(unitNo, "'" + sheet + "' rows " + first + "–" + last, part);
        idx.scan(body.toString(), where);
        idx.addToc("Sheet '" + sheet + "' rows " + first + "-" + last, where);
    }

    private static String csvRow(int rowNo, TreeMap<Integer, String> cells, int maxCol) {
        StringBuilder s = new StringBuilder().append(rowNo);
        for (int c = 1; c <= maxCol; c++) {
            String v = cells.get(c);
            s.append(',');
            if (v == null) continue;
            if (v.contains(",") || v.contains("\"") || v.contains("\n")) s.append('"').append(v.replace("\"", "\"\"")).append('"');
            else s.append(v);
        }
        return s.toString();
    }

    private static int colIndex(String ref) {
        int c = 0;
        for (char ch : ref.toCharArray()) { if (ch >= 'A' && ch <= 'Z') c = c * 26 + (ch - 'A' + 1); else break; }
        return c;
    }

    // ------------------------------------------------------------------ helpers
    private static Map<String, String> rels(ZipFile z, String path, String base) throws Exception {
        Map<String, String> m = new HashMap<>();
        if (z.getEntry(path) == null) return m;
        XmlPullParser x = parser(z, path);
        for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
            if (ev == XmlPullParser.START_TAG && "Relationship".equals(x.getName())) {
                String t = x.getAttributeValue(null, "Target");
                if (t == null || "External".equals(x.getAttributeValue(null, "TargetMode"))) continue;
                String full = t.startsWith("/") ? t.substring(1) : normalize(base + t);
                m.put(x.getAttributeValue(null, "Id"), full);
            }
        }
        return m;
    }

    private static String normalize(String p) {
        List<String> out = new ArrayList<>();
        for (String s : p.split("/")) { if (s.equals("..")) { if (!out.isEmpty()) out.remove(out.size() - 1); } else if (!s.equals(".") && !s.isEmpty()) out.add(s); }
        return android.text.TextUtils.join("/", out);
    }

    private static XmlPullParser parser(ZipFile z, String path) throws Exception {
        ZipEntry e = z.getEntry(path);
        if (e == null) throw new IllegalArgumentException("Not a valid Office file (missing " + path + ")");
        XmlPullParser x = Xml.newPullParser();
        x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);   // keep "w:" prefixes in names
        x.setInput(z.getInputStream(e), "UTF-8");
        return x;
    }

    static byte[] read(ZipFile z, ZipEntry e) throws Exception {
        try (InputStream in = z.getInputStream(e)) { return readAll(in); }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        for (int k; (k = in.read(b)) > 0; ) o.write(b, 0, k);
        return o.toByteArray();
    }

    private static String repeat(char c, int n) { char[] a = new char[n]; java.util.Arrays.fill(a, c); return new String(a); }

    // ------------------------------------------------------------------ plain text & single images
    static void text(File f, PartWriter w, Indexer idx, PdfSource.Progress pr) throws Exception {
        String all = new String(readAll(new java.io.FileInputStream(f)), StandardCharsets.UTF_8);
        String[] lines = all.split("\n", -1);
        int per = 1500;
        idx.content(lines.length + " lines");
        for (int i = 0, k = 1; i < lines.length; i += per, k++) {
            int end = Math.min(lines.length, i + per);
            StringBuilder b = new StringBuilder("## " + idx.label("Lines " + (i + 1) + "-" + end) + "\n\n");
            for (int j = i; j < end; j++) b.append(lines[j]).append('\n');
            Unit u = new Unit(idx.label("Lines " + (i + 1) + "-" + end), b.toString());
            int part = w.add(u);
            idx.scan(b.toString(), idx.block(k, "lines " + (i + 1) + "–" + end, part));
        }
    }

    static void image(File f, String name, PartWriter w, Indexer idx) throws Exception {
        Unit.Img img = Images.fromBytes(idx.img("image0001.jpg"), readAll(new java.io.FileInputStream(f)));
        if (img == null) throw new IllegalArgumentException("This image format can't be read on the phone.");
        Unit u = new Unit(idx.label("Image"), "## " + idx.label("Image") + "\n\n**[FIGURE " + idx.fig("1") + "]** `images/"
                + img.name + "` – original file: " + name + "\n");
        u.images.add(img);
        idx.content("1 image");
        w.add(u);
    }
}

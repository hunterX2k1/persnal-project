package com.docprep.app;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Packs units into parts that stay under the size and token limits, then zips each part with the index. */
final class PartWriter {
    static final class Part {
        final int no; final File dir; String first, last; long bytes, tokens, zipBytes; String zipName;
        String carryHeading, carryTail;   // context from the end of the previous part
        int unitCount;
        Part(int no, File dir) { this.no = no; this.dir = dir; }
    }

    private final File work;
    private final long maxBytes, maxTokens;
    final List<Part> parts = new ArrayList<>();
    private Part cur;
    private BufferedWriter body;
    private String lastHeading, lastTail;
    final List<String> warnings = new ArrayList<>();

    /** Share of each part's token limit kept free for INDEX.md (which is repeated in every part). */
    static final double INDEX_SHARE = 0.12;
    final long indexBudget;

    PartWriter(File work, long maxBytes, long maxTokens) {
        this.work = work;
        this.indexBudget = (long) (maxTokens * INDEX_SHARE);
        this.maxTokens = maxTokens - indexBudget;
        this.maxBytes = (long) (maxBytes * 0.95);   // zip headers + index text
    }

    /** Adds a unit, starting a new part when a limit would be exceeded. Returns the part number. */
    int add(Unit u) throws IOException {
        long b = u.bytes(), t = u.tokens();
        if (cur == null || (cur.unitCount > 0 && (cur.bytes + b > maxBytes || cur.tokens + t > maxTokens))) newPart();
        if (b > maxBytes || t > maxTokens) warnings.add(u.label + " alone is larger than the part limit; it was kept whole in part " + cur.no + ".");
        for (Unit.Img i : u.images) {
            File f = new File(cur.dir, "images/" + i.name);
            f.getParentFile().mkdirs();
            try (OutputStream o = new FileOutputStream(f)) { o.write(i.jpeg); }
        }
        body.write(u.md);
        body.write("\n\n");
        if (cur.first == null) cur.first = u.label;
        cur.last = u.label;
        cur.bytes += b; cur.tokens += t; cur.unitCount++;
        if (u.heading != null) lastHeading = u.heading;
        String plain = u.md.trim();
        if (plain.length() > 260) {
            plain = plain.substring(plain.length() - 260);
            int sp = plain.indexOf(' ');
            if (sp > 0 && sp < 40) plain = plain.substring(sp + 1);
        }
        lastTail = plain;
        return cur.no;
    }

    private void newPart() throws IOException {
        if (body != null) body.close();
        Part p = new Part(parts.size() + 1, new File(work, "part" + (parts.size() + 1)));
        p.dir.mkdirs();
        p.carryHeading = lastHeading;
        p.carryTail = lastTail;
        parts.add(p);
        cur = p;
        body = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(p.dir, "body.md")), StandardCharsets.UTF_8));
    }

    /** Writes one ZIP per part (INDEX.md + document.md + images + manifest.json). */
    List<File> finish(File outDir, String baseName, String title, Indexer idx, String sourceInfo, String settings,
                      String sourceSha) throws Exception {
        if (body != null) body.close();
        int n = parts.size();
        for (Part p : parts) p.zipName = String.format(java.util.Locale.US, "%s_part%02dof%02d.zip", baseName, p.no, n);
        // First pass gives sizes; index is rendered with final sizes after zipping once (sizes barely change).
        List<File> out = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            out.clear();
            String index = idx.render(title, sourceInfo, parts, settings, indexBudget);
            for (Part p : parts) {
                File z = new File(outDir, p.zipName);
                zipPart(p, z, index, title, n, sourceSha);
                p.zipBytes = z.length();
                out.add(z);
            }
        }
        return out;
    }

    private void zipPart(Part p, File zipFile, String index, String title, int n, String sourceSha) throws Exception {
        StringBuilder manifest = new StringBuilder("{\n  \"document\": ").append(q(title))
                .append(",\n  \"source_sha256\": ").append(q(sourceSha))
                .append(",\n  \"part\": ").append(p.no).append(", \"parts\": ").append(n)
                .append(",\n  \"covers\": ").append(q(p.first + " → " + p.last))
                .append(",\n  \"files\": {\n");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zipFile))) {
            List<String[]> hashes = new ArrayList<>();
            hashes.add(new String[]{"INDEX.md", put(z, "INDEX.md", index.getBytes(StandardCharsets.UTF_8))});

            StringBuilder head = new StringBuilder();
            head.append("# ").append(title).append(" — Part ").append(p.no).append(" of ").append(n).append("\n\n");
            head.append("Covers **").append(p.first).append("** to **").append(p.last).append("**. See INDEX.md for the whole document map.\n\n");
            if (p.no > 1) {
                head.append("> **Continues from part ").append(p.no - 1).append(".**");
                if (p.carryHeading != null) head.append(" Current section: _").append(p.carryHeading.replace("\n", " ")).append("_.");
                if (p.carryTail != null && !p.carryTail.isEmpty()) {
                    head.append("\n> End of previous part: “…").append(p.carryTail.replace("\n", " ").replaceAll("\\s+", " ")).append("”");
                }
                head.append("\n\n");
            }
            hashes.add(new String[]{"document.md", putDoc(z, head.toString(), new File(p.dir, "body.md"))});

            File[] imgs = new File(p.dir, "images").listFiles();
            if (imgs != null) {
                java.util.Arrays.sort(imgs);
                for (File f : imgs) hashes.add(new String[]{"images/" + f.getName(), putFile(z, "images/" + f.getName(), f)});
            }
            for (int i = 0; i < hashes.size(); i++) {
                manifest.append("    ").append(q(hashes.get(i)[0])).append(": ").append(q(hashes.get(i)[1]))
                        .append(i < hashes.size() - 1 ? ",\n" : "\n");
            }
            manifest.append("  }\n}\n");
            put(z, "manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String put(ZipOutputStream z, String name, byte[] data) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String putDoc(ZipOutputStream z, String head, File body) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        z.putNextEntry(new ZipEntry("document.md"));
        byte[] h = head.getBytes(StandardCharsets.UTF_8);
        z.write(h); md.update(h);
        copy(body, z, md);
        z.closeEntry();
        return hex(md.digest());
    }

    private static String putFile(ZipOutputStream z, String name, File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        z.putNextEntry(new ZipEntry(name));
        copy(f, z, md);
        z.closeEntry();
        return hex(md.digest());
    }

    private static void copy(File f, OutputStream o, MessageDigest md) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            for (int k; (k = in.read(buf)) > 0; ) { o.write(buf, 0, k); md.update(buf, 0, k); }
        }
    }

    static String hex(byte[] d) {
        StringBuilder s = new StringBuilder();
        for (byte b : d) s.append(String.format("%02x", b));
        return s.toString();
    }

    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}

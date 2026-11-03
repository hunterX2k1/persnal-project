package com.docprep.app;

import android.content.Context;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Runs one or more documents through the right readers and packs them into ZIP parts.
 * Small documents share a part; a document bigger than the limit continues across parts.
 * Everything happens on the phone.
 */
final class Processor {
    static final class Input {
        final File file; final String name;
        Input(File file, String name) { this.file = file; this.name = name; }
    }

    static final class Result { List<File> zips; List<String> warnings = new ArrayList<>(); String summary; }

    private Processor() {}

    static Result run(Context ctx, List<Input> inputs, Options opt, File outDir, PdfSource.Progress pr) throws Exception {
        PDFBoxResourceLoader.init(ctx.getApplicationContext());
        File work = new File(ctx.getCacheDir(), "work");
        deleteTree(work);
        work.mkdirs();
        deleteTree(outDir);
        outDir.mkdirs();

        PartWriter w = new PartWriter(work, opt.maxBytes, opt.maxTokens);
        Indexer idx = new Indexer();
        idx.multi = inputs.size() > 1;
        Result r = new Result();
        long totalIn = 0;
        int ok = 0;

        for (int k = 0; k < inputs.size(); k++) {
            if (pr.cancelled()) throw new InterruptedException("Cancelled");
            Input in = inputs.get(k);
            final String tag = idx.multi ? "Doc " + (k + 1) + "/" + inputs.size() + " · " : "";
            PdfSource.Progress p = new PdfSource.Progress() {
                @Override public void update(String m, int done, int total) { pr.update(tag + m, done, total); }
                @Override public boolean cancelled() { return pr.cancelled(); }
            };
            p.update("Fingerprinting " + in.name + "…", 0, 0);
            Indexer.Doc d = new Indexer.Doc(k + 1, in.name, sha256(in.file), in.file.length());
            idx.docs.add(d);
            idx.doc = d;
            totalIn += d.bytes;
            w.markDocStart();
            if (idx.multi) {
                w.startDocument("\n# Document D" + d.no + " of " + inputs.size() + ": " + in.name + "\n\n", "Document D" + d.no + " – " + in.name);
            }
            try {
                process(in, work, opt, w, idx, p, d);
                ok++;
            } catch (InterruptedException e) {
                throw e;
            } catch (Throwable e) {
                String msg = in.name + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                if (!idx.multi) throw e instanceof Exception ? (Exception) e : new RuntimeException(msg, e);
                r.warnings.add("Skipped " + msg);
                idx.note("could not be processed – " + msg);
                d.type = "skipped";
                d.content = "not processed (see notes)";
            }
            if (w.docFirstPart > 0) { d.firstPart = w.docFirstPart; d.lastPart = w.partCount(); }
        }
        if (w.parts.isEmpty()) throw new IllegalStateException("Nothing readable was found in the selected file(s).");
        r.warnings.addAll(w.warnings);
        idx.doc = null;
        idx.notes.addAll(w.warnings);

        String title, base, info;
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        if (!idx.multi) {
            Indexer.Doc d = idx.docs.get(0);
            title = d.name.replaceAll("\\.[A-Za-z0-9]{1,5}$", "");
            base = title.replaceAll("[^A-Za-z0-9._-]+", "_");
            info = "Source: **" + d.name + "** · " + String.format(Locale.US, "%.1f MB", d.bytes / 1048576.0)
                    + " · SHA-256 `" + d.sha + "`\nProcessed on the phone by DocPrep on " + date + ".";
        } else {
            StringBuilder names = new StringBuilder();
            for (Indexer.Doc d : idx.docs) { if (names.length() > 0) names.append(", "); names.append(d.name); }
            title = idx.docs.size() + " documents";
            base = "docs" + idx.docs.size() + "_" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
            info = "Sources: **" + idx.docs.size() + " documents** (" + names + "), "
                    + String.format(Locale.US, "%.1f MB", totalIn / 1048576.0) + " in total.\nProcessed on the phone by DocPrep on " + date + ".";
        }
        if (base.length() > 40) base = base.substring(0, 40);

        StringBuilder src = new StringBuilder("[");
        for (int i = 0; i < idx.docs.size(); i++) {
            Indexer.Doc d = idx.docs.get(i);
            src.append(i == 0 ? "\n    " : ",\n    ").append("{\"doc\": \"D").append(d.no).append("\", \"name\": ")
               .append(PartWriter.q(d.name)).append(", \"sha256\": ").append(PartWriter.q(d.sha)).append("}");
        }
        src.append("\n  ]");

        pr.update("Writing ZIP files…", 0, 0);
        r.zips = w.finish(outDir, base, title, idx, info, opt.describe(), src.toString());
        long total = 0;
        for (File z : r.zips) total += z.length();
        r.summary = String.format(Locale.US, "%s → %d part(s), %.1f MB total (originals %.1f MB).",
                idx.multi ? ok + " of " + inputs.size() + " documents" : inputs.get(0).name,
                r.zips.size(), total / 1048576.0, totalIn / 1048576.0);
        deleteTree(work);
        return r;
    }

    private static void process(Input in, File work, Options opt, PartWriter w, Indexer idx, PdfSource.Progress pr,
                                Indexer.Doc d) throws Exception {
        String lower = in.name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            d.type = "PDF";
            PdfSource.process(in.file, new File(work, "pdfbox-tmp"), opt, w, idx, pr);
        } else if (lower.endsWith(".docx") || lower.endsWith(".docm")) {
            d.type = "Word";
            OfficeSource.docx(in.file, opt, w, idx, pr);
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) {
            d.type = "Excel";
            OfficeSource.xlsx(in.file, opt, w, idx, pr);
        } else if (lower.matches(".*\\.(jpe?g|png|webp|bmp|gif)$")) {
            d.type = "Image";
            OfficeSource.image(in.file, in.name, w, idx);
        } else if (lower.matches(".*\\.(txt|csv|md|log|xml|json|st|scl|l5x|ini|cfg)$")) {
            d.type = "Text";
            OfficeSource.text(in.file, w, idx, pr);
        } else if (lower.endsWith(".doc") || lower.endsWith(".xls") || lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            throw new IllegalArgumentException("old .doc/.xls and PowerPoint files aren't supported – save as PDF, .docx or .xlsx first");
        } else {
            throw new IllegalArgumentException("unsupported file type – use PDF, Word (.docx), Excel (.xlsx), images or text files");
        }
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[1 << 16];
            for (int k; (k = in.read(b)) > 0; ) md.update(b, 0, k);
        }
        return PartWriter.hex(md.digest());
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}

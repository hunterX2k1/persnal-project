package com.docprep.app;

import android.content.Context;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Runs one document through the right reader and produces the ZIP parts. Everything happens on the phone. */
final class Processor {
    static final class Result { List<File> zips; List<String> warnings; String summary; }

    private Processor() {}

    static Result run(Context ctx, File input, String displayName, Options opt, File outDir, PdfSource.Progress pr) throws Exception {
        PDFBoxResourceLoader.init(ctx.getApplicationContext());
        File work = new File(ctx.getCacheDir(), "work");
        deleteTree(work);
        work.mkdirs();
        deleteTree(outDir);
        outDir.mkdirs();

        pr.update("Fingerprinting the original…", 0, 0);
        String sha = sha256(input);
        String lower = displayName.toLowerCase(Locale.ROOT);
        String title = displayName.replaceAll("\\.[A-Za-z0-9]{1,5}$", "");
        String base = title.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (base.length() > 40) base = base.substring(0, 40);

        PartWriter w = new PartWriter(work, opt.maxBytes, opt.maxTokens);
        Indexer idx = new Indexer();
        if (lower.endsWith(".pdf")) PdfSource.process(input, new File(work, "pdfbox-tmp"), opt, w, idx, pr);
        else if (lower.endsWith(".docx") || lower.endsWith(".docm")) OfficeSource.docx(input, opt, w, idx, pr);
        else if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) OfficeSource.xlsx(input, opt, w, idx, pr);
        else if (lower.matches(".*\\.(jpe?g|png|webp|bmp|gif)$")) OfficeSource.image(input, displayName, w, idx);
        else if (lower.matches(".*\\.(txt|csv|md|log|xml|json|st|scl|l5x|ini|cfg)$")) OfficeSource.text(input, w, idx, pr);
        else if (lower.endsWith(".doc") || lower.endsWith(".xls") || lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            throw new IllegalArgumentException("Old .doc/.xls and PowerPoint files aren't supported. Save as PDF, .docx or .xlsx first.");
        } else {
            throw new IllegalArgumentException("Unsupported file type. Use PDF, Word (.docx), Excel (.xlsx), images or text files.");
        }
        if (pr.cancelled()) throw new InterruptedException("Cancelled");
        if (w.parts.isEmpty()) throw new IllegalStateException("Nothing readable was found in this file.");
        idx.notes.addAll(w.warnings);

        String info = "Source: **" + displayName + "** · " + String.format(Locale.US, "%.1f MB", input.length() / 1048576.0)
                + " · SHA-256 `" + sha + "`\nProcessed on the phone by DocPrep on "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()) + ".";
        pr.update("Writing ZIP files…", 0, 0);
        Result r = new Result();
        r.zips = w.finish(outDir, base, title, idx, info, opt.describe(), sha);
        r.warnings = w.warnings;
        long total = 0;
        for (File z : r.zips) total += z.length();
        r.summary = String.format(Locale.US, "%s → %d part(s), %.1f MB total (original %.1f MB).",
                displayName, r.zips.size(), total / 1048576.0, input.length() / 1048576.0);
        deleteTree(work);
        return r;
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

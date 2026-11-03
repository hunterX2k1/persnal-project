package com.docprep.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK = 1, SAVE_TREE = 2;
    private static final long[] SIZES_MB = {5, 10, 20, 30, 50};
    private static final long[] TOKENS = {50_000, 100_000, 150_000, 300_000, 600_000};

    private Spinner size, tokens, images;
    private Button pick, cancel, shareAll, saveAll;
    private ProgressBar bar;
    private TextView status, log;
    private LinearLayout results;
    private SharedPreferences prefs;
    private volatile boolean cancelled;
    private Thread worker;
    private final List<File> zips = new ArrayList<>();

    static File outDir(Context c) { return new File(c.getFilesDir(), "out"); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = getPreferences(MODE_PRIVATE);
        size = spinner(R.id.size, new String[]{"5 MB", "10 MB", "20 MB", "30 MB", "50 MB"}, prefs.getInt("size", 1));
        tokens = spinner(R.id.tokens, new String[]{"~50k tokens (small chats)", "~100k tokens", "~150k tokens (recommended)",
                "~300k tokens", "~600k tokens (very long context)"}, prefs.getInt("tokens", 2));
        images = spinner(R.id.images, new String[]{"Normal – drawings & tables as page images",
                "High detail – also 4 zoom tiles per drawing (small print, P&IDs)", "Text only – smallest, no images"}, prefs.getInt("images", 0));
        pick = findViewById(R.id.pick);
        cancel = findViewById(R.id.cancel);
        shareAll = findViewById(R.id.share_all);
        saveAll = findViewById(R.id.save_all);
        bar = findViewById(R.id.bar);
        status = findViewById(R.id.status);
        log = findViewById(R.id.log);
        results = findViewById(R.id.results);

        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "text/*", "image/*", "application/octet-stream"});
            startActivityForResult(i, PICK);
        });
        cancel.setOnClickListener(v -> { cancelled = true; status.setText("Cancelling…"); });
        shareAll.setOnClickListener(v -> share(zips));
        saveAll.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), SAVE_TREE));
        showResults();
        handleIncoming(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleIncoming(i); }

    /** "Open with DocPrep" / "Share → DocPrep" from a file manager or email app. */
    private void handleIncoming(Intent i) {
        if (i == null) return;
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(i.getAction())) u = i.getData();
        else if (Intent.ACTION_SEND.equals(i.getAction())) u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u != null && worker == null) start(u);
    }

    private Spinner spinner(int id, String[] items, int sel) {
        Spinner s = findViewById(id);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(Math.min(sel, items.length - 1));
        return s;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == PICK) start(data.getData());
        else if (req == SAVE_TREE) saveTo(data.getData());
    }

    private void start(Uri uri) {
        if (worker != null) return;
        Options o = new Options();
        o.maxBytes = SIZES_MB[size.getSelectedItemPosition()] * 1024 * 1024;
        o.maxTokens = TOKENS[tokens.getSelectedItemPosition()];
        o.imageMode = images.getSelectedItemPosition();
        prefs.edit().putInt("size", size.getSelectedItemPosition()).putInt("tokens", tokens.getSelectedItemPosition())
                .putInt("images", images.getSelectedItemPosition()).apply();

        final String name = displayName(uri);
        cancelled = false;
        busy(true);
        log.setText("");
        status.setText("Copying " + name + "…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker = new Thread(() -> {
            String msg;
            List<File> out = null;
            List<String> warnings = null;
            long t0 = System.currentTimeMillis();
            File in = new File(getCacheDir(), "input" + ext(name));
            try {
                copy(uri, in);
                Processor.Result r = Processor.run(this, in, name, o, outDir(this), new PdfSource.Progress() {
                    long last;
                    @Override public void update(String m, int done, int total) {
                        long now = System.currentTimeMillis();
                        if (now - last < 150 && total > 0 && done < total - 1) return;
                        last = now;
                        runOnUiThread(() -> {
                            status.setText(m);
                            bar.setIndeterminate(total <= 0);
                            if (total > 0) { bar.setMax(total); bar.setProgress(done); }
                        });
                    }
                    @Override public boolean cancelled() { return cancelled; }
                });
                out = r.zips;
                warnings = r.warnings;
                msg = "✅ " + r.summary + String.format(Locale.US, " Took %d s.", (System.currentTimeMillis() - t0) / 1000);
            } catch (InterruptedException e) {
                msg = "Cancelled.";
            } catch (OutOfMemoryError e) {
                msg = "❌ The phone ran out of memory. Try 'Text only' or a smaller part size.";
            } catch (Throwable e) {
                msg = "❌ " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            } finally {
                in.delete();
            }
            final String fm = msg;
            final List<File> fo = out;
            final List<String> fw = warnings;
            runOnUiThread(() -> {
                worker = null;
                busy(false);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                status.setText(fm);
                if (fw != null && !fw.isEmpty()) log.setText("Notes:\n• " + android.text.TextUtils.join("\n• ", fw));
                if (fo == null) Processor.deleteTree(outDir(this));
                showResults();
            });
        }, "docprep");
        worker.start();
    }

    private void busy(boolean b) {
        pick.setEnabled(!b);
        cancel.setVisibility(b ? View.VISIBLE : View.GONE);
        bar.setVisibility(b ? View.VISIBLE : View.GONE);
        bar.setIndeterminate(true);
    }

    private void showResults() {
        zips.clear();
        File[] fs = outDir(this).listFiles();
        if (fs != null) {
            java.util.Arrays.sort(fs);
            for (File f : fs) if (f.getName().endsWith(".zip")) zips.add(f);
        }
        results.removeAllViews();
        for (File f : zips) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 6, 0, 6);
            TextView t = new TextView(this);
            t.setText(f.getName() + "\n" + String.format(Locale.US, "%.1f MB", f.length() / 1048576.0));
            t.setTextSize(13);
            row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button s = new Button(this);
            s.setText("Share");
            s.setOnClickListener(v -> { List<File> one = new ArrayList<>(); one.add(f); share(one); });
            row.addView(s);
            results.addView(row);
        }
        shareAll.setVisibility(zips.size() > 1 ? View.VISIBLE : View.GONE);
        saveAll.setVisibility(zips.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void share(List<File> files) {
        if (files.isEmpty()) return;
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : files) uris.add(ShareProvider.uriFor(f));
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/zip").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        ClipData clip = ClipData.newRawUri(files.get(0).getName(), uris.get(0));
        for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
        i.setClipData(clip);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Send to…"));
    }

    private void saveTo(Uri tree) {
        new Thread(() -> {
            int ok = 0;
            String err = null;
            try {
                Uri dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                for (File f : zips) {
                    Uri doc = DocumentsContract.createDocument(getContentResolver(), dir, "application/zip", f.getName());
                    if (doc == null) continue;
                    try (InputStream in = new FileInputStream(f); OutputStream o = getContentResolver().openOutputStream(doc)) {
                        byte[] b = new byte[1 << 16];
                        for (int k; (k = in.read(b)) > 0; ) o.write(b, 0, k);
                    }
                    ok++;
                }
            } catch (Exception e) { err = e.getMessage(); }
            final String m = err == null ? "Saved " + ok + " file(s)." : "Save failed: " + err;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void copy(Uri uri, File dst) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream o = new FileOutputStream(dst)) {
            if (in == null) throw new IllegalStateException("Can't open the file.");
            byte[] b = new byte[1 << 16];
            long n = 0;
            for (int k; (k = in.read(b)) > 0; ) {
                o.write(b, 0, k);
                n += k;
                if (cancelled) throw new InterruptedException();
                if ((n & ((1 << 23) - 1)) < k) { final long nn = n; runOnUiThread(() -> status.setText(String.format(Locale.US, "Copying… %.0f MB", nn / 1048576.0))); }
            }
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {}
        String p = uri.getLastPathSegment();
        return p == null ? "document" : p.substring(p.lastIndexOf('/') + 1);
    }

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i).toLowerCase(Locale.ROOT);
    }

    @Override
    public void onBackPressed() {
        if (worker == null) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this).setMessage("Processing is still running. Cancel it?")
                .setPositiveButton("Cancel processing", (d, w) -> cancelled = true)
                .setNegativeButton("Keep going", null).show();
    }
}

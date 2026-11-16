package com.fieldhelper.diag;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.fieldhelper.diag.ai.MiniLm;
import com.fieldhelper.diag.ai.PaddleOcr;
import com.fieldhelper.diag.diagram.PageGeom;
import com.fieldhelper.diag.diagram.ScanReader;
import com.fieldhelper.diag.ai.SemanticIndex;
import com.fieldhelper.diag.core.Card;
import com.fieldhelper.diag.core.CircuitTracer;
import com.fieldhelper.diag.core.Engine;
import com.fieldhelper.diag.core.Reply;
import com.fieldhelper.diag.diagram.DiagramReader;
import com.fieldhelper.diag.diagram.PdfExtractor;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Offline diagnosis helper. All screens are built in code; the only library reads PDF drawings. */
public class MainActivity extends Activity {

    private static final int BG = Color.parseColor("#F4F5F7");
    private static final int ACCENT = Color.parseColor("#0F6E56");
    private static final int ACCENT_LIGHT = Color.parseColor("#E1F5EE");
    private static final int TEXT = Color.parseColor("#1E1E1E");
    private static final int MUTED = Color.parseColor("#6B6B6B");
    private static final int USER_BUBBLE = Color.parseColor("#0F6E56");
    private static final int BOT_BUBBLE = Color.WHITE;
    private static final int REQ_IMPORT = 42;
    private static final int REQ_PDF = 43;
    private static final String PRACTICE_PDF = "practice_drawing.pdf";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean reading = false;
    private TextView titleView;
    private volatile SemanticIndex semantic;
    private PaddleOcr ocr; // loaded the first time a scanned page needs it
    private final java.util.concurrent.ExecutorService aiThread = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final String USER_FILE = "user_cards.json";

    private final Engine engine = new Engine();
    private FrameLayout content;
    private final List<Button> tabs = new ArrayList<>();
    private View askView, cardsView, editView;

    private LinearLayout chat;
    private ScrollView chatScroll;
    private EditText input;

    private ArrayAdapter<String> cardAdapter;
    private final List<Card> shownCards = new ArrayList<>();
    private EditText cardSearch;

    private EditText fId, fTitle, fTags, fSimple, fPath, fLoad, fControl, fChecks, fLinks, fSource;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        PDFBoxResourceLoader.init(getApplicationContext());
        String loadMsg = loadAllCards();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        TextView title = new TextView(this);
        title.setText("Diag Helper");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(16), dp(14), dp(16), dp(4));
        title.setBackgroundColor(ACCENT);
        root.addView(title);
        titleView = title;

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setBackgroundColor(ACCENT);
        tabRow.setPadding(dp(8), 0, dp(8), dp(6));
        String[] names = {"Ask", "Cards", "Add card"};
        for (int k = 0; k < names.length; k++) {
            final int idx = k;
            Button b = new Button(this);
            b.setText(names[k]);
            b.setAllCaps(false);
            b.setTextSize(15);
            b.setBackgroundColor(Color.TRANSPARENT);
            b.setOnClickListener(v -> showTab(idx));
            tabRow.addView(b, new LinearLayout.LayoutParams(0, dp(42), 1));
            tabs.add(b);
        }
        root.addView(tabRow);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        askView = buildAsk();
        cardsView = buildCards();
        editView = buildEditor();
        content.addView(askView);
        content.addView(cardsView);
        content.addView(editView);

        setContentView(root);
        showTab(0);
        addBot(engine.help());
        if (loadMsg != null) toast(loadMsg);
        startDonorModel();
    }

    // ---------- donor model (meaning search) ----------

    private void setStatus(String s) { ui.post(() -> titleView.setText(s.isEmpty() ? "Diag Helper" : "Diag Helper  ·  " + s)); }

    /** Loads the small offline model once, then prepares every card (cached, so later starts are quick). */
    private void startDonorModel() {
        List<Card> cards = new ArrayList<>(engine.index.all());
        aiThread.execute(() -> {
            try {
                setStatus("AI loading…");
                File model = new File(getFilesDir(), "minilm.onnx");
                long assetLen;
                try (android.content.res.AssetFileDescriptor fd = getAssets().openFd("model/minilm.onnx")) { assetLen = fd.getLength(); }
                if (!model.exists() || model.length() != assetLen) {
                    try (InputStream in = getAssets().open("model/minilm.onnx"); FileOutputStream out = new FileOutputStream(model)) { copy(in, out); }
                }
                MiniLm m;
                try (InputStream vocab = getAssets().open("model/vocab.txt")) { m = new MiniLm(model.getPath(), vocab); }
                SemanticIndex si = new SemanticIndex(m, new File(getFilesDir(), "card_vectors.bin"));
                si.sync(cards, (done, total) -> setStatus("AI preparing cards " + (done * 100 / Math.max(1, total)) + "%"));
                semantic = si;
                ui.post(() -> engine.setSemantic(si));
                setStatus("AI ready");
                ui.postDelayed(() -> setStatus(""), 3000);
            } catch (Throwable t) {
                setStatus("");
                ui.post(() -> toast("Meaning search is off (" + t.getMessage() + "). Word search still works."));
            }
        });
    }

    /** Call after cards change: updates the model's view of the cards in the background. */
    private void resyncDonorModel() {
        SemanticIndex si = semantic;
        if (si == null) return;
        List<Card> cards = new ArrayList<>(engine.index.all());
        aiThread.execute(() -> {
            si.sync(cards, (done, total) -> setStatus("AI updating " + (done * 100 / Math.max(1, total)) + "%"));
            setStatus("");
        });
    }

    // ---------- data ----------

    private String loadAllCards() {
        String problem = null;
        try {
            String[] files = getAssets().list("cards");
            if (files != null) {
                Arrays.sort(files);
                for (String f : files) {
                    if (!f.endsWith(".json")) continue;
                    try (InputStream in = getAssets().open("cards/" + f)) {
                        engine.loadCards(readAll(in), false);
                    } catch (Exception e) {
                        problem = "Card file " + f + " has an error: " + e.getMessage();
                    }
                }
            }
        } catch (Exception e) {
            problem = "Couldn't read built-in cards: " + e.getMessage();
        }
        File uf = new File(getFilesDir(), USER_FILE);
        if (uf.exists()) {
            try (InputStream in = new java.io.FileInputStream(uf)) {
                engine.loadCards(readAll(in), true);
            } catch (Exception e) {
                problem = "Your saved cards couldn't be read: " + e.getMessage();
            }
        }
        return problem;
    }

    private void saveUserCards() {
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), USER_FILE))) {
            out.write(engine.userCardsJson().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
        }
        resyncDonorModel();
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---------- tabs ----------

    private void showTab(int idx) {
        askView.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        cardsView.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        editView.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        for (int k = 0; k < tabs.size(); k++) {
            Button b = tabs.get(k);
            b.setTextColor(k == idx ? Color.WHITE : Color.parseColor("#B8E3D4"));
            b.setTypeface(k == idx ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        if (idx == 1) refreshCards();
    }

    // ---------- Ask screen ----------

    private View buildAsk() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        chat = new LinearLayout(this);
        chat.setOrientation(LinearLayout.VERTICAL);
        chat.setPadding(dp(10), dp(10), dp(10), dp(10));
        chatScroll = new ScrollView(this);
        chatScroll.addView(chat);
        box.addView(chatScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(dp(6), dp(4), dp(6), dp(2));
        String[][] quick = {
                {"Help", "help"}, {"Trace path", "trace 24V -> F1 -> S1 -> H1 -> 0V"}, {"Ohm's law", "V=24 R=12"},
                {"Cable drop", "drop L=20 A=1.5 I=5 V=24"}, {"4-20 mA", "4-20 12 0 10 bar"}, {"Convert", "conv 5 bar psi"},
                {"Example fault", "H5 lamp not glowing"}, {"Drawing example", "H7 alarm lamp not glowing"}};
        for (String[] q : quick) {
            TextView c = chip(q[0]);
            c.setOnClickListener(v -> { input.setText(q[1]); input.setSelection(q[1].length()); });
            chips.addView(c);
        }
        chipScroll.addView(chips);
        box.addView(chipScroll);

        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(8), dp(4), dp(8), dp(8));
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint("Describe the symptom or ask…");
        input.setTextSize(16);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setBackground(round(Color.WHITE, dp(22), Color.parseColor("#D0D0D0")));
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        input.setOnEditorActionListener((v, actionId, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) { send(); return true; }
            return false;
        });
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button sendB = new Button(this);
        sendB.setText("Ask");
        sendB.setAllCaps(false);
        sendB.setTextColor(Color.WHITE);
        sendB.setBackground(round(ACCENT, dp(22), 0));
        sendB.setOnClickListener(v -> send());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(72), dp(46));
        sp.leftMargin = dp(6);
        row.addView(sendB, sp);
        box.addView(row);
        return box;
    }

    private void send() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        input.setText("");
        addUser(q);
        addBot(engine.ask(q));
    }

    private void addUser(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setTextIsSelectable(true);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setBackground(round(USER_BUBBLE, dp(14), 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.leftMargin = dp(48);
        lp.topMargin = dp(8);
        chat.addView(t, lp);
        scrollDown();
    }

    private void addBot(Reply r) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        b.setBackground(round(BOT_BUBBLE, dp(14), Color.parseColor("#E2E2E2")));

        TextView t = new TextView(this);
        t.setText(r.text);
        t.setTextColor(r.kind.equals("none") ? Color.parseColor("#993C1D") : TEXT);
        t.setTextSize(15);
        t.setLineSpacing(0, 1.15f);
        t.setTextIsSelectable(true);
        b.addView(t);

        if (!r.refs.isEmpty()) {
            TextView lbl = new TextView(this);
            lbl.setText("Sources (tap a card to open):");
            lbl.setTextColor(MUTED);
            lbl.setTextSize(12);
            lbl.setPadding(0, dp(8), 0, dp(2));
            b.addView(lbl);
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout refs = new LinearLayout(this);
            for (String ref : r.refs) {
                TextView c = chip(ref);
                c.setTextSize(12);
                if (engine.index.get(ref) != null) c.setOnClickListener(v -> openCardDialog(engine.index.get(ref)));
                else c.setBackground(round(Color.parseColor("#F1EFE8"), dp(12), 0));
                refs.addView(c);
            }
            hs.addView(refs);
            b.addView(hs);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.rightMargin = dp(16);
        chat.addView(b, lp);
        scrollDown();
    }

    private void scrollDown() { chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN)); }

    // ---------- Cards screen ----------

    private View buildCards() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), 0);

        cardSearch = new EditText(this);
        cardSearch.setHint("Filter cards (ID, title or tag)");
        cardSearch.setSingleLine(true);
        cardSearch.setBackground(round(Color.WHITE, dp(10), Color.parseColor("#D0D0D0")));
        cardSearch.setPadding(dp(12), dp(10), dp(12), dp(10));
        cardSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { refreshCards(); }
        });
        box.addView(cardSearch);

        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, dp(6), 0, dp(6));
        btns.addView(smallButton("Import file", v -> pickImportFile()), wl(44));
        btns.addView(smallButton("Paste JSON", v -> pasteImport()), wl(44));
        btns.addView(smallButton("Export mine", v -> exportCards()), wl(44));
        box.addView(btns);

        LinearLayout btns2 = new LinearLayout(this);
        btns2.setPadding(0, 0, 0, dp(6));
        btns2.addView(smallButton("Read drawing PDF", v -> pickPdf()), wl(44));
        btns2.addView(smallButton("Try practice drawing", v -> readPractice()), wl(44));
        box.addView(btns2);

        ListView list = new ListView(this);
        cardAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(cardAdapter);
        list.setOnItemClickListener((p, v, pos, id) -> openCardDialog(shownCards.get(pos)));
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    private void refreshCards() {
        if (cardAdapter == null) return;
        String f = cardSearch.getText().toString().trim().toLowerCase();
        shownCards.clear();
        List<String> rows = new ArrayList<>();
        for (Card c : engine.index.all()) {
            String hay = (c.id + " " + c.title + " " + String.join(" ", c.tags)).toLowerCase();
            if (!f.isEmpty() && !hay.contains(f)) continue;
            shownCards.add(c);
            rows.add(c.id + (c.user ? "  ★" : "") + "\n" + c.title);
        }
        cardAdapter.clear();
        cardAdapter.addAll(rows);
        cardAdapter.notifyDataSetChanged();
    }

    private void openCardDialog(Card c) {
        if (c == null) return;
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle(c.id + " · " + c.type())
                .setMessage(engine.showCard(c.id).text)
                .setPositiveButton("Edit", (x, y) -> { fillEditor(c); showTab(2); })
                .setNeutralButton("Close", null);
        if (c.user) {
            d.setNegativeButton("Delete", (x, y) -> confirmDelete(c.id));
        } else if (c.type().equals("Circuit") || c.type().equals("Fault") || c.type().equals("Component")) {
            d.setNegativeButton("Ask", (x, y) -> {
                showTab(0);
                String q = c.type().equals("Circuit") ? c.id : c.title;
                addUser(q);
                addBot(engine.ask(q));
            });
        }
        d.show();
    }

    private void confirmDelete(String id) {
        if (id.toUpperCase().startsWith("DOC-")) {
            String code = id.substring(4);
            new AlertDialog.Builder(this)
                    .setTitle("Remove drawing " + code + "?")
                    .setMessage("This removes the drawing card and every circuit/part card read from it. You can read the PDF again any time.")
                    .setPositiveButton("Remove", (x, y) -> {
                        int n = engine.deleteDocument(code);
                        saveUserCards();
                        refreshCards();
                        toast(n + " card(s) removed");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete " + id + "?")
                .setMessage("This removes your card from the phone. It can't be undone (export first if unsure). If it replaced a built-in card, restart the app to see the built-in one again.")
                .setPositiveButton("Delete", (x, y) -> {
                    engine.deleteUserCard(id);
                    saveUserCards();
                    refreshCards();
                    toast(id + " deleted");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickImportFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            toast("No file picker found");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_PDF) { readPdf(uri); return; }
        if (req != REQ_IMPORT) return;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            importJson(readAll(in));
        } catch (Exception e) {
            toast("Import failed: " + e.getMessage());
        }
    }

    // ---------- Reading drawings ----------

    private void pickPdf() {
        if (reading) { toast("Still reading the last drawing…"); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        try {
            startActivityForResult(i, REQ_PDF);
        } catch (Exception e) {
            toast("No file picker found");
        }
    }

    private void readPractice() {
        if (reading) { toast("Still reading the last drawing…"); return; }
        try {
            File f = new File(getCacheDir(), PRACTICE_PDF);
            try (InputStream in = getAssets().open(PRACTICE_PDF); FileOutputStream out = new FileOutputStream(f)) { copy(in, out); }
            readPdfFile(f, PRACTICE_PDF);
        } catch (Exception e) {
            toast("Couldn't open the practice drawing: " + e.getMessage());
        }
    }

    private void readPdf(Uri uri) {
        String name = displayName(uri);
        try {
            File f = new File(getCacheDir(), "drawing.pdf");
            try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(f)) { copy(in, out); }
            readPdfFile(f, name);
        } catch (Exception e) {
            toast("Couldn't open that PDF: " + e.getMessage());
        }
    }

    /** Reads every page on a background thread, with a progress bar. */
    private void readPdfFile(File file, String name) {
        reading = true;
        TextView msg = new TextView(this);
        msg.setPadding(dp(20), dp(16), dp(20), dp(8));
        msg.setTextSize(15);
        msg.setTextColor(TEXT);
        msg.setText("Opening " + name + "…");
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Reading drawing").setView(msg).setCancelable(false)
                .setNegativeButton("Stop", (x, y) -> reading = false).show();
        new Thread(() -> {
            List<DiagramReader.PageResult> pages = new ArrayList<>();
            List<ScanReader.ScanPage> scans = new ArrayList<>();
            String error = null;
            android.graphics.pdf.PdfRenderer renderer = null;
            android.os.ParcelFileDescriptor pfd = null;
            try (PDDocument doc = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())) {
                int n = doc.getNumberOfPages();
                for (int i = 0; i < n && reading; i++) {
                    final int done = i;
                    ui.post(() -> msg.setText("Page " + (done + 1) + " of " + n + "\n" + bar(done, n)));
                    try {
                        PageGeom g = PdfExtractor.page(doc, i);
                        DiagramReader.PageResult pr = DiagramReader.readPage(g);
                        if (pr.scanned) {
                            // a picture of the page: read its text with the OCR models
                            ui.post(() -> msg.setText("Page " + (done + 1) + " of " + n + " · scanned, reading text (OCR)…\n" + bar(done, n)));
                            if (renderer == null) {
                                pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                                renderer = new android.graphics.pdf.PdfRenderer(pfd);
                            }
                            scans.add(ocrPage(renderer, i));
                        } else pages.add(pr);
                    } catch (Exception | OutOfMemoryError pe) {
                        DiagramReader.PageResult bad = new DiagramReader.PageResult();
                        bad.page = i + 1;
                        bad.issues.add("Page " + (i + 1) + " couldn't be read: " + pe.getMessage());
                        pages.add(bad);
                    }
                }
            } catch (Exception | OutOfMemoryError e) {
                error = e.getMessage();
            } finally {
                try { if (renderer != null) renderer.close(); if (pfd != null) pfd.close(); } catch (Exception ignored) { }
            }
            final boolean stopped = !reading;
            final String err = error;
            String code = DiagramReader.docCodeOf(name);
            final List<Card> cards = new ArrayList<>();
            final StringBuilder report = new StringBuilder();
            if (err == null) {
                if (!pages.isEmpty()) {
                    DiagramReader.DocResult dr = DiagramReader.toCards(name, pages);
                    for (Card c : dr.cards) if (scans.isEmpty() || !c.id.equalsIgnoreCase("DOC-" + code)) cards.add(c);
                    report.append(dr.report);
                }
                if (!scans.isEmpty()) {
                    List<Card> sc = ScanReader.toCards(name, code, scans);
                    cards.addAll(sc);
                    if (report.length() > 0) report.append("\n\n");
                    report.append(scans.size()).append(" scanned sheet(s) read by OCR. Text is searchable now; wires in pictures are not followed.\n");
                    report.append("Ask e.g. \"oil pressure\" or a plug name. Letters can be misread: check the sheet.\n");
                    for (Card c : sc) if (!c.id.equalsIgnoreCase("DOC-" + code) && report.length() < 1500) report.append("\n• ").append(c.title.length() > 70 ? c.title.substring(0, 70) + "…" : c.title);
                }
            }
            ui.post(() -> {
                reading = false;
                dlg.dismiss();
                if (err != null) {
                    new AlertDialog.Builder(this).setTitle("Couldn't read " + name).setMessage(err).setPositiveButton("OK", null).show();
                    return;
                }
                if (!cards.isEmpty()) {
                    engine.deleteDocument(code);
                    for (Card c : cards) engine.saveUserCard(c);
                    saveUserCards();
                    refreshCards();
                }
                String head = stopped ? "Stopped early. Cards were made from the pages read so far.\n\n" : "";
                new AlertDialog.Builder(this)
                        .setTitle(name)
                        .setMessage(head + report.toString().trim())
                        .setPositiveButton("OK", null)
                        .show();
            });
        }).start();
    }

    /** Draws one page as a grey picture (200 dpi) and reads its text. Runs on the reading thread. */
    private ScanReader.ScanPage ocrPage(android.graphics.pdf.PdfRenderer renderer, int index) throws Exception {
        if (ocr == null) {
            File det = assetFile("ocr/det.onnx"), rec = assetFile("ocr/rec.onnx");
            try (InputStream keys = getAssets().open("ocr/keys.txt")) { ocr = new PaddleOcr(det.getPath(), rec.getPath(), keys); }
        }
        try (android.graphics.pdf.PdfRenderer.Page page = renderer.openPage(index)) {
            float scale = 200f / 72f;
            int w = Math.max(1, Math.round(page.getWidth() * scale)), h = Math.max(1, Math.round(page.getHeight() * scale));
            long px = (long) w * h;
            if (px > 12_000_000L) { double f = Math.sqrt(12_000_000.0 / px); w = (int) (w * f); h = (int) (h * f); }
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            byte[] gray = new byte[w * h];
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                bmp.getPixels(row, 0, w, 0, y, w, 1);
                for (int x = 0; x < w; x++) {
                    int c = row[x];
                    gray[y * w + x] = (byte) ((((c >> 16) & 255) * 3 + ((c >> 8) & 255) * 6 + (c & 255)) / 10);
                }
            }
            bmp.recycle();
            return new ScanReader.ScanPage(index + 1, w, h, ocr.read(new PaddleOcr.Gray(w, h, gray)));
        }
    }

    /** Copies an asset to app storage once (models must be files for the model runtime). */
    private File assetFile(String asset) throws java.io.IOException {
        File f = new File(getFilesDir(), asset.replace('/', '_'));
        long len;
        try (android.content.res.AssetFileDescriptor fd = getAssets().openFd(asset)) { len = fd.getLength(); }
        if (!f.exists() || f.length() != len) {
            try (InputStream in = getAssets().open(asset); FileOutputStream out = new FileOutputStream(f)) { copy(in, out); }
        }
        return f;
    }

    private static String bar(int done, int total) {
        int pct = total == 0 ? 0 : done * 100 / total;
        int fill = pct / 10;
        StringBuilder b = new StringBuilder("[");
        for (int k = 0; k < 10; k++) b.append(k < fill ? "█" : "░");
        return b.append("] ").append(pct).append("%").toString();
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && c.getString(i) != null) return c.getString(i);
            }
        } catch (Exception ignored) { }
        return "drawing.pdf";
    }

    private static void copy(InputStream in, java.io.OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private void pasteImport() {
        EditText box = new EditText(this);
        box.setHint("Paste card JSON here");
        box.setMinLines(6);
        box.setGravity(Gravity.TOP);
        new AlertDialog.Builder(this)
                .setTitle("Import cards")
                .setView(box)
                .setPositiveButton("Import", (x, y) -> importJson(box.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void importJson(String json) {
        try {
            int n = engine.loadCards(json, true);
            saveUserCards();
            refreshCards();
            toast(n + " card(s) imported");
        } catch (Exception e) {
            toast("That JSON has an error: " + e.getMessage());
        }
    }

    private void exportCards() {
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_SUBJECT, "Diag Helper cards");
        s.putExtra(Intent.EXTRA_TEXT, engine.userCardsJson());
        startActivity(Intent.createChooser(s, "Export my cards"));
    }

    // ---------- Card editor ----------

    private View buildEditor() {
        ScrollView sv = new ScrollView(this);
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(14), dp(10), dp(14), dp(24));

        TextView help = new TextView(this);
        help.setText("ID prefix sets the type: P- principle, S- system, C- component, CKT- circuit, F- fault, DOC- source. "
                + "Saving with an existing ID replaces that card.");
        help.setTextColor(MUTED);
        help.setTextSize(13);
        f.addView(help);

        fId = field(f, "ID (e.g. CKT-010)", false);
        fTitle = field(f, "Title", false);
        fTags = field(f, "Tags, comma separated (e.g. h5, lamp, running)", false);
        fSimple = field(f, "Simple explanation", true);
        fPath = field(f, "Circuit path (e.g. 24V -> F3 -> K12:13-14 -> H5 -> 0V)", true);
        fLoad = field(f, "Load: the part that should work (e.g. H5)", false);
        fControl = field(f, "Switched by (e.g. K12 coil <- PLC DO4)", false);
        fChecks = field(f, "Fault checks, one per line, in order", true);
        fLinks = field(f, "Links to other cards, comma separated (e.g. C-K12, P-004)", false);
        fSource = field(f, "Source (e.g. DOC-02 sheet 7)", false);

        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(12), 0, 0);
        row.addView(smallButton("Save", v -> saveEditor()), wl(46));
        row.addView(smallButton("Test trace", v -> testTrace()), wl(46));
        row.addView(smallButton("Clear", v -> fillEditor(null)), wl(46));
        f.addView(row);

        sv.addView(f);
        return sv;
    }

    private EditText field(LinearLayout parent, String label, boolean multi) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(TEXT);
        l.setTextSize(13);
        l.setPadding(0, dp(10), 0, dp(2));
        parent.addView(l);
        EditText e = new EditText(this);
        e.setTextSize(15);
        e.setBackground(round(Color.WHITE, dp(8), Color.parseColor("#D0D0D0")));
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(2);
            e.setGravity(Gravity.TOP);
        } else {
            e.setSingleLine(true);
        }
        parent.addView(e);
        return e;
    }

    private void fillEditor(Card c) {
        fId.setText(c == null ? "" : c.id);
        fTitle.setText(c == null ? "" : c.title);
        fTags.setText(c == null ? "" : String.join(", ", c.tags));
        fSimple.setText(c == null ? "" : c.simple);
        fPath.setText(c == null ? "" : c.path);
        fLoad.setText(c == null ? "" : c.load);
        fControl.setText(c == null ? "" : c.control);
        fChecks.setText(c == null ? "" : String.join("\n", c.checks));
        fLinks.setText(c == null ? "" : String.join(", ", c.links));
        fSource.setText(c == null ? "" : c.source);
    }

    private void saveEditor() {
        String id = fId.getText().toString().trim().toUpperCase();
        if (id.isEmpty() || !id.matches("(P|S|C|CKT|F|DOC)-[A-Z0-9_.:-]+")) {
            fId.setError("Use a prefix like CKT-010, F-020, C-K12");
            return;
        }
        if (fTitle.getText().toString().trim().isEmpty()) {
            fTitle.setError("Give the card a title");
            return;
        }
        Card c = new Card();
        c.id = id;
        c.title = fTitle.getText().toString().trim();
        c.tags = Card.splitList(fTags.getText().toString(), ",");
        c.simple = fSimple.getText().toString().trim();
        c.path = fPath.getText().toString().trim();
        c.load = fLoad.getText().toString().trim();
        c.control = fControl.getText().toString().trim();
        c.checks = Card.splitList(fChecks.getText().toString(), "\n");
        c.links = Card.splitList(fLinks.getText().toString(), ",");
        c.source = fSource.getText().toString().trim();
        engine.saveUserCard(c);
        saveUserCards();
        toast(id + " saved");
    }

    private void testTrace() {
        String path = fPath.getText().toString().trim();
        if (path.isEmpty()) { fPath.setError("Type a path first"); return; }
        List<String> chk = CircuitTracer.checks(path, fLoad.getText().toString(), fControl.getText().toString());
        StringBuilder b = new StringBuilder(CircuitTracer.worksWhen(path, fLoad.getText().toString(), fControl.getText().toString())).append("\n\n");
        for (int k = 0; k < chk.size(); k++) b.append(k + 1).append(") ").append(chk.get(k)).append("\n");
        new AlertDialog.Builder(this).setTitle("Trace preview").setMessage(b.toString().trim()).setPositiveButton("OK", null).show();
    }

    // ---------- small helpers ----------

    private TextView chip(String text) {
        TextView c = new TextView(this);
        c.setText(text);
        c.setTextColor(ACCENT);
        c.setTextSize(13);
        c.setPadding(dp(12), dp(6), dp(12), dp(6));
        c.setBackground(round(ACCENT_LIGHT, dp(14), 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        c.setLayoutParams(lp);
        return c;
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(ACCENT);
        b.setBackground(round(ACCENT_LIGHT, dp(10), 0));
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable round(int fill, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    /** Equal-width button slot with a small gap. */
    private LinearLayout.LayoutParams wl(int heightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(heightDp), 1);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        return lp;
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }
}

package com.opensearch.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView web;
    private EditText input;
    private Spinner engineSpinner;
    private ProgressBar progress;
    private TextView home;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        web = findViewById(R.id.web);
        input = findViewById(R.id.query);
        engineSpinner = findViewById(R.id.engine);
        progress = findViewById(R.id.progress);
        home = findViewById(R.id.home);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, Engines.NAMES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        engineSpinner.setAdapter(adapter);
        engineSpinner.setSelection(prefs.getInt("engine", 0));
        engineSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt("engine", pos).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        setupWebView();

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                search(input.getText().toString());
                return true;
            }
            return false;
        });
        findViewById(R.id.go).setOnClickListener(v -> search(input.getText().toString()));
        ImageButton menu = findViewById(R.id.menu);
        menu.setOnClickListener(this::showMenu);

        handleIntent(getIntent());
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, false);
        applyFilterOffCookies();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String scheme = req.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl())); }
                catch (Exception ignored) {}
                return true;
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap fav) {
                progress.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView v, String url) {
                progress.setVisibility(View.GONE);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) { progress.setProgress(p); }
        });
    }

    private void applyFilterOffCookies() {
        CookieManager cm = CookieManager.getInstance();
        for (String[] c : Engines.COOKIES) cm.setCookie(c[0], c[1] + "; path=/; max-age=31536000");
        cm.flush();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String q = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) q = intent.getStringExtra(Intent.EXTRA_TEXT);
        else if (Intent.ACTION_WEB_SEARCH.equals(intent.getAction())) q = intent.getStringExtra("query");
        if (q != null && !q.trim().isEmpty()) { input.setText(q); search(q); }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void search(String raw) {
        String q = raw.trim();
        if (q.isEmpty()) return;
        hideKeyboard();
        String url;
        // Typed a web address? Open it directly.
        if (q.matches("^(https?://)?[\\w.-]+\\.[a-z]{2,}(/\\S*)?$") && !q.contains(" ")) {
            url = q.startsWith("http") ? q : "https://" + q;
        } else {
            url = Engines.buildUrl(engineSpinner.getSelectedItemPosition(), q, prefs);
        }
        home.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.loadUrl(url);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Open in browser");
        pm.getMenu().add(0, 2, 0, "Share link");
        pm.getMenu().add(0, 3, 0, "Clear history & cookies");
        pm.getMenu().add(0, 4, 0, "Settings");
        pm.setOnMenuItemClickListener(item -> {
            String cur = web.getUrl();
            switch (item.getItemId()) {
                case 1:
                    if (cur != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(cur)));
                    return true;
                case 2:
                    if (cur != null) {
                        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, cur);
                        startActivity(Intent.createChooser(i, "Share"));
                    }
                    return true;
                case 3:
                    clearData();
                    return true;
                case 4:
                    startActivity(new Intent(this, SettingsActivity.class));
                    return true;
            }
            return false;
        });
        pm.show();
    }

    private void clearData() {
        web.clearHistory();
        web.clearCache(true);
        web.clearFormData();
        WebStorage.getInstance().deleteAllData();
        CookieManager.getInstance().removeAllCookies(ok -> applyFilterOffCookies());
        web.loadUrl("about:blank");
        web.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (prefs.getBoolean("auto_clear", false) && isFinishing()) clearData();
    }

    @Override
    public void onBackPressed() {
        if (web.getVisibility() == View.VISIBLE && web.canGoBack()) web.goBack();
        else if (web.getVisibility() == View.VISIBLE) {
            web.setVisibility(View.GONE);
            home.setVisibility(View.VISIBLE);
        } else super.onBackPressed();
    }
}

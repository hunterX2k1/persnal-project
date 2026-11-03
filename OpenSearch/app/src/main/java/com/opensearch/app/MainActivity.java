package com.opensearch.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
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

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView web;
    private EditText input;
    private Spinner engineSpinner;
    private ProgressBar progress;
    private TextView home;
    private TextView status;
    private SharedPreferences prefs;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Proxy rule currently active inside WebView ("" = direct), or null while it's being applied. */
    private String activeRule = null;
    private String pendingUrl;
    private ScriptHandler scriptHandler;
    private String injectedScript;
    private boolean reloadWhenNewIp = false;

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
        status = findViewById(R.id.status);

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
        status.setOnClickListener(v -> pickCountry());
        TorManager.setListener(this::onTorChanged);
        applyPrivacy();

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
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setGeolocationEnabled(false);          // real GPS is never exposed (app has no location permission either)
        s.setSaveFormData(false);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            // Stop sending "X-Requested-With: com.opensearch.app" to every site.
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, Collections.<String>emptySet());
        }

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
                // Fallback for very old WebView without document-start scripts (best effort).
                if (scriptHandler == null && injectedScript != null) v.evaluateJavascript(injectedScript, null);
            }
            @Override public void onPageFinished(WebView v, String url) {
                progress.setVisibility(View.GONE);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) { progress.setProgress(p); }
            @Override public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback cb) {
                cb.invoke(origin, false, false);   // always deny the real location
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyPrivacy();   // settings may have changed
    }

    /** Routes WebView through Tor / the chosen proxy and installs the anti-tracking page script. */
    private void applyPrivacy() {
        WebSettings s = web.getSettings();
        s.setUserAgentString(Privacy.genericUa(prefs) ? Privacy.GENERIC_UA : null);

        String script = Privacy.pageScript(prefs);
        if (!script.equals(injectedScript)) {
            if (scriptHandler != null) { scriptHandler.remove(); scriptHandler = null; }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                scriptHandler = WebViewCompat.addDocumentStartJavaScript(web, script,
                        Collections.singleton("*"));
            }
            injectedScript = script;
        }

        if (Privacy.proxyMode(prefs) == Privacy.MODE_BUILTIN) TorManager.start(this, Privacy.countryPref(prefs));
        else TorManager.stop(this);

        final String rule = Privacy.proxyRule(prefs);
        final String want = rule == null ? "" : rule;
        if (want.equals(activeRule)) { updateStatus(); return; }
        activeRule = null;   // block loading until the new proxy is in place
        updateStatus();

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (rule != null) {
                status.setText("⚠ This phone's Android System WebView is too old for proxy/Tor. "
                        + "Update \"Android System WebView\" from the Play Store.");
                return;      // stay blocked: never fall back to the real IP silently
            }
            activeRule = "";
            updateStatus();
            return;
        }
        Runnable done = () -> ui.post(() -> {
            activeRule = want;
            updateStatus();
            if (pendingUrl != null) { String u = pendingUrl; pendingUrl = null; load(u); }
        });
        if (rule == null) {
            ProxyController.getInstance().clearProxyOverride(Runnable::run, done);
        } else {
            try {
                // No "direct" fallback rule: if the proxy is down, nothing loads (fail closed).
                ProxyConfig cfg = new ProxyConfig.Builder()
                        .addProxyRule(rule)
                        .build();
                ProxyController.getInstance().setProxyOverride(cfg, Runnable::run, done);
            } catch (Exception e) {
                status.setText("⚠ Invalid proxy address: " + rule + " (fix it in Settings)");
            }
        }
    }

    private void onTorChanged() {
        if (reloadWhenNewIp && TorManager.exitIp() != null && web.getVisibility() == View.VISIBLE) {
            reloadWhenNewIp = false;
            web.reload();
        }
        applyPrivacy();   // picks up the Tor port / country once ready; also refreshes the status bar
    }

    private void updateStatus() {
        int mode = Privacy.proxyMode(prefs);
        if (mode == Privacy.MODE_BUILTIN) {
            String cc = TorManager.activeCountry();
            Countries.Country c = Countries.byCode(cc);
            String where = c == null ? "🌍 any country" : c.flag() + " " + c.name;
            String text;
            if (TorManager.error() != null && !TorManager.isReady()) {
                text = "⚠ " + TorManager.error();
            } else if (!TorManager.isReady()) {
                text = "⏳ Starting Tor… " + TorManager.progress() + "%   (" + where + ")";
            } else {
                String ip = TorManager.exitIp();
                text = "🧅 " + where + "  ·  IP " + (ip == null ? "changing…" : ip);
                if (Privacy.mimic(prefs) && c != null) text += "  ·  GPS/time/lang mimicked";
            }
            status.setText(text + "   ▾ change");
            status.setBackgroundColor(TorManager.isReady() ? 0xFF14532D : 0xFF374151);
            return;
        }
        String ip = mode == Privacy.MODE_TOR ? "🧅 IP hidden by Tor (Orbot)"
                : mode == Privacy.MODE_CUSTOM ? "🛡 IP hidden by proxy"
                : "⚠ Real IP visible";
        if (activeRule == null && mode != Privacy.MODE_OFF) ip = "⏳ connecting proxy…";
        String fake = Privacy.locationLabel(prefs);
        String where = fake == null ? "📍 location blocked" : "📍 fake: " + fake;
        String region = Privacy.region(prefs)[1];
        status.setText(ip + "   " + where + (region.isEmpty() ? "" : "   🌐 " + region.toUpperCase()));
        status.setBackgroundColor(mode == Privacy.MODE_OFF ? 0xFFB45309 : 0xFF14532D);
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
        guardedLoad(url);
    }

    /** Loads only once the proxy is active; for Tor, first checks that Orbot is actually running. */
    private void guardedLoad(String url) {
        if (Privacy.proxyMode(prefs) != Privacy.MODE_TOR) { load(url); return; }
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean up = Privacy.torReachable();
            ui.post(() -> { if (up) load(url); else askForOrbot(url); });
        });
    }

    private void load(String url) {
        if (Privacy.proxyMode(prefs) == Privacy.MODE_BUILTIN && !TorManager.isReady()) {
            pendingUrl = url;   // Tor still starting: loads automatically when it's connected
            updateStatus();
            return;
        }
        String want = Privacy.proxyRule(prefs);
        if (activeRule == null || !activeRule.equals(want == null ? "" : want)) {
            pendingUrl = url;   // proxy not ready yet; applyPrivacy() will load it
            return;
        }
        Countries.Country c = Privacy.mimicCountry(prefs);
        if (c != null) {
            Map<String, String> h = new HashMap<>();
            h.put("Accept-Language", c.lang + "," + c.lang.substring(0, 2) + ";q=0.9,en;q=0.5");
            web.loadUrl(url, h);
        } else {
            web.loadUrl(url);
        }
    }

    /** Server list: pick which country websites should think you are in. */
    private void pickCountry() {
        if (Privacy.proxyMode(prefs) != Privacy.MODE_BUILTIN) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        Map<String, Integer> counts = TorManager.serverCounts;
        if (counts == null && TorManager.isReady()) TorManager.refreshServerCounts();
        final List<String> codes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        codes.add(""); labels.add("🌍 Any country (fastest)");
        codes.add("random"); labels.add("🎲 Random country");
        List<Countries.Country> list = new ArrayList<>(java.util.Arrays.asList(Countries.ALL));
        if (counts != null) {   // most servers first
            Collections.sort(list, (x, y) -> Integer.compare(
                    counts.containsKey(y.code) ? counts.get(y.code) : 0,
                    counts.containsKey(x.code) ? counts.get(x.code) : 0));
        }
        for (Countries.Country c : list) {
            Integer n = counts == null ? null : counts.get(c.code);
            String extra = counts == null ? "" : (n == null || n == 0) ? "   – no servers right now" : "   – " + n + " servers";
            codes.add(c.code);
            labels.add(c.flag() + "  " + c.name + extra);
        }
        String cur = Privacy.countryPref(prefs);
        int sel = Math.max(0, codes.indexOf(cur));
        int total = 0;
        if (counts != null) for (int n : counts.values()) total += n;
        new AlertDialog.Builder(this)
                .setTitle(counts == null ? "Appear to be in…" : "Appear to be in… (" + total + " exit servers live)")
                .setSingleChoiceItems(labels.toArray(new String[0]), sel, (d, which) -> {
                    String code = codes.get(which);
                    Integer n = counts == null ? null : counts.get(code);
                    if (counts != null && code.length() == 2 && (n == null || n == 0)) {
                        Toast.makeText(this, "No Tor servers in that country right now – pages won't load until one appears.",
                                Toast.LENGTH_LONG).show();
                    }
                    prefs.edit().putString("exit_country", code).apply();
                    TorManager.setCountry(code);
                    reloadWhenNewIp = true;
                    injectedScript = null;   // force re-inject with the new country's GPS / time zone
                    applyPrivacy();
                    d.dismiss();
                })
                .setNeutralButton("New IP, same country", (d, w) -> { reloadWhenNewIp = true; TorManager.newIp(); })
                .setNegativeButton("Close", null)
                .show();
    }

    private void askForOrbot(String url) {
        new AlertDialog.Builder(this)
                .setTitle("Tor is not running")
                .setMessage("To hide your IP address, OpenSearch sends everything through Tor using the "
                        + "free Orbot app.\n\n1. Open Orbot and tap Start / Connect.\n"
                        + "2. Come back and search again.\n\n"
                        + "Without Tor, nothing loads (so your real IP is never leaked by accident).")
                .setPositiveButton(isInstalled(Privacy.ORBOT_PKG) ? "Open Orbot" : "Install Orbot",
                        (d, w) -> openOrbot())
                .setNeutralButton("Try again", (d, w) -> guardedLoad(url))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean isInstalled(String pkg) {
        return getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    private void openOrbot() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(Privacy.ORBOT_PKG);
        if (launch != null) {
            // Ask Orbot to start Tor right away, then show it.
            sendBroadcast(new Intent("org.torproject.android.intent.action.START").setPackage(Privacy.ORBOT_PKG));
            startActivity(launch);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Privacy.ORBOT_PKG)));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + Privacy.ORBOT_PKG)));
        }
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
        if (Privacy.proxyMode(prefs) == Privacy.MODE_BUILTIN) {
            pm.getMenu().add(0, 6, 0, "Change country / server");
            pm.getMenu().add(0, 7, 0, "New IP address");
        }
        pm.getMenu().add(0, 5, 0, "Check my IP & location");
        pm.getMenu().add(0, 4, 0, "Privacy & settings");
        pm.setOnMenuItemClickListener(item -> {
            String cur = web.getUrl();
            switch (item.getItemId()) {
                case 1:
                    if (cur == null) return true;
                    if (Privacy.proxyMode(prefs) == Privacy.MODE_OFF) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(cur)));
                    } else {
                        new AlertDialog.Builder(this)
                                .setMessage("Your other browser does NOT use Tor/the proxy, so that site "
                                        + "will see your real IP address. Continue?")
                                .setPositiveButton("Open anyway", (d, w) ->
                                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(cur))))
                                .setNegativeButton("Cancel", null).show();
                    }
                    return true;
                case 6:
                    pickCountry();
                    return true;
                case 7:
                    reloadWhenNewIp = true;
                    TorManager.newIp();
                    return true;
                case 5:
                    home.setVisibility(View.GONE);
                    web.setVisibility(View.VISIBLE);
                    guardedLoad(Privacy.proxyMode(prefs) <= Privacy.MODE_TOR
                            ? "https://check.torproject.org/" : "https://browserleaks.com/ip");
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
    protected void onDestroy() {
        if (isFinishing()) {
            TorManager.setListener(null);
            TorManager.stop(this);
        }
        super.onDestroy();
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

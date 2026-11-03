package com.opensearch.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        Spinner proxyMode = spinner(R.id.proxy_mode, Privacy.PROXY_MODES, Privacy.proxyMode(prefs));
        EditText proxyCustom = findViewById(R.id.proxy_custom);
        View torBox = findViewById(R.id.tor_box);
        final String[] codes = new String[Countries.ALL.length + 2];
        String[] labels = new String[codes.length];
        codes[0] = ""; labels[0] = "🌍 Any country (fastest)";
        codes[1] = "random"; labels[1] = "🎲 Random country each start";
        for (int i = 0; i < Countries.ALL.length; i++) {
            codes[i + 2] = Countries.ALL[i].code;
            labels[i + 2] = Countries.ALL[i].flag() + "  " + Countries.ALL[i].name;
        }
        int curC = java.util.Arrays.asList(codes).indexOf(Privacy.countryPref(prefs));
        Spinner country = spinner(R.id.country, labels, Math.max(0, curC));
        CheckBox mimic = findViewById(R.id.mimic);
        mimic.setChecked(Privacy.mimic(prefs));
        Spinner location = spinner(R.id.location, Privacy.locationLabels(), prefs.getInt("location", Privacy.LOC_BLOCK));
        View customLoc = findViewById(R.id.custom_loc);
        EditText lat = findViewById(R.id.lat);
        EditText lon = findViewById(R.id.lon);
        Spinner region = spinner(R.id.region, Privacy.regionLabels(), prefs.getInt("region", 0));
        CheckBox blockRtc = findViewById(R.id.block_webrtc);
        CheckBox genericUa = findViewById(R.id.generic_ua);
        EditText searx = findViewById(R.id.searxng);
        EditText custom = findViewById(R.id.custom);
        CheckBox autoClear = findViewById(R.id.auto_clear);

        proxyCustom.setText(prefs.getString("proxy_custom", ""));
        lat.setText(prefs.getString("lat", ""));
        lon.setText(prefs.getString("lon", ""));
        blockRtc.setChecked(Privacy.blockWebRtc(prefs));
        genericUa.setChecked(Privacy.genericUa(prefs));
        searx.setText(prefs.getString("searxng", Engines.DEFAULT_SEARXNG));
        custom.setText(prefs.getString("custom", Engines.DEFAULT_CUSTOM));
        autoClear.setChecked(prefs.getBoolean("auto_clear", false));

        onSelect(proxyMode, pos -> {
            proxyCustom.setVisibility(pos == Privacy.MODE_CUSTOM ? View.VISIBLE : View.GONE);
            torBox.setVisibility(pos == Privacy.MODE_BUILTIN ? View.VISIBLE : View.GONE);
        });
        onSelect(location, pos -> customLoc.setVisibility(pos == Privacy.LOC_CUSTOM ? View.VISIBLE : View.GONE));

        findViewById(R.id.save).setOnClickListener(v -> {
            int mode = proxyMode.getSelectedItemPosition();
            String rule = proxyCustom.getText().toString().trim();
            if (mode == Privacy.MODE_CUSTOM && !rule.matches("^(socks5?|https?)://[^\\s/]+:\\d{1,5}$")) {
                proxyCustom.setError("Use socks5://host:port or http://host:port");
                return;
            }
            boolean countryChanged = !codes[country.getSelectedItemPosition()].equals(Privacy.countryPref(prefs));
            prefs.edit()
                    .putInt("ip_mode", mode)
                    .putString("exit_country", codes[country.getSelectedItemPosition()])
                    .putBoolean("mimic", mimic.isChecked())
                    .putString("proxy_custom", rule)
                    .putInt("location", location.getSelectedItemPosition())
                    .putString("lat", lat.getText().toString().trim())
                    .putString("lon", lon.getText().toString().trim())
                    .putInt("region", region.getSelectedItemPosition())
                    .putBoolean("block_webrtc", blockRtc.isChecked())
                    .putBoolean("generic_ua", genericUa.isChecked())
                    .putString("searxng", searx.getText().toString().trim())
                    .putString("custom", custom.getText().toString().trim())
                    .putBoolean("auto_clear", autoClear.isChecked())
                    .apply();
            if (countryChanged && TorManager.isRunning()) TorManager.setCountry(Privacy.countryPref(prefs));
            if (location.getSelectedItemPosition() == Privacy.LOC_CUSTOM && Privacy.fakeLocation(prefs) == null) {
                Toast.makeText(this, "Coordinates invalid: location will be blocked", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            }
            finish();
        });
    }

    private Spinner spinner(int id, String[] items, int selected) {
        Spinner sp = findViewById(id);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        sp.setSelection(Math.max(0, Math.min(selected, items.length - 1)));
        return sp;
    }

    private interface OnPick { void pick(int pos); }

    private void onSelect(Spinner sp, OnPick cb) {
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { cb.pick(pos); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }
}

package com.opensearch.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        EditText searx = findViewById(R.id.searxng);
        EditText custom = findViewById(R.id.custom);
        CheckBox autoClear = findViewById(R.id.auto_clear);
        searx.setText(prefs.getString("searxng", Engines.DEFAULT_SEARXNG));
        custom.setText(prefs.getString("custom", Engines.DEFAULT_CUSTOM));
        autoClear.setChecked(prefs.getBoolean("auto_clear", false));

        findViewById(R.id.save).setOnClickListener(v -> {
            prefs.edit()
                    .putString("searxng", searx.getText().toString().trim())
                    .putString("custom", custom.getText().toString().trim())
                    .putBoolean("auto_clear", autoClear.isChecked())
                    .apply();
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}

package com.anand.gamenetdoctor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Shows the diagnostic report with Copy and Share buttons. */
public class ReportActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = screen("Export report",
                "Copy or share this report and paste it into your chat with Claude for analysis. "
                        + "It has your network settings, the last test, the last device scan and any crash. "
                        + "It contains local IP addresses and device names, but no passwords.");

        final String report = Report.build(this);

        LinearLayout c = card(root, null);
        Button copy = button("COPY REPORT", ACCENT);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("Game Net Doctor report", report));
            Toast.makeText(this, "Report copied. Paste it in the chat.", Toast.LENGTH_LONG).show();
        });
        c.addView(copy);

        Button share = button("SHARE REPORT (WhatsApp, email, Drive…)", GOOD);
        share.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "Game Net Doctor report");
            send.putExtra(Intent.EXTRA_TEXT, report);
            startActivity(Intent.createChooser(send, "Share report"));
        });
        c.addView(share);

        if (Report.load(this, Report.CRASH_FILE) != null) {
            Button clear = button("Clear saved crash (after you've sent it)", SECONDARY);
            clear.setOnClickListener(v -> {
                Report.clearCrash(this);
                Toast.makeText(this, "Crash log cleared", Toast.LENGTH_SHORT).show();
                recreate();
            });
            c.addView(clear);
        }

        c.addView(text(report.length() + " characters", 12, MUTED, false));

        LinearLayout preview = card(root, "Preview");
        TextView t = text(report, 11, TEXT, false);
        t.setTypeface(Typeface.MONOSPACE);
        preview.addView(t);
    }
}

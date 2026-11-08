package com.anand.gamenetdoctor;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.LinkAddress;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Lists the devices connected to the same Wi-Fi network. */
public class DevicesActivity extends BaseActivity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status;
    private Button scanBtn;
    private boolean scanning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = screen("Devices on my Wi-Fi",
                "Every device below shares your internet. Phones and TVs streaming or downloading during your match cause lag.");

        LinearLayout c = card(root, null);
        scanBtn = button("SCAN NETWORK", ACCENT);
        scanBtn.setOnClickListener(v -> scan());
        c.addView(scanBtn);
        status = text("Takes about 30 seconds.", 13, MUTED, false);
        c.addView(status);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        LinearLayout tip = card(root, "Want to slow down or block a device?");
        tip.addView(text("Only your router can do that. Open Router control, log in to the router, and use "
                + "QoS / Bandwidth Control / Access Control to give your phone High priority or limit the other devices. "
                + "Note the device IPs listed here so you can find them in the router's list.", 13, MUTED, false));
        Button r = button("OPEN ROUTER CONTROL", SECONDARY);
        r.setOnClickListener(v -> startActivity(new Intent(this, RouterActivity.class)));
        tip.addView(r);

        scan();
    }

    private void scan() {
        if (scanning) return;
        LinkAddress self = ownIpv4(this);
        if (self == null) {
            status.setText("Not connected to Wi-Fi. Connect to your Wi-Fi and try again.");
            return;
        }
        scanning = true;
        scanBtn.setEnabled(false);
        scanBtn.setText("SCANNING…");
        list.removeAllViews();
        String selfIp = self.getAddress().getHostAddress();
        String gw = gateway(this);
        int prefix = self.getPrefixLength();

        new Thread(() -> {
            List<LanScanner.Device> devices = LanScanner.scan(selfIp, prefix, gw,
                    (done, total, found) -> {
                        if (done % 8 == 0 || done == total) {
                            ui.post(() -> status.setText("Scanning " + done + "/" + total + " addresses… " + found + " found"));
                        }
                    });
            ui.post(() -> show(devices));
        }).start();
    }

    private void show(List<LanScanner.Device> devices) {
        scanning = false;
        scanBtn.setEnabled(true);
        scanBtn.setText("SCAN AGAIN");
        int others = 0;
        for (LanScanner.Device d : devices) if (!d.isMe && !d.isRouter) others++;
        status.setText(devices.size() + " devices found · " + others + " besides your phone and router.\n"
                + "Some phones sleep and hide from scans. Scan again to catch them.");

        for (LanScanner.Device d : devices) {
            LinearLayout row = card(list, null);
            if (d.isMe || d.isRouter) {
                ((GradientDrawable) row.getBackground()).setStroke(dp(2), d.isMe ? GOOD : WARN);
            }
            String title = (d.isMe ? "📱 " : d.isRouter ? "📡 " : "• ") + d.type;
            row.addView(text(title, 16, TEXT, true));
            StringBuilder s = new StringBuilder();
            s.append("IP: ").append(d.ip);
            if (d.name != null) s.append("\nName: ").append(d.name);
            if (d.mac != null) s.append("\nMAC: ").append(d.mac);
            if (d.isMe) s.append("\nThis is the device to give HIGH priority in the router.");
            row.addView(text(s.toString(), 13, MUTED, false));
        }
    }
}

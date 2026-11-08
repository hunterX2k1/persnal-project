package com.anand.gamenetdoctor;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;

/** Shared look-and-feel and network helpers for all screens. */
public abstract class BaseActivity extends Activity {

    static final int BG = Color.parseColor("#12141A");
    static final int CARD = Color.parseColor("#1E222B");
    static final int ACCENT = Color.parseColor("#E5484D");
    static final int GOOD = Color.parseColor("#3DD68C");
    static final int WARN = Color.parseColor("#FFB224");
    static final int TEXT = Color.parseColor("#ECEDEE");
    static final int MUTED = Color.parseColor("#9BA1A6");
    static final int SECONDARY = Color.parseColor("#2B303B");

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Root scrolling column; call setContentView with the returned ScrollView's parent automatically. */
    LinearLayout screen(String title, String subtitle) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(32));
        scroll.addView(root);
        root.addView(text(title, 26, TEXT, true));
        if (subtitle != null) root.addView(text(subtitle, 14, MUTED, false));
        setContentView(scroll);
        return root;
    }

    TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.2f);
        t.setTextIsSelectable(!bold);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        parent.addView(c, lp);
        if (heading != null) c.addView(text(heading, 16, TEXT, true));
        return c;
    }

    Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        b.setMinHeight(dp(48));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------------------------------------------------------------- network helpers

    static LinkProperties linkProps(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        return n == null ? null : cm.getLinkProperties(n);
    }

    /** IPv4 default gateway (the router), or null. */
    static String gateway(Context c) {
        LinkProperties lp = linkProps(c);
        if (lp == null) return null;
        for (RouteInfo r : lp.getRoutes()) {
            InetAddress gw = r.getGateway();
            if (r.isDefaultRoute() && gw instanceof Inet4Address && !gw.isAnyLocalAddress()) {
                return gw.getHostAddress();
            }
        }
        return null;
    }

    /** This phone's IPv4 address with prefix on the active network, or null. */
    static LinkAddress ownIpv4(Context c) {
        LinkProperties lp = linkProps(c);
        if (lp == null) return null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address) return la;
        }
        return null;
    }
}

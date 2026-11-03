package com.opensearch.app;

import android.content.SharedPreferences;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

/**
 * Everything that hides who/where you are from the sites you visit:
 *  - IP address: all WebView traffic is forced through Tor (Orbot) or a proxy you choose.
 *    It "fails closed": if the proxy is down, pages don't load, they never fall back to your real IP.
 *  - Location: the app has no location permission at all. Pages asking for your location get
 *    either "denied" or a fake position you pick.
 *  - Leaks: WebRTC (which can expose your real IP even behind a proxy) is disabled, the
 *    browser identity is replaced with a generic one, and the app-name header is removed.
 *  - MAC address: never sent to websites (it only exists on your local Wi-Fi network),
 *    and Android hides it from apps anyway, so there is nothing to mask.
 */
final class Privacy {
    private Privacy() {}

    // ---- IP masking ----
    static final String[] PROXY_MODES = {
            "Tor via Orbot (recommended)",
            "My own proxy / VPN server",
            "Off – sites see my real IP",
    };
    static final int MODE_TOR = 0, MODE_CUSTOM = 1, MODE_OFF = 2;
    static final String TOR_SOCKS = "socks5://127.0.0.1:9050";
    static final String ORBOT_PKG = "org.torproject.android";

    static int proxyMode(SharedPreferences p) { return p.getInt("proxy_mode", MODE_TOR); }

    /** Proxy rule for the current mode, or null when off. */
    static String proxyRule(SharedPreferences p) {
        switch (proxyMode(p)) {
            case MODE_TOR: return TOR_SOCKS;
            case MODE_CUSTOM: {
                String r = p.getString("proxy_custom", "").trim();
                return r.isEmpty() ? null : r;
            }
            default: return null;
        }
    }

    /** True if something is listening on Orbot's SOCKS port. Call off the UI thread. */
    static boolean torReachable() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 9050), 700);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Search region (what country the engine thinks you're in) ----
    // {label, country code, DuckDuckGo region, language}
    static final String[][] REGIONS = {
            {"Engine decides", "", "", ""},
            {"United States", "us", "us-en", "en-US"},
            {"United Kingdom", "gb", "uk-en", "en-GB"},
            {"Canada", "ca", "ca-en", "en-CA"},
            {"Germany", "de", "de-de", "de-DE"},
            {"France", "fr", "fr-fr", "fr-FR"},
            {"Netherlands", "nl", "nl-nl", "nl-NL"},
            {"Switzerland", "ch", "ch-de", "de-CH"},
            {"Sweden", "se", "se-sv", "sv-SE"},
            {"Japan", "jp", "jp-jp", "ja-JP"},
            {"India", "in", "in-en", "en-IN"},
            {"Brazil", "br", "br-pt", "pt-BR"},
            {"Australia", "au", "au-en", "en-AU"},
    };

    static String[] regionLabels() {
        String[] l = new String[REGIONS.length];
        for (int i = 0; i < l.length; i++) l[i] = REGIONS[i][0];
        return l;
    }

    static String[] region(SharedPreferences p) {
        int i = p.getInt("region", 0);
        return REGIONS[(i >= 0 && i < REGIONS.length) ? i : 0];
    }

    // ---- Fake GPS location ----
    // {label, lat, lon}; index 0 = block, last = custom
    static final String[][] LOCATIONS = {
            {"Block – pages get no location", "", ""},
            {"New York, USA", "40.7128", "-74.0060"},
            {"London, UK", "51.5074", "-0.1278"},
            {"Toronto, Canada", "43.6532", "-79.3832"},
            {"Frankfurt, Germany", "50.1109", "8.6821"},
            {"Paris, France", "48.8566", "2.3522"},
            {"Amsterdam, Netherlands", "52.3676", "4.9041"},
            {"Zurich, Switzerland", "47.3769", "8.5417"},
            {"Stockholm, Sweden", "59.3293", "18.0686"},
            {"Tokyo, Japan", "35.6762", "139.6503"},
            {"Mumbai, India", "19.0760", "72.8777"},
            {"São Paulo, Brazil", "-23.5505", "-46.6333"},
            {"Sydney, Australia", "-33.8688", "151.2093"},
            {"Custom coordinates", "", ""},
    };
    static final int LOC_BLOCK = 0, LOC_CUSTOM = LOCATIONS.length - 1;

    static String[] locationLabels() {
        String[] l = new String[LOCATIONS.length];
        for (int i = 0; i < l.length; i++) l[i] = LOCATIONS[i][0];
        return l;
    }

    /** {lat, lon} or null when location is blocked / custom values are invalid. */
    static double[] fakeLocation(SharedPreferences p) {
        int i = p.getInt("location", LOC_BLOCK);
        if (i <= LOC_BLOCK || i > LOC_CUSTOM) return null;
        String la = i == LOC_CUSTOM ? p.getString("lat", "") : LOCATIONS[i][1];
        String lo = i == LOC_CUSTOM ? p.getString("lon", "") : LOCATIONS[i][2];
        try {
            double lat = Double.parseDouble(la.trim()), lon = Double.parseDouble(lo.trim());
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
            // Tiny random jitter (~100 m) so it doesn't look like a city-centre preset.
            lat += (Math.random() - 0.5) * 0.002;
            lon += (Math.random() - 0.5) * 0.002;
            return new double[]{lat, lon};
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Browser identity ----
    /** The "reduced" UA Chrome sends for every Android phone: hides model, Android version and WebView. */
    static final String GENERIC_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";

    static boolean genericUa(SharedPreferences p) { return p.getBoolean("generic_ua", true); }
    static boolean blockWebRtc(SharedPreferences p) { return p.getBoolean("block_webrtc", true); }

    /** Script injected into every page and frame before the page's own scripts run. */
    static String pageScript(SharedPreferences p) {
        double[] loc = fakeLocation(p);
        StringBuilder js = new StringBuilder("(function(){'use strict';\n");
        // Geolocation: fake position or permission denied. The real GPS is never reachable.
        js.append("var FAKE=").append(loc == null ? "null"
                : String.format(Locale.US, "{lat:%.6f,lon:%.6f}", loc[0], loc[1])).append(";\n");
        js.append("function answer(ok,err){setTimeout(function(){"
                + "if(FAKE){ok&&ok({coords:{latitude:FAKE.lat,longitude:FAKE.lon,accuracy:20+Math.random()*15,"
                + "altitude:null,altitudeAccuracy:null,heading:null,speed:null},timestamp:Date.now()});}"
                + "else if(err){err({code:1,message:'User denied Geolocation',PERMISSION_DENIED:1,"
                + "POSITION_UNAVAILABLE:2,TIMEOUT:3});}},80+Math.random()*200);}\n");
        js.append("var geo={getCurrentPosition:function(a,b){answer(a,b);},"
                + "watchPosition:function(a,b){answer(a,b);return Math.floor(Math.random()*1000)+1;},"
                + "clearWatch:function(){}};\n");
        js.append("try{Object.defineProperty(Navigator.prototype,'geolocation',"
                + "{get:function(){return geo;},configurable:true});}catch(e){}\n");
        if (blockWebRtc(p)) {
            js.append("['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel',"
                    + "'RTCSessionDescription','RTCIceCandidate'].forEach(function(n){"
                    + "try{Object.defineProperty(window,n,{value:undefined,writable:false,configurable:false});}"
                    + "catch(e){}});\n");
        }
        if (genericUa(p)) {
            js.append("try{Object.defineProperty(Navigator.prototype,'userAgentData',"
                    + "{get:function(){return undefined;},configurable:true});}catch(e){}\n");
        }
        js.append("})();");
        return js.toString();
    }
}

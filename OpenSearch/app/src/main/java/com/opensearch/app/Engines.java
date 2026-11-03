package com.opensearch.app;

import android.content.SharedPreferences;
import android.net.Uri;

/**
 * Search engines with every user-controllable filter (SafeSearch / adult filter /
 * family mode) switched OFF via URL parameters and cookies.
 */
final class Engines {
    static final String[] NAMES = {
            "DuckDuckGo",
            "Google",
            "Bing",
            "Brave Search",
            "Startpage",
            "Mojeek",
            "Yandex",
            "SearXNG (self-chosen instance)",
            "Custom URL",
    };

    static final int SEARXNG = 7;
    static final int CUSTOM = 8;

    static final String DEFAULT_SEARXNG = "https://searx.be";
    static final String DEFAULT_CUSTOM = "https://html.duckduckgo.com/html/?kp=-2&q=%s";

    private Engines() {}

    static String buildUrl(int engine, String query, SharedPreferences prefs) {
        String q = Uri.encode(query);
        switch (engine) {
            case 0: return "https://duckduckgo.com/?kp=-2&kz=-1&q=" + q;            // kp=-2 : safe search off
            case 1: return "https://www.google.com/search?safe=off&pws=0&q=" + q;    // safe=off, no personalisation
            case 2: return "https://www.bing.com/search?adlt=off&q=" + q;            // adlt=off : adult filter off
            case 3: return "https://search.brave.com/search?safesearch=off&q=" + q;
            case 4: return "https://www.startpage.com/sp/search?qadf=none&query=" + q; // qadf=none : family filter off
            case 5: return "https://www.mojeek.com/search?safe=0&q=" + q;
            case 6: return "https://yandex.com/search/?family=no&text=" + q;
            case SEARXNG: {
                String base = prefs.getString("searxng", DEFAULT_SEARXNG).trim();
                while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                return base + "/search?safesearch=0&q=" + q;                         // safesearch=0 : none
            }
            default: {
                String tpl = prefs.getString("custom", DEFAULT_CUSTOM).trim();
                if (!tpl.contains("%s")) tpl = tpl + "%s";
                return tpl.replace("%s", q);
            }
        }
    }

    /** Preference cookies some engines read instead of (or as well as) URL params. */
    static final String[][] COOKIES = {
            {"https://duckduckgo.com", "p=-2"},          // safe search off
            {"https://duckduckgo.com", "kp=-2"},
            {"https://www.bing.com", "SRCHHPGUSR=ADLT=OFF"},
            {"https://search.brave.com", "safesearch=off"},
            {"https://www.mojeek.com", "safe=0"},
    };
}

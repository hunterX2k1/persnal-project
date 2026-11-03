package com.opensearch.app;

import java.util.Locale;

/** Countries you can appear to be in. Each one has real Tor exit servers (most of the time). */
final class Countries {
    private Countries() {}

    static final class Country {
        final String code, name, tz, lang;
        final double lat, lon;
        Country(String code, String name, double lat, double lon, String tz, String lang) {
            this.code = code; this.name = name; this.lat = lat; this.lon = lon; this.tz = tz; this.lang = lang;
        }
        String flag() {
            String cc = code.toUpperCase(Locale.ROOT);
            return new String(Character.toChars(0x1F1E6 + cc.charAt(0) - 'A'))
                    + new String(Character.toChars(0x1F1E6 + cc.charAt(1) - 'A'));
        }
        /** DuckDuckGo region code, e.g. de-de, uk-en. */
        String ddg() {
            return ("gb".equals(code) ? "uk" : code) + "-" + lang.substring(0, 2);
        }
    }

    static final Country[] ALL = {
            c("us", "United States", 40.7128, -74.0060, "America/New_York", "en-US"),
            c("ca", "Canada", 43.6532, -79.3832, "America/Toronto", "en-CA"),
            c("mx", "Mexico", 19.4326, -99.1332, "America/Mexico_City", "es-MX"),
            c("br", "Brazil", -23.5505, -46.6333, "America/Sao_Paulo", "pt-BR"),
            c("ar", "Argentina", -34.6037, -58.3816, "America/Argentina/Buenos_Aires", "es-AR"),
            c("cl", "Chile", -33.4489, -70.6693, "America/Santiago", "es-CL"),
            c("gb", "United Kingdom", 51.5074, -0.1278, "Europe/London", "en-GB"),
            c("ie", "Ireland", 53.3498, -6.2603, "Europe/Dublin", "en-IE"),
            c("fr", "France", 48.8566, 2.3522, "Europe/Paris", "fr-FR"),
            c("de", "Germany", 50.1109, 8.6821, "Europe/Berlin", "de-DE"),
            c("nl", "Netherlands", 52.3676, 4.9041, "Europe/Amsterdam", "nl-NL"),
            c("be", "Belgium", 50.8503, 4.3517, "Europe/Brussels", "nl-BE"),
            c("lu", "Luxembourg", 49.6116, 6.1319, "Europe/Luxembourg", "fr-LU"),
            c("ch", "Switzerland", 47.3769, 8.5417, "Europe/Zurich", "de-CH"),
            c("at", "Austria", 48.2082, 16.3738, "Europe/Vienna", "de-AT"),
            c("it", "Italy", 45.4642, 9.1900, "Europe/Rome", "it-IT"),
            c("es", "Spain", 40.4168, -3.7038, "Europe/Madrid", "es-ES"),
            c("pt", "Portugal", 38.7223, -9.1393, "Europe/Lisbon", "pt-PT"),
            c("se", "Sweden", 59.3293, 18.0686, "Europe/Stockholm", "sv-SE"),
            c("no", "Norway", 59.9139, 10.7522, "Europe/Oslo", "nb-NO"),
            c("fi", "Finland", 60.1699, 24.9384, "Europe/Helsinki", "fi-FI"),
            c("dk", "Denmark", 55.6761, 12.5683, "Europe/Copenhagen", "da-DK"),
            c("is", "Iceland", 64.1466, -21.9426, "Atlantic/Reykjavik", "is-IS"),
            c("pl", "Poland", 52.2297, 21.0122, "Europe/Warsaw", "pl-PL"),
            c("cz", "Czechia", 50.0755, 14.4378, "Europe/Prague", "cs-CZ"),
            c("sk", "Slovakia", 48.1486, 17.1077, "Europe/Bratislava", "sk-SK"),
            c("hu", "Hungary", 47.4979, 19.0402, "Europe/Budapest", "hu-HU"),
            c("ro", "Romania", 44.4268, 26.1025, "Europe/Bucharest", "ro-RO"),
            c("bg", "Bulgaria", 42.6977, 23.3219, "Europe/Sofia", "bg-BG"),
            c("gr", "Greece", 37.9838, 23.7275, "Europe/Athens", "el-GR"),
            c("md", "Moldova", 47.0105, 28.8638, "Europe/Chisinau", "ro-MD"),
            c("ua", "Ukraine", 50.4501, 30.5234, "Europe/Kyiv", "uk-UA"),
            c("lv", "Latvia", 56.9496, 24.1052, "Europe/Riga", "lv-LV"),
            c("lt", "Lithuania", 54.6872, 25.2797, "Europe/Vilnius", "lt-LT"),
            c("ee", "Estonia", 59.4370, 24.7536, "Europe/Tallinn", "et-EE"),
            c("rs", "Serbia", 44.7866, 20.4489, "Europe/Belgrade", "sr-RS"),
            c("ru", "Russia", 55.7558, 37.6173, "Europe/Moscow", "ru-RU"),
            c("tr", "Turkey", 41.0082, 28.9784, "Europe/Istanbul", "tr-TR"),
            c("il", "Israel", 32.0853, 34.7818, "Asia/Jerusalem", "he-IL"),
            c("ae", "United Arab Emirates", 25.2048, 55.2708, "Asia/Dubai", "ar-AE"),
            c("in", "India", 19.0760, 72.8777, "Asia/Kolkata", "en-IN"),
            c("sg", "Singapore", 1.3521, 103.8198, "Asia/Singapore", "en-SG"),
            c("hk", "Hong Kong", 22.3193, 114.1694, "Asia/Hong_Kong", "zh-HK"),
            c("tw", "Taiwan", 25.0330, 121.5654, "Asia/Taipei", "zh-TW"),
            c("jp", "Japan", 35.6762, 139.6503, "Asia/Tokyo", "ja-JP"),
            c("kr", "South Korea", 37.5665, 126.9780, "Asia/Seoul", "ko-KR"),
            c("my", "Malaysia", 3.1390, 101.6869, "Asia/Kuala_Lumpur", "ms-MY"),
            c("th", "Thailand", 13.7563, 100.5018, "Asia/Bangkok", "th-TH"),
            c("id", "Indonesia", -6.2088, 106.8456, "Asia/Jakarta", "id-ID"),
            c("vn", "Vietnam", 21.0278, 105.8342, "Asia/Ho_Chi_Minh", "vi-VN"),
            c("au", "Australia", -33.8688, 151.2093, "Australia/Sydney", "en-AU"),
            c("nz", "New Zealand", -36.8485, 174.7633, "Pacific/Auckland", "en-NZ"),
            c("cr", "Costa Rica", 9.9281, -84.0907, "America/Costa_Rica", "es-CR"),
            c("pe", "Peru", -12.0464, -77.0428, "America/Lima", "es-PE"),
            c("hr", "Croatia", 45.8150, 15.9819, "Europe/Zagreb", "hr-HR"),
            c("cy", "Cyprus", 35.1856, 33.3823, "Asia/Nicosia", "el-CY"),
            c("ph", "Philippines", 14.5995, 120.9842, "Asia/Manila", "en-PH"),
            c("za", "South Africa", -26.2041, 28.0473, "Africa/Johannesburg", "en-ZA"),
    };

    private static Country c(String code, String name, double lat, double lon, String tz, String lang) {
        return new Country(code, name, lat, lon, tz, lang);
    }

    static Country byCode(String code) {
        if (code == null) return null;
        for (Country c : ALL) if (c.code.equals(code)) return c;
        return null;
    }
}

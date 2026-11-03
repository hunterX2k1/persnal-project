# OpenSearch – unfiltered search for Android

A small Android app (≈20 KB, no ads, no trackers, no third‑party libraries) that sends your
query to the engine of your choice with **SafeSearch / adult / family filters turned off**.

**Download:** [`apk/OpenSearch.apk`](../apk/OpenSearch.apk) → copy to phone → open → allow
"Install unknown apps".

## Engines (filters off)
| Engine | How the filter is disabled |
|---|---|
| DuckDuckGo | `kp=-2` + cookie |
| Google | `safe=off`, `pws=0` (no personalisation) |
| Bing | `adlt=off` + `SRCHHPGUSR=ADLT=OFF` cookie |
| Brave Search | `safesearch=off` + cookie |
| Startpage | `qadf=none` |
| Mojeek | `safe=0` |
| Yandex | `family=no` |
| SearXNG | `safesearch=0`, instance URL set in Settings (self‑host for full control) |
| Custom URL | any template with `%s`, set in Settings |

Other features: type a web address to open it directly, "Share → OpenSearch" from any app,
open/share current page, one‑tap clear history & cookies, optional auto‑clear on exit.

**Limit:** the app removes every filter *you* can control, but each engine still applies its own
legal removals (e.g. court/DMCA takedowns, CSAM). For the most control, run your own
[SearXNG](https://docs.searxng.org) instance and point the app at it.

## Build
```bash
ANDROID_HOME=/path/to/android-sdk ./build-apk.sh   # needs build-tools 35 + platform 34, JDK 17+
```
Or open this folder in Android Studio (Gradle project included).
`signing.jks` (password `android`) keeps the signature stable so new builds install as updates.

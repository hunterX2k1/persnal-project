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

## Privacy: hide IP, location and device
| What sites could learn | How OpenSearch hides it |
|---|---|
| **IP address** (→ your city/ISP) | All traffic forced through **Tor via Orbot** (default) or your own SOCKS/HTTP proxy. *Fail‑closed*: if Tor/proxy is down nothing loads – no silent leak. |
| **GPS location** | App has **no location permission**. Pages get "denied" or a **fake location** (12 city presets or custom coordinates, with small random jitter). |
| **Country of search** | "Pretend to search from" sets the engine region (`gl`/`hl`, `cc`, `kl`, `language`). |
| **Real IP via WebRTC** | `RTCPeerConnection` etc. removed from every page. |
| **Phone model / Android version** | Generic Chrome user agent, `userAgentData` hidden, `X-Requested-With` app header removed. |
| **MAC address** | Never sent to websites (it doesn't leave your Wi‑Fi network; Android hides it from apps too). Nothing to mask. |

Setup: install **Orbot** (Tor Project, Play Store / F‑Droid) → tap **Start**. Search in OpenSearch.
Use ⋮ → *Check my IP & location* to verify (check.torproject.org). To appear in a specific
country, pick the exit country in Orbot's settings and set the same fake location + region.

Tips: Google often shows CAPTCHAs to Tor users – DuckDuckGo, Brave, Startpage, Mojeek or the
**DuckDuckGo .onion** engine work much better. Accounts you log into still identify you.

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

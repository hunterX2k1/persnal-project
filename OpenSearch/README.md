# OpenSearch – unfiltered search for Android

A small Android app (≈8 MB incl. Tor, no ads, no trackers) that sends your
query to the engine of your choice with **SafeSearch / adult / family filters turned off**.

**Download:** [`apk/OpenSearch.apk`](../apk/OpenSearch.apk) → copy to phone → open → allow
"Install unknown apps". Needs Android 7.0+ on an ARM phone (virtually all phones).

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

## Appear to be in another country (v3)
OpenSearch has **Tor built in** (no extra app). Your searches go through 3 volunteer servers;
the last one – the *exit server* – is in the country **you choose**, and its IP is the only IP
search engines see.

* **Server list:** tap the green bar → pick from 58 countries. Live server counts come from the
  Tor network directory (e.g. US ≈1000, Netherlands ≈600, Germany ≈370, Sweden ≈200 exit servers),
  sorted by most servers first. Also *Any country (fastest)* and *Random country*.
* **New IP, same country** – one tap for a different exit server.
* **Mimic the country** (on by default): fake GPS (in that country's big city), time zone,
  browser language, `Accept-Language` header and search-engine region all match the chosen country.
* The status bar shows the country and the **IP sites currently see** (from check.torproject.org).

Why not just "type a fake IP"? An IP address is where the answer gets sent – a made-up one means
results never come back. Routing through a real server in that country is how VPNs and Tor do it.

## Privacy: hide IP, location and device
| What sites could learn | How OpenSearch hides it |
|---|---|
| **IP address** (→ your city/ISP) | Built‑in Tor with country choice (default), Tor via Orbot, or your own SOCKS/HTTP proxy. *Fail‑closed*: while Tor is starting or down, nothing loads – no silent leak. |
| **GPS location** | App has **no location permission**. Pages get a location matching your Tor country, a fake city you pick, or "denied". |
| **Time zone / language** | Spoofed to the chosen country (JS `Intl`, `getTimezoneOffset`, `navigator.language`). |
| **Real IP via WebRTC** | `RTCPeerConnection` etc. removed from every page. |
| **Phone model / Android version** | Generic Chrome user agent, `userAgentData` hidden, `X-Requested-With` app header removed. |
| **MAC address** | Never sent to websites (it doesn't leave your Wi‑Fi network; Android hides it from apps too). |

Tips: Google often shows CAPTCHAs to Tor users – DuckDuckGo, Brave, Startpage, Mojeek or the
**DuckDuckGo .onion** engine work much better. Accounts you log into still identify you.

Other features: type a web address to open it directly, "Share → OpenSearch" from any app,
open/share current page, one‑tap clear history & cookies, optional auto‑clear on exit.

**Limit:** the app removes every filter *you* can control, but each engine still applies its own
legal removals (e.g. court/DMCA takedowns, CSAM). For the most control, run your own
[SearXNG](https://docs.searxng.org) instance and point the app at it.

## Build
```bash
ANDROID_HOME=/path/to/android-sdk ./build-apk.sh   # needs build-tools 35 + platform 34, JDK 17+; downloads Tor into libs-dl/
```
Or open this folder in Android Studio (Gradle project included).
`signing.jks` (password `android`) keeps the signature stable so new builds install as updates.

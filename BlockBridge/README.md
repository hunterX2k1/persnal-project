# BlockBridge — uBlock Origin's filtering engine for your whole Android phone

BlockBridge ports the network-filtering part of **uBlock Origin** (the classic MV2 extension
Chrome dropped) into an Android app. It runs as a local DNS filter, so it works with your
existing Chrome and every other app, and Chrome keeps updating normally.

## What was taken from uBlock Origin

| uBO feature | In BlockBridge |
|---|---|
| Filter list catalogue (`assets/assets.json`) | Same 71 lists, same 9 on by default (uBlock filters ×5, EasyList, EasyPrivacy, Online Malicious URL Blocklist, Peter Lowe's) |
| Static filter syntax | `\|\|host^`, `@@\|\|host^`, hosts files, plain hostnames, `$important`, `$badfilter`, option aliases, `$reason` |
| `!#if` / `!#else` / `!#endif` preprocessor | Evaluated as uBO on Chromium for Android (`env_chromium`, `env_mobile`) |
| Match order | Trusted site → `$important` → exception → block |
| My filters / Trusted sites | Same tabs, same syntax; restore reads uBO backup `userFilters` / `whitelist` |
| Logger | Every lookup, which list blocked it, tap to allow/block/trust |
| List auto-update | Honors each list's `! Expires:` header, like uBO |
| "used out of total" per list | Shown under each list |

## What can't move out of the browser

uBO also has cosmetic filters (`##`), scriptlets (`##+js`, used for YouTube ads) and
URL-path rules (`/ads.js`, `$domain=`, `$redirect`). These need to run *inside the web page*,
which only a browser extension can do — no Android app can reach into Chrome's pages.
On the default lists that's roughly 43k cosmetic rules and 18k path rules; BlockBridge applies the
~99k hostname rules. The "used out of total" counter under each list shows exactly how many.

If a site's video player breaks (some use `imasdk.googleapis.com`, which uBO swaps for a
harmless stand-in that DNS can't provide), open **Logger**, tap the red entry → **Allow**.

## How it works

```
Chrome ──"where is ads.tracker.com?"──► BlockBridge (on your phone, uBO rules)
                                          │ blocked → answers 0.0.0.0 → ad never loads
                                          │ allowed → asks real DNS (1.1.1.1) → normal page
```

Android's VPN feature is used **only to capture DNS lookups**. There is no external VPN server,
and page/video traffic never passes through the app.

## Full-traffic mode (default)

With **Settings › Send all app traffic through BlockBridge** on, every connection from every app
goes through BlockBridge:

```
any app ─► VPN tunnel ─► hev-socks5-tunnel (native, MIT) ─► BlockBridge SOCKS5 relay ─► internet
                                                            │ DNS to *any* server → uBO filter
                                                            │ connection to a blocked host → refused
```

* Apps that skip Android's DNS (hard-coded 8.8.8.8, DNS over TCP) are filtered too.
* Connections to hosts the filters block are refused even if the app cached the IP.
* The status line shows open connections and data used.
* Turn the switch off for DNS-only mode (lowest battery use).

The native engine lives in the `app/src/main/jni/hev-socks5-tunnel` git submodule — clone with
`git clone --recursive`, or run `git submodule update --init --recursive`.

## VPN: browse from another country

**VPN tab › Use VPN server.** Traffic still passes BlockBridge's filters first (ads stay blocked),
then leaves the phone through a WireGuard server, so websites see that server's IP and country.
DNS goes through the tunnel too.

```
app ─► BlockBridge relay (uBO filters) ─► bbwg (WireGuard, wg/) ═══encrypted═══► server ─► internet
```

* **Kill switch:** if the server can't be reached, traffic is paused rather than sent from your real IP.
* **Free servers:** Proton VPN's free plan — account.protonvpn.com › Downloads › WireGuard
  configuration › Android › a free server › Download, then *Import .conf file*.
* **Your own free server:** Oracle Cloud *Always Free* + `server/setup-wireguard-server.sh`,
  then *Paste config*.
* **Check my location** shows the IP and country websites see.
* Google also uses GPS/Wi-Fi location, SIM country and your Play payments profile — turn Location
  off while using the VPN; the Play Store country doesn't change with a VPN.

`wg/` is BlockBridge's own Go helper on top of the official `golang.zx2c4.com/wireguard`
(userspace WireGuard + gVisor netstack), exposed to the app as a local SOCKS5 server. Gradle
cross-compiles it with the NDK when Go is installed; `go test -race ./...` in `wg/` runs a full
encrypted tunnel against a local WireGuard peer.

## App exceptions

**Filters › Apps**: apps you switch on there skip BlockBridge entirely (no ad blocking, no VPN), so
games can show their reward ads. The tunnel is rebuilt automatically when the list changes.

## Free VPN exits: Tor

**VPN › Add Tor** runs Tor inside the app (Guardian Project `tor-android` 0.4.9.5, the newest
release that builds with this project's Android Gradle plugin) with an optional exit country.
Ad blocking still applies; DNS goes through Tor's DNSPort. Tor carries TCP only, is slower than a
VPN, and some sites show CAPTCHAs to Tor users.

## Install

1. Download `BlockBridge.apk` (GitHub › Releases › *BlockBridge (latest build)*, or the Actions artifact).
2. Open it on the phone → allow "Install unknown apps" for your browser/file manager when asked.
3. Open BlockBridge → **Turn on** → accept the VPN prompt. uBO's default lists download on first start.

## Make sure Chrome uses it

* Chrome › Settings › Privacy and security › **Use secure DNS** → "Use your current service provider"
  (a custom secure-DNS provider in Chrome bypasses BlockBridge).
* Android Settings › **Private DNS** → Off or Automatic.
* Keep it always on: Android Settings › Network › VPN › BlockBridge › ⚙ › **Always-on VPN**.

## Build locally

```
cd BlockBridge
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew testDebugUnitTest assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

`app/blockbridge.jks` is a signing key committed on purpose so local and GitHub builds can
update each other without uninstalling. It's for personal sideloading only — don't reuse it for
anything you publish.

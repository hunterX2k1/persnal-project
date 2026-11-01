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

# BlockBridge — uBlock-style ad blocking for Chrome on Android

BlockBridge sits between your phone and the internet as a **local DNS filter**.
It works with your existing Chrome (and every other app) without modifying Chrome,
so Chrome keeps updating normally from the Play Store.

## How it works

```
Chrome ──"where is ads.tracker.com?"──► BlockBridge (on your phone)
                                          │ on blocklist? → answers 0.0.0.0 → ad never loads
                                          │ not listed?  → asks real DNS (1.1.1.1) → normal page
```

* Uses Android's VPN feature **only to capture DNS lookups** — nothing leaves your phone
  except the normal lookups to the DNS server you choose. No external VPN server.
* Page/video/download traffic does **not** go through the app, so speed and battery are unaffected.
* Blocklists are the ones uBlock Origin uses (AdGuard DNS filter built from EasyList/EasyPrivacy,
  Peter Lowe's list, URLhaus malware list) plus optional StevenBlack and HaGeZi PRO.
  They refresh automatically every 3 days.

## Install

1. Download `BlockBridge.apk` (GitHub › Releases › *BlockBridge (latest build)*, or the Actions artifact).
2. Open it on the phone → allow "Install unknown apps" for your browser/file manager when asked.
3. Open BlockBridge → **Start** → accept the VPN prompt.

## Make sure Chrome uses it

* Chrome › Settings › Privacy and security › **Use secure DNS** → "Use your current service provider"
  (a custom secure-DNS provider in Chrome bypasses BlockBridge).
* Android Settings › **Private DNS** → Off or Automatic.
* Keep it always on: Android Settings › Network › VPN › BlockBridge › ⚙ › **Always-on VPN**.

## Differences from uBlock Origin

| | uBlock Origin | BlockBridge |
|---|---|---|
| Ad/tracker servers | ✅ | ✅ |
| Malware/phishing domains | ✅ | ✅ |
| Hiding empty ad boxes (cosmetic filters) | ✅ | ❌ |
| YouTube video ads (same domain as videos) | ✅ | ❌ |
| Works in all apps, not just the browser | ❌ | ✅ |

If a site breaks, open BlockBridge → **Recent requests** → tap the red domain → **Allow it**.

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

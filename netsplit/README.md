# NetSplit – per-app bandwidth limiter for Android (no root)

Keeps an online game (e.g. Shadow Fight 4: Arena) smooth while YouTube plays in the
background on a single mobile connection (Jio 5G / BSNL 4G …).

## Why games lag next to YouTube
YouTube downloads video in big bursts. On mobile data those bursts fill the connection's
queue, so the game's small, time-critical packets wait behind them → "network error" /
endless loading. Capping YouTube *below* your link speed keeps the queue empty, so the game's
packets go through immediately.

## Modes
* **Priority mode (recommended for gaming)** – pick your game and the background app.
  The game is **not routed through the VPN at all** (zero added lag). The background app
  is capped at a % of your speed (default 30 %).
* **Split mode** – both apps are capped at their share, e.g. 50 / 50 or 70 / 30.

Limits can be changed while it runs; they apply instantly.

## How it works
Android doesn't let normal apps throttle other apps, so NetSplit uses a **local VPN**
(`VpnService`) that only carries the limited app(s). A small userspace TCP/UDP forwarder
(`app/src/main/java/com/netsplit/app/vpn/`) terminates their connections and re-sends the traffic
through normal sockets, reading from those sockets only as fast as a per-app token bucket allows.
The real server then slows down (TCP flow control), so the app truly uses less of your link.
No data leaves your phone except the app's own traffic; there is no remote server.

Optionally NetSplit blocks QUIC (UDP 443) for limited apps so YouTube falls back to TCP,
which makes the limit exact.

## Install
Download `release/NetSplit-v1.0.apk` to your phone, open it and allow "install unknown apps".
Requires Android 10+.

## Using it
1. Open NetSplit → it pre-selects Shadow Fight and YouTube if installed (or tap **Choose**).
2. Tap **Measure** (with nothing else downloading) or type your speed in Mbps.
3. Priority mode → set the background limit (e.g. 25–35 %). The hint shows which video quality fits.
4. Tap **Start** and accept the VPN prompt. A key icon appears in the status bar.
5. Open YouTube, then your game.

Notes: Only one VPN can be active on Android at a time. Mobile speed varies a lot – if the game
still lags, lower the background limit.

## Build
```
./gradlew :app:assembleRelease          # APK in app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest        # packet/token-bucket unit tests
NETSPLIT_TUN_TEST=1 ./gradlew :app:testDebugUnitTest   # + end-to-end tests through a real Linux TUN (root)
```

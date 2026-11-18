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

## Auto mode (live speed, on by default)
Mobile speed changes all the time, so a fixed number is always wrong. With **Auto** on, NetSplit
pings your connection twice a second (tiny DNS queries to your carrier's DNS / 1.1.1.1 / 8.8.8.8 that
travel the same mobile queue as the game's packets):

* Ping rises above its normal level → YouTube is filling the line → its limit is cut right away (×0.7).
* Ping stays low and YouTube wants more → its limit is raised (fast at first, gently near the level
  that caused lag last time), so it follows the speed back up.
* The live connection speed is estimated and shown in the app and notification.

**Game boost** (priority + auto, on by default): the game is also passed through NetSplit, *never
limited*, only so its live traffic can be seen (Android doesn't let apps read another app's live usage).
When the game asks for more (match loading, asset download), NetSplit keeps 1.5–2× its recent
use free and cuts YouTube at once; when the game calms down, YouTube gets the speed back.
Measured cost: +0.15 ms per round trip. Simulated: game jumps to 3 Mbps on a 10 Mbps line →
worst extra ping 89 ms with boost vs 336 ms without.

**Game protection** (priority mode) sets how much extra ping is tolerated: Balanced ≈ 60 ms,
Strong ≈ 35 ms (default), Maximum ≈ 22 ms. In split mode the split % is applied to the live speed.

Simulated check (`AutoTunerTest`): link 20 → 4 → 40 Mbps; extra ping stayed ≈ 6–10 ms while YouTube
got 17.7 / 3.3 / 33.4 Mbps.

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
Download `release/NetSplit-v1.2.apk` to your phone, open it and allow "install unknown apps".
Requires Android 10+.

## Using it
1. Open NetSplit → it pre-selects Shadow Fight and YouTube if installed (or tap **Choose**).
2. Leave **Auto** on (or turn it off and Measure / type a fixed speed).
3. Priority mode → pick Game protection (Strong is a good start).
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

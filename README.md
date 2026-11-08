# Game Net Doctor (Android)

A small Android app that finds **why online games like Shadow Fight Arena show "unstable connection"
on Wi-Fi while YouTube works fine**, and helps fix it.

**Download:** [`release/GameNetDoctor.apk`](release/GameNetDoctor.apk) (Android 7.0+, ~24 KB, no ads, no tracking).

## Why YouTube works but the game doesn't
YouTube uses TCP and downloads video seconds ahead, so short dropouts and lag spikes are hidden.
A live PvP match sends small packets many times a second, usually over UDP. It needs **low ping, low jitter
and almost 0% packet loss**. One second of bad Wi-Fi is enough for the game to say "unstable".

## What the app does
1. **Shows your connection:** Wi-Fi band (2.4 / 5 GHz), signal strength, link speed, VPN, battery saver,
   data saver and Private DNS.
2. **Runs a 30-second test:**
   - phone ↔ router ping (tests Wi-Fi quality)
   - internet over TCP (what YouTube uses)
   - internet over UDP (what games use)
   Each one reports average ping, jitter, maximum and packet loss.
3. **Diagnosis:** based on the numbers, tells you if the problem is weak Wi-Fi, 2.4 GHz interference,
   a router or ISP that blocks or throttles UDP, ISP congestion, a VPN, or battery/data saver,
   and gives step-by-step fixes.
4. **Game Mode:** a foreground service that holds Android's high-performance and low-latency Wi-Fi locks
   so the Wi-Fi radio doesn't drop into power-save between packets. This removes the random lag spikes
   caused by power saving. It works best on Android 13 and older. Newer Android versions limit this lock.
5. **Devices on my Wi-Fi:** scans the network and lists every connected device with IP, name and type
   (iPhone, Android, Windows PC, TV/Chromecast, camera, printer…). Names come from mDNS, NetBIOS and the router's DNS.
   Android 10+ hides other devices' MAC addresses from apps, so MACs only show on Android 9 and older.
6. **Router control (prioritise my phone):** detects the router brand (TP-Link, JioFiber, Airtel, Netgear, ASUS,
   D-Link, Tenda, Xiaomi…), checks whether UPnP is on, opens the router admin page inside the app, and shows
   brand-specific steps to give your phone High priority (QoS), limit other devices' bandwidth, reserve your IP,
   enable UPnP and block unknown devices.
   Routers have no standard way for an app to change these settings, so you make the change yourself
   in the router page; the app tells you exactly where to go.
7. **Export report / crash report:** one tap builds a text report (phone & network details, VPN status,
   last speed test, last router check, last device scan with the *reason* each device was detected,
   probe error statistics, and the last crash stack trace if the app crashed). Copy or share it for analysis.
8. **One-tap shortcuts** to Wi-Fi, SFA app settings (battery: Unrestricted), battery saver, data saver,
   Private DNS and VPN settings.

> No app can make your internet line faster or fix a bad route between your ISP and the game server.
> Apps that claim to "boost ping" mostly just clear RAM. This app measures where the problem is
> and fixes what can be fixed on the phone.

## Build
Requires JDK 17+ and the Android SDK (platform 35):
```
gradle assembleDebug     # output: app/build/outputs/apk/debug/app-debug.apk
```

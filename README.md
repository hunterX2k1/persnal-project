# Call Keeper

Automatic call recording on a **non-rooted** Android phone (built for a Moto Edge 60 Pro), recording **both sides** of every call.

> **Call Keeper is a FORK (edited clone) of [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) by kitsumed.**
> It is not the original app. Licensed under GPL-3.0 with the original project's additional terms; see [`call-keeper/LICENSE`](call-keeper/LICENSE).

- APK: [`apk/CallKeeper-2.0.0-callkeeper.1.apk`](apk/CallKeeper-2.0.0-callkeeper.1.apk)
- Source: [`call-keeper/`](call-keeper/), based on upstream release `v2.0.0`

## What's changed from the original

| Change | Why |
|---|---|
| **Recordings tab** (new): every recording with contact name, number, incoming/outgoing, date, duration; search, play, share, delete | The original has no in-app list, and Google Phone's list loses contact names |
| **Contact names stored per recording**, and re-looked-up live (matches numbers with or without `+91`) | Names show even if the contact was saved or renamed later |
| **Auto-record every call ON by default** (incoming and outgoing, all contacts) | No more adding contacts one by one |
| File names now include the contact name: `{date}_{caller_name}_{phone_number}_{direction}` | Easy to find recordings in a file manager too |
| **Red warning banner** when Shizuku isn't running | You know straight away when calls aren't being recorded (e.g. after a restart) |
| New app name and package ID (`com.personal.callkeeper`), plus a fork notice | Required by the original license; it can be installed next to the original app |

## One-time setup

1. **Developer options**: Settings → About phone → tap **Build number** 7 times.
2. **Install Shizuku (thedjchi fork)** from <https://github.com/thedjchi/Shizuku/releases/latest>.
   This fork can **start itself after a reboot** and **restart itself if it crashes**. If you installed the original Shizuku, uninstall it first.
3. **Start Shizuku** (on Wi-Fi): Settings → System → Developer options → turn on **USB debugging** and **Wireless debugging** → in Shizuku tap *Start via Wireless debugging → Pairing* → enter the pairing code in the notification → **Start**.
4. In Shizuku's settings, turn on **Start on boot** and **Watchdog**.
5. **Install Call Keeper**: copy the APK to the phone, open it, and allow "Install unknown apps" when asked.
6. Open Call Keeper, accept the disclaimer and grant every permission it lists (Shizuku, phone, call log, contacts, notifications, battery).
7. **Settings tab → choose a recordings folder** (e.g. `Recordings/Calls`).
8. Make a **test call** and check that both voices come through in the **Recordings** tab.

## Good to know

- **After a restart**, Shizuku starts by itself **once the phone connects to Wi-Fi**. Until then, calls aren't recorded and Call Keeper shows a red banner.
- Keep **Developer options, USB debugging and Wireless debugging ON**; turning them off stops Shizuku.
- Some banking/UPI apps complain while Developer options are on. Call Keeper's Settings has *Shizuku* security toggles that turn Shizuku on only while recording, which helps with this.
- Recording quality depends on Motorola's audio drivers supporting the call-audio source. If a test call is silent or one-sided, try another *Audio source* in Settings.
- Recordings made **before** installing Call Keeper (or by the original app) appear in the list using their file name, without contact details.
- Recording laws vary; some places require telling the other person.

## Building it yourself

Needs JDK 21, Android SDK platform 37 and Gradle 9.8.2:

```sh
cd call-keeper
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleRelease   # then sign app/build/outputs/apk/release/app-release-unsigned.apk with apksigner
```

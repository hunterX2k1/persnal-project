# Signing key

`debug.keystore` is the key every Vault and Atlas APK has been signed with, so builds made with it
install over the existing apps as updates.

- Store password: `android`
- Key alias: `androiddebugkey`
- Key password: `android`
- SHA-256: `9A:2C:B0:7C:D8:5F:43:FE:AA:4C:6D:60:23:52:18:03:E9:53:A1:BF:C4:2E:6F:47:67:DA:2F:CE:13:01:87:15`

Both apps' `build.gradle.kts` use it for debug and release builds. It is a debug key (standard passwords):
fine for personal sideloaded apps, not meant for a Play Store release.

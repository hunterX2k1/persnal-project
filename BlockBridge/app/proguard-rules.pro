# hev-socks5-tunnel looks these up by name in JNI_OnLoad (RegisterNatives).
-keep class com.blockbridge.HevTunnel {
    native <methods>;
}

# IPtProxy (gomobile): native code looks these classes up by name.
-keep class go.** { *; }
-keep class IPtProxy.** { *; }

# hev-socks5-tunnel looks these up by name in JNI_OnLoad (RegisterNatives).
-keep class com.blockbridge.HevTunnel {
    native <methods>;
}

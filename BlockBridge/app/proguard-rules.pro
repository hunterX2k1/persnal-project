# hev-socks5-tunnel looks these up by name in JNI_OnLoad (RegisterNatives).
-keep class com.blockbridge.HevTunnel {
    native <methods>;
}

# IPtProxy (gomobile): native code looks these classes up by name.
-keep class go.** { *; }
-keep class IPtProxy.** { *; }

# tor-android: libtor.so reads/writes TorService fields and calls methods by name over JNI
# (e.g. "torConfiguration", "torControlFd"). Renaming them crashes Tor on start.
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Full-traffic mode uses hev-socks5-tunnel (MIT) from src/main/jni/hev-socks5-tunnel.
// Without it the app still builds and filters DNS only.
val hevDir = file("src/main/jni/hev-socks5-tunnel")
val hasHev = file("$hevDir/Android.mk").exists() && file("$hevDir/third-part/lwip/Android.mk").exists()

// VPN mode uses bbwg (../wg, Go + official wireguard-go). Built only when Go is installed.
val goExe: File? = (System.getenv("PATH") ?: "").split(File.pathSeparator)
    .map { File(it, "go") }.firstOrNull { it.canExecute() }
val wgSrc = rootProject.file("wg")
val wgOut = layout.buildDirectory.dir("bbwg/jniLibs")

android {
    namespace = "com.blockbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.blockbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "4.0"

        if (hasHev) {
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
            externalNativeBuild {
                ndkBuild {
                    targets("hev-socks5-tunnel")
                    cFlags("-DPKGNAME=com/blockbridge", "-DCLSNAME=HevTunnel", "-O3")
                    arguments("APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true")
                }
            }
        }
    }

    if (hasHev) {
        ndkVersion = "27.2.12479018"
        externalNativeBuild {
            ndkBuild { path = file("src/main/jni/Android.mk") }
        }
    }

    // Fixed key so every build (local or GitHub Actions) can update the installed app
    // without uninstalling. Only used for personal sideloading.
    signingConfigs {
        create("blockbridge") {
            storeFile = file("blockbridge.jks")
            storePassword = "blockbridge"
            keyAlias = "blockbridge"
            keyPassword = "blockbridge"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("blockbridge")
        }
        debug {
            signingConfig = signingConfigs.getByName("blockbridge")
        }
    }

    // bbwg is an executable shipped as libbbwg.so; it must be extracted to run.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    if (goExe != null && hasHev) {
        sourceSets.getByName("main").jniLibs.srcDir(wgOut)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Cross-compiles bbwg for each ABI with the NDK's clang (cgo), output as lib/<abi>/libbbwg.so.
val buildBbwg by tasks.registering {
    enabled = goExe != null && hasHev
    inputs.dir(wgSrc)
    outputs.dir(wgOut)
    doLast {
        val toolchain = android.ndkDirectory.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
        val targets = mapOf(
            "arm64-v8a" to Triple("arm64", "aarch64-linux-android26-clang", ""),
            "armeabi-v7a" to Triple("arm", "armv7a-linux-androideabi26-clang", "7"),
            "x86_64" to Triple("amd64", "x86_64-linux-android26-clang", ""),
        )
        for ((abi, t) in targets) {
            val out = wgOut.get().file("$abi/libbbwg.so").asFile
            out.parentFile.mkdirs()
            val proc = ProcessBuilder(
                goExe!!.path, "build", "-trimpath", "-buildvcs=false", "-ldflags=-s -w", "-o", out.path, ".",
            ).directory(wgSrc).redirectErrorStream(true).apply {
                environment().putAll(mapOf(
                    "CGO_ENABLED" to "1", "GOOS" to "android", "GOARCH" to t.first,
                    "CC" to toolchain.resolve(t.second).path,
                ))
                if (t.third.isNotEmpty()) environment()["GOARM"] = t.third
            }.start()
            val log = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() != 0) throw GradleException("bbwg build failed for $abi:\n$log")
        }
    }
}
tasks.named("preBuild") { dependsOn(buildBbwg) }

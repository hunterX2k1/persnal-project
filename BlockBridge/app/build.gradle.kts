import java.util.Properties

plugins {
    id("com.android.application") // AGP 9 compiles Kotlin itself (built-in Kotlin)
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

// Release signing key (see the signingConfigs comment below)
val keyProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val storePath: String? = keyProps.getProperty("storeFile") ?: System.getenv("BB_KEYSTORE")
val hasReleaseKey = storePath != null && file(storePath).exists()

android {
    namespace = "com.blockbridge"
    // tor-android 0.4.9.6+ requires compiling against Android 37.1
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "com.blockbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 16
        versionName = "7.8"

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

    // Release signing key — never committed. Taken from BlockBridge/keystore.properties
    // (storeFile, storePassword, keyAlias, keyPassword) or the BB_KEYSTORE* environment
    // variables (GitHub Actions secrets). Without one, release builds use the debug key.
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = file(storePath!!)
            storePassword = keyProps.getProperty("storePassword") ?: System.getenv("BB_KEYSTORE_PASSWORD")
            keyAlias = keyProps.getProperty("keyAlias") ?: System.getenv("BB_KEY_ALIAS") ?: "blockbridge"
            keyPassword = keyProps.getProperty("keyPassword") ?: System.getenv("BB_KEY_PASSWORD")
                ?: System.getenv("BB_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    // One APK per processor type (most phones: arm64-v8a) plus a universal one.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // bbwg is an executable shipped as libbbwg.so; it must be extracted to run.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    if (goExe != null && hasHev) {
        sourceSets.getByName("main").jniLibs.directories.add(wgOut.get().asFile.path)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

}

dependencies {
    // Tor (Guardian Project, BSD): runs tor in-process; used as a free VPN exit with country choice.
    implementation("info.guardianproject:tor-android:0.4.9.14")
    // Tor bridges (Snowflake, obfs4/lyrebird) for networks that block Tor — Guardian Project, MIT/BSD
    implementation("com.netzarchitekten:IPtProxy:5.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM tests
    // Renders real screens on the JVM for screenshot checks (test only, not in the APK)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

// Cross-compiles bbwg for each ABI with the NDK's clang (cgo), output as lib/<abi>/libbbwg.so.
val ndkDir = androidComponents.sdkComponents.ndkDirectory
val buildBbwg = tasks.register("buildBbwg") {
    enabled = goExe != null && hasHev
    inputs.dir(wgSrc)
    outputs.dir(wgOut)
    doLast {
        val toolchain = ndkDir.get().asFile.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
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
// Tags every connection with the app-side address (see src/main/jni/patches). Idempotent.
val applyHevPatch = tasks.register("applyHevPatch") {
    enabled = hasHev
    val patch = file("src/main/jni/patches/hev-app-source.patch")
    inputs.file(patch)
    doLast {
        fun git(vararg args: String) = ProcessBuilder("git", "-C", hevDir.path, *args)
            .redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() }
        if (git("apply", "--reverse", "--check", patch.path) != 0 && git("apply", patch.path) != 0) {
            throw GradleException("Could not apply ${patch.name} to hev-socks5-tunnel")
        }
    }
}
tasks.named("preBuild") { dependsOn(buildBbwg, applyHevPatch) }

// ./gradlew testDebugUnitTest -Dbb.shots=/some/dir renders screenshots (ScreenshotTest).
// Robolectric downloads the Android system jar it renders with; use Google's Maven Central
// mirror (repo1.maven.org rate-limits that large download).
val shotsDir: String = System.getProperty("bb.shots") ?: ""
tasks.withType<Test>().configureEach {
    systemProperty("bb.shots", shotsDir)
    systemProperty("bb.e2e", System.getProperty("bb.e2e") ?: "")
    if (shotsDir.isNotEmpty()) {
        systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
    }
}

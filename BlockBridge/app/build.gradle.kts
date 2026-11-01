plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Full-traffic mode uses hev-socks5-tunnel (MIT) from src/main/jni/hev-socks5-tunnel.
// Without it the app still builds and filters DNS only.
val hevDir = file("src/main/jni/hev-socks5-tunnel")
val hasHev = file("$hevDir/Android.mk").exists() && file("$hevDir/third-part/lwip/Android.mk").exists()

android {
    namespace = "com.blockbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.blockbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"

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

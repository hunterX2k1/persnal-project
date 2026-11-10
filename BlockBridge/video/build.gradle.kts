import java.util.Properties

// BlockBridge Video: YouTube's site in Firefox's engine (GeckoView) with uBlock Origin,
// SponsorBlock and BlockBridge's YouTube Plus helper, as its own app.
plugins {
    id("com.android.application")
}

// Same release signing key as BlockBridge (see app/build.gradle.kts); never committed.
val keyProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val storePath: String? = keyProps.getProperty("storeFile") ?: System.getenv("BB_KEYSTORE")
val hasReleaseKey = storePath != null && rootProject.file(storePath).let { it.exists() || file(storePath).exists() }

android {
    namespace = "com.blockbridge.video"
    // GeckoView 159 needs Android 37.2
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.blockbridge.video"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
        // Firefox's engine is for 64-bit phones only (the 32-bit copy is ~70 MB and too heavy
        // for those phones). Practically every phone of the last years is 64-bit.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = rootProject.file(storePath!!).takeIf { it.exists() } ?: file(storePath)
            storePassword = keyProps.getProperty("storePassword") ?: System.getenv("BB_KEYSTORE_PASSWORD")
            keyAlias = keyProps.getProperty("keyAlias") ?: System.getenv("BB_KEY_ALIAS") ?: "blockbridge"
            keyPassword = keyProps.getProperty("keyPassword") ?: System.getenv("BB_KEY_PASSWORD")
                ?: System.getenv("BB_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    // Native libraries compressed in the APK: a smaller download.
    packaging { jniLibs { useLegacyPackaging = true } }

    sourceSets.getByName("main").kotlin.directories.add(rootProject.file("common/src/main/java").path)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    // Firefox's engine, running the real uBlock Origin (MPL-2.0)
    implementation("org.mozilla.geckoview:geckoview:159.0.20261102144711")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

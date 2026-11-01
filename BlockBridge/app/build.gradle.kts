plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.blockbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.blockbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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

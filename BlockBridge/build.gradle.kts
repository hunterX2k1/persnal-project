buildscript {
    dependencies {
        // Kotlin compiler used by AGP 9's built-in Kotlin support
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
}

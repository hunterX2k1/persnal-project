pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central (repo1.maven.org rate-limits big downloads)
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        // GeckoView (Firefox's engine) for BlockBridge Video
        maven("https://maven.mozilla.org/maven2/") { content { includeGroup("org.mozilla.geckoview") } }
        mavenCentral()
    }
}
rootProject.name = "BlockBridge"
include(":app")
include(":video")

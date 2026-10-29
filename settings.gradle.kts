pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        // NOT IN THE PUBLIC COPY: app/libs is empty; README.md says where to get both SDKs. Without them Gradle
        // stops with "Could not find com.google.android.gms:play-services-home" (or com.spotify.android:app-remote).
        // Google Home APIs 1.11: not on Google's Maven; Google ships it as a zip to host locally (Zeus's docs/HESTIA.md).
        // Only these two modules come from here.
        maven {
            url = uri("app/libs/home-sdk")
            content {
                includeModule("com.google.android.gms", "play-services-home")
                includeModule("com.google.android.gms", "play-services-home-types")
            }
        }
        // Spotify App Remote: the official AAR (Zeus's docs/SPOTIFY.md) as a local Maven entry, so dependency verification
        // checks its sha256 like every other artifact. Only this module comes from here.
        maven {
            url = uri("app/libs/spotify")
            content { includeModule("com.spotify.android", "app-remote") }
        }
    }
}
rootProject.name = "Apollo"
include(":app")

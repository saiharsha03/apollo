import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9 compiles Kotlin itself, so there is no kotlin-android plugin here.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // The code lives in dev.apollo; the application id stays dev.phonerecall so Apollo installs as an update over Zeus
    // and keeps its data and Keystore keys (Calypso).
    namespace = "dev.apollo"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.phonerecall"
        minSdk = 31
        targetSdk = 35
        // Above Zeus's last (230), so it installs over it.
        versionCode = 300
        versionName = "3.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        // The phone's daily build: not debuggable (ART compiles it), signed with the same debug key as Zeus's builds,
        // so it installs over them and keeps his data.
        create("fast") {
            initWith(getByName("debug"))
            isDebuggable = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "debug"
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    // lintVital's checks classpath pulls an unverified kotlin-stdlib-jdk8 pom; the fast build is installed by hand anyway.
    lint { checkReleaseBuilds = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // The verified graph (Zeus's) carries kotlin-stdlib 2.4.0, newer than this compiler's metadata; Zeus skipped the same check.
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

// Firebase's datatransport uploader is removed everywhere; the classes Firebase links against are no-op stubs in
// app/src/main/java/com/google/android/datatransport (same as Zeus): nothing is sent.
configurations.configureEach {
    exclude(group = "com.google.android.datatransport", module = "transport-runtime")
    exclude(group = "com.google.android.datatransport", module = "transport-backend-cct")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // Named at Zeus's versions so the graph resolves to artifacts gradle/verification-metadata.xml already checks.
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.2.10")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.10")
        // Firebase messaging's Play services pieces at the versions Zeus resolved (verified).
        implementation("com.google.android.gms:play-services-base:18.9.0")
        implementation("com.google.android.gms:play-services-basement:18.9.0")
        implementation("com.google.android.gms:play-services-tasks:18.4.0")
        implementation("com.google.errorprone:error_prone_annotations:2.50.0")
    }
    // The one HTTP client to the Apollo server (net/Server.kt).
    // Its kotlin-stdlib-jdk8 1.8.21 request isn't in the verified set; stdlib 2.x carries those classes itself.
    implementation("com.squareup.okhttp3:okhttp:4.12.0") { exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8") }
    // FCM wake-ups only ({"t":"sync"}); Firebase is set up from res/values/firebase.xml, no google-services plugin.
    implementation("com.google.firebase:firebase-messaging:24.1.1")
    // Google Home APIs 1.11 (Device and Structure APIs), through Google Play services. Since 1.10.1 the app must name the
    // coroutines libraries itself (Zeus's docs/HESTIA.md); same versions as Zeus, so the verified set covers them.
    implementation("com.google.android.gms:play-services-home:17.1.0")
    implementation("com.google.android.gms:play-services-home-types:17.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    // Muse: Spotify App Remote 0.8.0 (IPC to the Spotify app; no network of ours) and Gson, its JSON mapper. Zeus's versions.
    implementation("com.spotify.android:app-remote:0.8.0")
    implementation("com.google.code.gson:gson:2.14.0")
    // Senses, at Zeus's verified versions: stays and places (fused location, balanced power, and geofences for his saved
    // places; all inside Play services), Health Connect reads, and on-phone text from a shared photo or scan (ML Kit's
    // unbundled text recognition: the model lives in Play services, the photo and its text stay on the phone until sent).
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("androidx.health.connect:connect-client:1.1.0-beta01")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    // Calypso's clipboard clear survives process death.
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    testImplementation("junit:junit:4.13.2")
}

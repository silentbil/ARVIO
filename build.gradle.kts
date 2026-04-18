// Top-level build file for Arflix Native Android TV

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.test") version "8.5.2" apply false
    id("androidx.baselineprofile") version "1.3.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    // Kotlin 2.0+: Compose compiler is a dedicated Gradle plugin; version
    // must track Kotlin. 2.1.0 adds the `SpillingKt` coroutine helper that
    // modern CloudStream `.cs3` plugins link against.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    id("com.google.dagger.hilt.android") version "2.54" apply false
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin") version "2.0.1" apply false
    // Firebase - requires google-services.json from Firebase Console
    id("com.google.gms.google-services") version "4.4.0" apply false
    id("com.google.firebase.crashlytics") version "2.9.9" apply false
    // Static analysis
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
}

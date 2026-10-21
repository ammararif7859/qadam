// Root build file. The Kotlin plugin is declared here once so that `core` and `app`
// share one copy of it. The Android plugins are declared in app/build.gradle.kts, so a
// core-only build never needs Google's Maven.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

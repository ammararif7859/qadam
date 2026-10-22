// Root build file. Every plugin is declared here once ("apply false" = make it available,
// don't use it here), so `core` and `app` share one copy of each and the Kotlin and
// Android plugins can see each other's classes.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

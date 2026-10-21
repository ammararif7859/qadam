// TEMPORARY type-check harness — not part of the app. Delete it once the real build works.
//
// Without the Android SDK (blocked in the Claude Code container on 21 Oct 2026) this compiles
// the app + core sources against Robolectric's android-all jar (the Android 17 framework,
// from Maven Central) and JetBrains Compose for desktop 1.6.11 (same Compose API, older),
// with hand-written stand-ins for Room, activity-compose, LocalContext and R (in stubs/).
// It catches typos and wrong signatures. It does NOT check Room's SQL, resources,
// the manifest, or anything at run time.
//
// Run from the repo root:  ./gradlew -p tools/typecheck compileKotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17); allWarningsAsErrors.set(false) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
sourceSets {
    main {
        kotlin.srcDirs("../../app/src/main/kotlin", "../../core/src/main/kotlin", "stubs")
    }
}
dependencies {
    compileOnly("org.robolectric:android-all:17-robolectric-15733970")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.compose.runtime:runtime-desktop:1.6.11")
    implementation("org.jetbrains.compose.foundation:foundation-desktop:1.6.11")
    implementation("org.jetbrains.compose.ui:ui-desktop:1.6.11")
    implementation("org.jetbrains.compose.material3:material3-desktop:1.6.11")
}

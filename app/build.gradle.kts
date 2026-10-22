// `app`: the Android app. It collects signals, stores rows, draws screens and overlays,
// and hands every decision to `core`.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "de.ammar.qadam"
    compileSdk = 37 // Android 17, latest stable (checked 21 Oct 2026)

    defaultConfig {
        applicationId = "de.ammar.qadam"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    // One debug key for every machine (the debug key is not secret: password "android").
    // Builds from this container, from Pi or from Android Studio then carry the same
    // signature, so an update installs over the old app without deleting its data.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        checkDependencies = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// After every debug build: list the APK's permissions and fail if INTERNET is among them.
val checkApkPermissions = tasks.register<Exec>("checkApkPermissions") {
    description = "Lists the debug APK's permissions; fails if INTERNET is present."
    workingDir = rootDir
    commandLine("bash", "tools/check-apk-permissions.sh", "app/build/outputs/apk/debug/app-debug.apk")
}
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(checkApkPermissions) }

// Which modules make up the build, and where Gradle downloads things from.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Qadam"

// `core`: pure Kotlin, all the rules. Builds anywhere with a JDK.
include(":core")

// `app`: the Android app. Needs the Android SDK (local.properties or ANDROID_HOME).
// To build and test `core` alone, without the SDK:  ./gradlew -Pqadam.coreOnly=true test
// (Google's Maven must still be reachable: the plugins are declared in the root build file.)
if (providers.gradleProperty("qadam.coreOnly").orNull != "true") {
    include(":app")
}

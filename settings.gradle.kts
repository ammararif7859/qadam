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

// `app`: the Android app. Needs the Android SDK and Google's Maven.
// Where those are missing, build `core` alone with:  ./gradlew -Pqadam.coreOnly=true test
if (providers.gradleProperty("qadam.coreOnly").orNull != "true") {
    include(":app")
}

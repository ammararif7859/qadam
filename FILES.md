# FILES — what each file does

2–4 plain sentences per file: what it does and why Qadam needs it.
Android terms are explained the first time they appear.
"UNBUILT" means the file was written without the Android SDK and has never been compiled.

---

## Repo root

- **`AGENTS.md` / `CLAUDE.md`** — The working rules for any coding agent (identical text; Claude Code reads `CLAUDE.md`, Pi reads `AGENTS.md`). They fix the technology, the privacy rules and how we work step by step.
- **`SPEC.md`** — What the app must do: signals, states, the ladder, the phases and the phone tests. When code and spec disagree, the spec wins until you change it.
- **`PROGRESS.md`** — The handoff file: what is done, what is built and tested, what is unbuilt, which phone checks you still owe, and the next step. A new agent can continue from it alone.
- **`FILES.md`** — This file.
- **`.gitignore`** — Tells git which files never to commit: build output, local settings, and every `.csv` (so a real export can't slip into the repo by accident).

## Build system (Gradle)

Gradle is the build tool: it downloads libraries, compiles the code and packs the APK (the installable app file).

- **`settings.gradle.kts`** — Names the modules (`core`, `app`) and the download sites. Passing `-Pqadam.coreOnly=true` leaves `app` out, so `core` can be built and tested on a machine without the Android SDK.
- **`build.gradle.kts`** (root) — Declares the Kotlin plugin once for the whole project. The Android plugins are declared in `app/build.gradle.kts` instead, so a core-only build never needs Google's servers.
- **`gradle.properties`** — Build switches: memory for Gradle, build cache, and the AndroidX flag Android projects need.
- **`gradle/libs.versions.toml`** — The version catalog: one list of every library and plugin with its version. Changing a version here changes it everywhere.
- **`gradlew`, `gradlew.bat`, `gradle/wrapper/`** — The Gradle wrapper. It downloads the exact Gradle version (9.8.2) the project expects, so every machine builds the same way.

## `core` — pure Kotlin, all the rules

- **`core/build.gradle.kts`** — Builds `core` as a plain Kotlin library with no Android in it. Its tests run with JUnit on any computer with Java.
- **`core/src/main/resources/qadam/thresholds.properties`** — Every tunable number in one file: the §6 placeholders, the ladder timings, sensor rates. Phase 1 replaces the §6 values with numbers from your data; the app and the Python script both read this file.
- **`core/.../config/Thresholds.kt`** — Loads that file and gives the rules named values like `driftScreenMs` (10 min in milliseconds). It converts units by reading the key's last word (`min`, `s`, `h`).
- **`core/.../config/Knobs.kt`** — For every number: a label for the Rules screen and which direction is a loosening (e.g. a *higher* drift threshold is looser). §8 needs this to decide what waits 24 hours.
- **`core/src/test/.../config/ThresholdsTest.kt`** — Checks the file and the knob list name the same keys, the §6–§8 numbers match SPEC, and bad values are rejected.

## `app` — the Android app (UNBUILT)

- **`app/build.gradle.kts`** — Builds the APK: package `de.ammar.qadam`, `minSdk 29` (Android 10), `targetSdk 37` (Android 17). After every debug build it runs the permission check below.
- **`app/src/main/AndroidManifest.xml`** — The manifest: the app's ID card, listing its permissions, screens and background parts. It removes INTERNET (and network-state) even if a library tries to add it, and switches backup off.
- **`app/src/main/kotlin/de/ammar/qadam/MainActivity.kt`** — The activity (one full screen of an app) that hosts all Compose screens. Compose is Android's way of describing a screen in Kotlin code instead of XML layouts.
- **`app/src/main/res/xml/data_extraction_rules.xml`** — Tells Android 12+ to copy nothing to Google's cloud backup and nothing to a new phone. Your data stays on this phone.
- **`app/src/main/res/xml/backup_rules.xml`** — The same rule in the older format for Android 10–11.
- **`app/src/main/res/values/themes.xml`, `colors.xml`** — The window theme behind the Compose screens, a transparent theme for the NFC tag receiver, and the app colour.
- **`app/src/main/res/mipmap-anydpi/ic_launcher.xml`, `drawable/ic_launcher_foreground.xml`** — The launcher icon, drawn as a vector so no image files are needed.

## Tools

- **`tools/check-apk-permissions.sh`** — Lists the permissions inside the built APK with `aapt2` (Android's resource tool) and fails if INTERNET is among them. AGENTS.md requires this after every build.

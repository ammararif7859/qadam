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
- **`core/.../model/Model.kt`** — The shared vocabulary: app categories (drift / allowed / neutral), the §6 states, the time windows (day / meal / night), the six sampling labels, the summary row with exactly the §4 columns, and event rows. Every other file speaks in these types.
- **`core/.../csv/Csv.kt`** — Turns rows into CSV lines for the export: exact §4 header, local time with offset, 1/0 for yes/no, an empty cell for "no reading". It quotes cells that contain commas, so an override reason can't break the file.
- **`core/.../signals/GravityFilter.kt`** — Splits the accelerometer reading into gravity (the slow part) and linear acceleration (the shaking). This is the §4 fallback for phones without a gravity sensor; it's a low-pass filter, the same idea as an RC circuit.
- **`core/.../signals/MotionMeter.kt`** — Computes the motion level: the RMS of the shaking over the last 10 s, once per second. A summary row stores the mean and the 90th percentile of those values, which separates "on a table", "in hand" and "carried".
- **`core/.../signals/AccelStepDetector.kt`** — Counts steps from the accelerometer, because your phone has no step sensor. It looks for bounces of the right height at a steady walking pace (1–2.5 per second) and only counts runs of 4 or more, so lying, scrolling and turning over count nothing.
- **`core/.../signals/StepLog.kt`** — Remembers when steps happened, so the rules can ask "how many steps in the last 3 (or 5) minutes".
- **`core/.../rules/Settings.kt`** — Everything you set on the Rules screen, with the defaults of this run (bedtime 23:30, no meal windows, one example plan, levels off). It also sorts apps into categories (Settings, dialer, clock and Qadam are always allowed, whatever the list says) and tells which window a time of day falls in.
- **`core/.../logger/RowAccumulator.kt`** — Collects what happens between two summary rows (scroll, tap and step counts, motion, gravity, proximity, light) and closes them into one row. It also holds the §4 timing: a row every 30 s with screen on or audio, every 5 min otherwise.
- **`core/.../logger/SamplingPlanner.kt`** — Picks about 5 random prompt times per day (08:00 to bedtime, at least an hour apart) and decides when a due prompt is shown: only with the screen on, and dropped after an hour of waiting.
- **`core/.../engine/EngineTypes.kt`** — The engine's inputs and outputs: a *tick* (what the phone is doing right now), your *actions* (buttons, tag taps, override, pause), and the *output* (state, level, what overlay to draw, audio and grayscale wishes, one-off actions like "vibrate" or "go home").
- **`core/.../engine/EngineState.kt`** — Everything the ladder must remember (level, timers, snoozes, daily counters), and a text form of it. The app saves this text after every change, so killing or rebooting the phone can't lift a Level 3 block (§12 scenario 7).
- **`core/.../engine/Engine.kt`** — The heart of Qadam: the §6 states and the §7 ladder as one state machine. Every few seconds it gets a tick, decides the state (chore, moving, drift…), moves the ladder at most one step, and says what the app should show. It never touches Android, so the tests can replay hours of behaviour in milliseconds.
- **`core/.../rules/Commitment.kt`** — The §8 rules: every change on the Rules screen is either a tightening (applies at once) or a loosening (waits 24 h, shown as "active tomorrow at hh:mm"). Settings, the dialer and other protected apps can never be made drift apps.
- **`core/.../logger/Reports.kt`** — Daily totals for the Today screen, the morning notification ("Qadam was off 2 h") and the 30-day retention: drift minutes, chore minutes, triggers, false positives, overrides, time the services were off.
- **`core/src/test/.../engine/Sim.kt`** — A small simulator for tests: "scroll PipePipe at 8 per minute for 5 minutes", "walk at 60 steps per minute", "screen off with audio", one tick every 10 seconds.
- **`core/src/test/.../engine/StatesTest.kt`** — One test per §6 rule: DRIFT_SCREEN, DRIFT_SCROLL, DRIFT_AUDIO, MOVING, CHORE (and its early end), NIGHT, DANGER WINDOW, reset.
- **`core/src/test/.../engine/LadderTest.kt`** — One test per §7 rule: the three Level 1 buttons, Level 2 friction and audio hold, Level 3 block, tag unlock, override limits, pause, calls and protected apps.
- **`core/src/test/.../engine/ScenariosTest.kt`** — The eight §12 phone scenarios, replayed on synthetic signals.
- **`core/src/test/.../rules/CommitmentTest.kt`** — §8: which changes apply at once, which wait 24 h, the "active tomorrow at" label, maturing, cancelling.
- **`core/src/test/.../logger/ReportsTest.kt`** — Daily totals, off-time measurement, morning summary timing, retention cutoff.
- **`core/src/test/.../signals/SyntheticMotion.kt`** — Makes fake accelerometer streams for tests: walking at different paces and strengths, a phone on a table, lying in bed scrolling, fidgeting, turning over. No real data is needed to test the detector.
- **`core/src/test/.../signals/SignalsTest.kt`** — Tests the step detector (walking counts within 8–12 %, still or fidgeting counts almost nothing, at 10 and 20 Hz), the gravity filter, the motion level and the step log.
- **`core/src/test/.../logger/LoggerTest.kt`** — Tests the CSV format against SPEC §4, the row builder, the app categories, the time windows and the sampling plan.
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

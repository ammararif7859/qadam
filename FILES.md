# FILES — what each file does

2–4 plain sentences per file: what it does and why Qadam needs it.
Android terms are explained the first time they appear.
Everything here builds (22 Oct 2026); nothing has run on a phone yet.

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
- **`build.gradle.kts`** (root) — Declares every Gradle plugin once for the whole project (Android, Kotlin, Compose, KSP). Declaring them in one place lets the Kotlin and Android plugins see each other.
- **`local.properties`** (not in git) — One line telling Gradle where the Android SDK is on this machine (`sdk.dir=…`). Each computer has its own.
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

## `app` — the Android app (built; not yet run on a phone)

**Build and identity**
- **`app/build.gradle.kts`** — Builds the APK: package `de.ammar.qadam`, `minSdk 29` (Android 10), `targetSdk 37` (Android 17). After every debug build it runs the permission check, so an INTERNET permission can never slip in unnoticed.
- **`app/debug.keystore`** — The key that signs debug builds. Android only installs an update if it is signed like the installed app, so one shared key means updates from any computer keep your logged data. It is the standard, non-secret debug key — fine for a private app, never for a public one.
- **`app/src/main/AndroidManifest.xml`** — The manifest: the app's ID card. It lists every permission (and removes INTERNET even if a library adds it), the screens, the logger service (type "health"), the accessibility service, the NFC tag receiver and the boot receiver. Its `<queries>` block says which other apps Qadam may see (Android 11+ hides the rest).
- **`QadamApp.kt`** — The Application object, created once when the app's process starts. It opens the database and creates the notification channels.
- **`Bridge.kt`** — The meeting point inside the app's process: the logger service, the accessibility service, the NFC screen and the UI find each other here. It also holds the live values for the debug view and the scroll/tap counters. And the log tag `Qadam` (`adb logcat -s Qadam`).

**Storage (`data/`)**
- **`data/QadamDb.kt`** — The Room database: five tables (summary rows, events, small named values like the settings, pending loosenings, daily totals) and the DAO, the list of queries the app runs. Room turns these annotated Kotlin classes into SQLite code at build time.
- **`app/schemas/`** — Room's record of the database layout (version 1), written at every build. If a table ever changes, Room needs this record to move your existing data to the new layout (a "migration") instead of wiping it.
- **`data/Repo.kt`** — The one door to the database. It converts between `core`'s types and stored rows, applies the §8 commitment rules to every settings change, writes the CSV export page by page, and does the 30-day retention.

**The logger (`service/`)**
- **`service/QadamService.kt`** — The heart of the app on the phone: a foreground service (Android keeps it alive because it shows a permanent notification). Every 2–60 seconds it reads all signals, asks `core`'s engine what to do, and does it: overlays, audio, grayscale, vibration, notifications, event rows; every 30 s or 5 min it writes a summary row. It also runs sampling, the morning summary and retention.
- **`service/PhoneState.kt`** — Reads screen on/off, locked, audio playing, headphones, phone call and charging; and which app is in front (from Usage access). While the phone is locked no app counts as "in front", so no overlay can cover the lock screen or its emergency button.
- **`service/SensorHub.kt`** — Checks which sensors the phone has and listens to them only while the screen is on or audio plays. On your phone (no step sensor) steps come from `core`'s accelerometer step detector; missing gravity or linear-acceleration sensors are replaced by the filter in `core`.
- **`service/AudioAndScreen.kt`** — Pausing other apps' audio through audio focus (Levels 2 and 3), grayscale through Android's colour-correction setting (needs the adb grant), the one vibration, and the lookup of the phone's own dialer, SMS app, clock, camera, Settings and home screen so they are never blocked.
- **`service/Notifications.kt`** — All notifications: the permanent one of the service, the "Right now?" question with six one-tap answers (a custom layout, because a normal notification allows only three buttons), the screen-off Level 1 nudge with its three buttons, and the morning summary.
- **`service/Receivers.kt`** — Broadcast receivers, small entry points Android calls: one restarts the logger after a reboot or app update (§12 scenario 7); the other handles taps on notification buttons.

**Accessibility (`accessibility/`)**
- **`accessibility/QadamAccessibilityService.kt`** — The accessibility service you switch on in Settings. It only counts scroll gestures and taps (never reads text or screen content) and logs when it is switched on or off. It also hosts the overlays, because an accessibility service may draw over every app.
- **`accessibility/Overlays.kt`** — The Level 1 card (bottom of the screen, app stays usable), the Level 2 full screen (type the next action) and the Level 3 block (tag or 30-second hold + reason). Both full screens have a "Go to the home screen" button, so you can always reach Settings or the dialer.

**NFC (`nfc/`)**
- **`nfc/TagActivity.kt`** — An invisible screen Android opens when a Qadam tag touches the phone. It starts chore mode (and lifts a Level 3 block) through the service and closes at once.
- **`nfc/TagWriter.kt`** — Writes `qadam://tag/<name>` plus an "open Qadam" record onto an NTAG213/215 sticker, and reads it back for the test.

**Screens (`MainActivity.kt`, `ui/`)**
- **`MainActivity.kt`** — The one activity (a full screen of an app). It shows the five screens behind a bottom bar and starts the logger if it isn't running.
- **`ui/Common.kt`** — Shared pieces: the colour theme (light and dark), titled cards, "label … value" rows, small formatting helpers.
- **`ui/TodayScreen.kt`** — Now (state, level, chore mode, pause button), today's totals, and the live debug view of every signal plus the sensor check — the Phase 0 tool for checking each sensor works.
- **`ui/RulesScreen.kt`** — Levels on/off, drift and allowed apps (picked from installed apps), bedtime, night end, meal windows, if-then plans, grayscale, every editable threshold, and pending loosenings with "active tomorrow at hh:mm" and a Cancel button.
- **`ui/TagsScreen.kt`** — Write a tag, test a tag, last real tap, and where to stick tags (far from the bed).
- **`ui/DataScreen.kt`** — Export summary rows and events as CSV through Android's file picker, the 30-day retention with kept daily totals, and "delete all data" (type DELETE to confirm).
- **`ui/PermissionsScreen.kt`** — Each permission with its status and a button to its settings page, the HiOS battery settings of §13, NFC, and the adb command for grayscale.

**Resources (`res/`)**
- **`res/xml/accessibility_service_config.xml`** — The accessibility service's limits: only scroll and click events, `canRetrieveWindowContent="false"`.
- **`res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`** — Nothing goes to Google's cloud backup or to a new phone (Android 12+ and Android 10–11 formats).
- **`res/layout/notification_sampling_small.xml`, `..._big.xml`** — The six answer buttons of the "Right now?" notification: one row when collapsed or heads-up, two rows when expanded.
- **`res/values/strings.xml`** — The texts Android itself shows: app name, and the accessibility description (which promises what the service never reads).
- **`res/values/themes.xml`, `colors.xml`** — Window themes (normal and the invisible NFC screen), notification button styles, the app colour.
- **`res/mipmap-anydpi/ic_launcher.xml`, `res/drawable/ic_launcher_foreground.xml`, `res/drawable/ic_stat_qadam.xml`** — The launcher icon and the one-colour status-bar icon, drawn as vectors.

## `analysis` — Phase 1, on the laptop (Python)

- **`analysis/make_synthetic.py`** — Makes fake exports in the exact §4 format: days of sleep, drifting in bed, chores with a video, studying (also with a lecture on YouTube, a known false-positive source), eating, walks with audio, about 5 labels a day. The script and its tests are developed on this, never on your real data.
- **`analysis/qadam_analyze.py`** — The Phase 1 script. It reads your export, groups rows by your labels (±5 min; chore mode counts as chores), shows each signal per activity (table + plots), replays the §6 rules, tries other threshold values, and writes `proposed_thresholds.properties` in the same format as the real file. Run: `python3 analysis/qadam_analyze.py my_export.csv`.
- **`analysis/test_analysis.py`** — 16 tests on synthetic data: the CSV header equals SPEC §4 and the app's header, labels and chore mode are grouped right, the rules catch drifting and spare chores, a longer threshold catches less and hits less, the proposal file is valid, plots are written. Run: `python3 -m unittest discover -s analysis`.

## Tools

- **`tools/check-apk-permissions.sh`** — Lists the permissions inside the built APK with `aapt2` (Android's resource tool) and fails if INTERNET is among them. AGENTS.md requires this after every build.

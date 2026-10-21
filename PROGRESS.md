# PROGRESS — Qadam

The handoff file. Whoever continues (Ammar, Pi, another agent) starts here.
Read `AGENTS.md` and `SPEC.md` first; "This run" rules from 21 Oct are summarised at the end.

**Last updated:** 21 Oct 2026 — after Step 1 (skeleton).

---

## 1. State at a glance

| Part | Status |
|---|---|
| `core` (pure Kotlin) | **built and tested** here: `./gradlew -Pqadam.coreOnly=true test` |
| `app` (Android) | **UNBUILT** — every Android file is written but was never compiled |
| Phase 1 script (`analysis/`) | not started |
| Pushed to GitHub | **no** — no repo attached to the session yet |

## 2. Blockers in the Claude Code container (21 Oct)

1. **`dl.google.com` is blocked** by the environment's network policy. The Android SDK
   and Google's Maven (`maven.google.com` redirects there) both download from it. So
   `./gradlew assembleDebug` and `lint` cannot run here. Fix: allow `dl.google.com` in
   the environment's network settings, or build on a machine with the SDK (SPEC §15 Route A).
2. **No GitHub repo attached**, so nothing is pushed. Ammar must connect GitHub
   (https://claude.ai/connect-github), install the Claude GitHub App on a private repo,
   and give its `owner/name`.
3. Maven Central answers HTTP 429 at random from this container. Worked around with a
   local retrying relay plus `~/.gradle/init.d/maven-central-repo1.init.gradle.kts`.
   Both live outside the repo; a normal machine needs neither.

## 3. Done

- Step 0 — `AGENTS.md`, `CLAUDE.md`, `SPEC.md` committed word for word.
- Step 1 — Gradle skeleton (Gradle 9.8.2 wrapper, version catalog), `core` with the
  thresholds file and its tests, empty Compose `app` (unbuilt) with INTERNET removed,
  backup off, data-extraction rules, and `tools/check-apk-permissions.sh`.

## 4. Built and tested vs unbuilt

**Built and tested (`core`):**
- `config/Thresholds.kt`, `config/Knobs.kt`, `resources/qadam/thresholds.properties` — 7 tests.

**UNBUILT (`app`)** — compile these first once the SDK is available:
- `app/build.gradle.kts`, `AndroidManifest.xml`, `MainActivity.kt`, all `res/` files.
- Versions in `gradle/libs.versions.toml` were read from developer.android.com release
  notes (AGP 9.4.0, Compose BOM 2026.09.00, Room 2.8.5, Activity 1.13.0) but never resolved.
- `compileSdk = 37` uses the classic DSL; AGP 9 also has a newer `compileSdk { }` form. Unchecked.

## 5. Phone checks Ammar still owes

None can be done before the app builds. Full list (filled in as features land):

- [ ] Phase 0 / build: `./gradlew assembleDebug` prints the permission list with no INTERNET.

## 6. Decisions taken (SPEC left them open)

- Two modules, `core` + `app` (This-run rule 3 overrides AGENTS.md "one module").
- `targetSdk`/`compileSdk` 37 (Android 17 is the latest stable, checked 21 Oct 2026).
- All tunable numbers live in one file: `core/src/main/resources/qadam/thresholds.properties`.
  The app reads it as defaults; the Phase 1 script reads it too.
- Core-only builds: `settings.gradle.kts` skips `:app` when `-Pqadam.coreOnly=true` is passed.

## 7. Next step

Step 2–4: `core` CSV row + writer, signal processing (gravity filter, motion RMS,
accelerometer step detector) and logger logic (row builder, app categories, windows,
sampling planner), each with tests.

## 8. How to build

- With the Android SDK: `./gradlew test assembleDebug lint`
  (`assembleDebug` runs `tools/check-apk-permissions.sh` automatically).
- Without it: `./gradlew -Pqadam.coreOnly=true test`
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## 9. "This run" rules (21 Oct, override AGENTS.md where they differ)

1. Ammar can't test on the phone until late November. Build Phases 0, 2, 3; for Phase 1
   only the Python script, tested on synthetic CSVs. Thresholds stay placeholders in one file.
2. After the Phase 0 plan was approved ("go"), work through the phases without waiting.
   Ask only for decisions SPEC.md can't settle.
3. Modules `core` (pure Kotlin, all rules, JUnit-tested) and `app` (Android). Every §6–§8
   rule and every §12 scenario gets a unit test in `core`.
4. Verification without a phone: `./gradlew test`, `./gradlew assembleDebug`, APK
   permissions without INTERNET. If the SDK is unreachable: build `core`, mark Android
   files "unbuilt".
5. Keep this file current after every step.
6. File explanations go in `FILES.md` (2–4 plain sentences each).
7. Commit after every green step and push to the private repo.
8. Phone: Tecno Pova 5 Pro 5G, HiOS 14 (Android 14), no step sensor → accelerometer step
   detector in `core`. `minSdk 29`.
9. SPEC §0 blanks become settings with defaults: drift/allowed apps picked from installed
   apps; bedtime 23:30; meal windows empty; one editable example if-then plan.

# PROGRESS — Qadam

The handoff file. Whoever continues (Ammar, Pi, another agent) starts here.
Read `AGENTS.md` and `SPEC.md` first; the "This run" rules from 21 Oct are in section 9.

**Last updated:** 21 Oct 2026 — after the `core` rule engine (Phases 2+3 logic).

---

## 1. State at a glance

| Part | Status |
|---|---|
| `core` (pure Kotlin, all rules) | **built and tested**: 116 unit tests pass (`./gradlew -Pqadam.coreOnly=true test`) |
| `app` (Android) | **UNBUILT** — only the step-1 skeleton so far; never compiled |
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
- Steps 2–4 — `core`: model and CSV format (§4 columns), gravity filter, motion RMS,
  accelerometer step detector, step log, settings with this run's defaults, app
  categories, day windows, summary-row builder, experience-sampling planner.
- Phase 2+3 logic (done early, so the app can be written against it) — `core/engine`:
  the §6 states and §7 ladder as one state machine, with saved state; `rules/Commitment`
  for §8; `logger/Reports` for daily totals, off-times and the morning summary. Every §6–§8
  rule and every §12 scenario has a unit test; two deliberate bugs were caught by them.

## 4. Built and tested vs unbuilt

**Built and tested (`core`, 116 tests):**
- `config/` — thresholds file, loader, knob directions.
- `model/`, `csv/` — rows and the CSV export format.
- `signals/` — gravity filter, motion meter, accelerometer step detector, step log.
  Step detector on synthetic data: 96–100 % of steps for normal walking at 10 and 20 Hz,
  0 false steps lying/scrolling, ≤ 8 per 5 min with strong fidgeting. Very gentle walking
  (bounce ≈ 1.2 m/s²) is undercounted — see phone checks.
- `rules/` — settings, app categories, day windows, commitment (§8).
- `logger/` — row accumulator, sampling planner, reports.
- `engine/` — states, ladder, saved state.

**UNBUILT (`app`)** — compile these first once the SDK is available:
- `app/build.gradle.kts`, `AndroidManifest.xml`, `MainActivity.kt`, all `res/` files.
- Versions in `gradle/libs.versions.toml` were read from developer.android.com release
  notes (AGP 9.4.0, Compose BOM 2026.09.00, Room 2.8.5, Activity 1.13.0) but never resolved.
- `compileSdk = 37` uses the classic DSL; AGP 9 also has a newer `compileSdk { }` form. Unchecked.

## 5. Phone checks Ammar still owes

Nothing can be checked on the phone until the app builds. Full list:

**Build (any machine with the SDK)**
- [ ] `./gradlew test assembleDebug lint` passes; the permission list printed after
      `assembleDebug` has no INTERNET.

**Phase 0 (SPEC §11)**
- [ ] Debug view shows every sensor's status (first check of Phase 0).
- [ ] Step count: walk 100 counted steps holding the phone, then 100 with it in a pocket.
      The debug step counter should rise by 90–110 each time. If it undercounts, lower
      `step.min_peak` in `thresholds.properties` (default 0.8 m/s²).
- [ ] 1. Runs 3 days without Android killing it.
- [ ] 2. Battery use (Settings → Battery) about 5 % a day or less.
- [ ] 3. Exported CSV opens on the laptop, with labels in it.
- [ ] 4. The APK's permission list has no INTERNET.

**Phase 2 (one week, Level 1 only)**
- [ ] Under one false positive a day ("Wrong" button).
- [ ] If-then plans written.

**Phase 3 (SPEC §12 — all also replayed in `ScenariosTest`)**
- [ ] 1. Lie on the bed and scroll PipePipe → Level 1 by about 10 min; sooner with heavy scrolling.
- [ ] 2. Kitchen tag, phone propped up, dishes 20 min with a video → nothing fires.
- [ ] 3. Lie down with audio, screen off 15 min → Level 1 (audio version: notification + vibration).
- [ ] 4. Phone in pocket, walking with audio → nothing fires.
- [ ] 5. At Level 3 → only a tag or the 30-second override unlocks.
- [ ] 6. A call during Level 3 → the call works.
- [ ] 7. Reboot → the service starts again by itself (and a Level 3 block is still there).
- [ ] 8. Loosen a threshold → it shows "active tomorrow at hh:mm".
- [ ] Extra: PipePipe pauses at Level 3 (audio focus) and at Level 2 with the screen off.

## 6. Decisions taken (SPEC left them open)

Structure and versions
- Two modules, `core` + `app` (This-run rule 3 overrides AGENTS.md "one module").
- `targetSdk`/`compileSdk` 37 (Android 17 is the latest stable, checked 21 Oct 2026).
- All tunable numbers live in one file: `core/src/main/resources/qadam/thresholds.properties`.
  The app reads it as defaults; the Phase 1 script reads it too.
- Core-only builds: `settings.gradle.kts` skips `:app` when `-Pqadam.coreOnly=true` is passed.

Settings defaults (This-run rule 9)
- Bedtime 23:30; NIGHT ends 06:00 (SPEC gives no wake time; a setting). Meal windows empty.
  One plan text (SPEC's example) used for every window until others are written.
- `maxLevel` setting: 0 = logger only (Phase 0, default on a fresh install), 1 = Level 1
  only (Phase 2), 3 = full ladder (Phase 3). Raising it applies at once; lowering it waits
  24 h (SPEC §8 "switching a level off").
- Settings, dialer, phone, telecom, in-call UI, emergency, clock, permission controller,
  package installer and Qadam are always ALLOWED even if put on the drift list.

Signals
- Accelerometer step detector: smoothed magnitude minus its slow average; peaks ≥ 0.8 m/s²,
  steady pace (±35 %) between 1 and 2.5 steps/s, runs of ≥ 4; one missed peak inside a
  run is counted. Accelerometer rate for the fallbacks: 20 Hz (SPEC says ~10 Hz for
  signals 5–8; the detector also works at 10 Hz — lower `sensors.rate_hz` if battery suffers).
- Experience sampling: prompts between 08:00 and bedtime, ≥ 60 min apart; a prompt due
  while the screen is off waits up to 60 min for screen-on, then is dropped.

Rules (§6) — how the words were read
- "Drift app in front without a break": leaving the drift app for under 60 s is not a
  break (e.g. a glance at the notification shade). Switching between drift apps is one session.
- DRIFT_SCROLL "≥ 6 scrolls per minute for 3 min": every one of the last 3 minutes has
  ≥ 6 scrolls in a drift app (a burst then nothing doesn't count).
- DRIFT_AUDIO: audio is credited to the last app seen in front before the screen went
  off; only a drift app counts (SPEC §5: only drift apps trigger). "No steps" = 0 steps in
  the last 15 min (`drift_audio.max_steps=0`).
- CHORE early end: screen on all 5 min, ≥ 1 scroll in each minute (any app), 0 steps.
- NIGHT: after bedtime, a drift app coming to the front goes straight to Level 2 (with
  only Level 1 switched on: Level 1).
- MOVING (≥ 20 steps in 3 min) blocks new triggers and escalation, but doesn't reset the
  ladder; the reset needs ≥ 50 steps within 5 min (or screen off 10 min with no audio).
- After every reset the drift timers start again from zero.

Ladder (§7)
- "Getting up": at least 1 step within 2 min (`level1.getting_up_steps`), else Level 2.
- "5 more minutes": snoozes 5 min; if still drifting after that, it's a second trigger → Level 2.
- "Wrong": logged as a false positive, 15-min snooze, and it doesn't count as a trigger.
- Second trigger within 30 min → Level 2 directly. A tag tap clears that memory; an
  override or the end of a block does not.
- Level 1 while the screen is off (audio drift): vibration + a notification with the
  three buttons (SPEC's "audio version").
- Level 2: the typed action buys 5 min once; Level 3 comes 5 min after Level 2 began or
  when the bought minutes end, whichever is later, if still drifting. Audio is held
  (transient audio focus) until steps start. Grayscale (if on) during Levels 2 and 3.
- Level 3: steps, screen-off, pause and the Level 1/2 buttons don't unlock; tag or
  override (30 s hold + reason, 2/day) do; otherwise the block ends after 20 min.
- During a call nothing fires, escalates or is drawn.
- Pause (1 h, once a day) is refused during a Level 3 block.

Commitment (§8)
- Loosening = anything that makes Qadam fire or block less: a looser threshold (direction
  per knob in `Knobs.kt`), removing a drift app, allowing a drift app, a later bedtime or
  earlier night end, removing meal-window time, lowering `maxLevel`, grayscale off, a long
  pause (2–72 h). Everything else applies at once.
- A tightening of the same thing voids a pending loosening; cancelling a pending change
  is always immediate.

## 7. Next step

Phase 1 script (`analysis/`): synthetic CSV generator, the analysis script and its tests.
Then the Android side of Phases 0, 2, 3 (all UNBUILT here).

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

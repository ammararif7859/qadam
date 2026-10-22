# PROGRESS — Qadam

The handoff file. Whoever continues (Ammar, Pi, another agent) starts here.
Read `AGENTS.md` and `SPEC.md` first; the "This run" rules from 21 Oct are in section 9.

**Last updated:** 22 Oct 2026 — the app is built (APK, tests, lint) and pushed to GitHub. Nothing has run on a phone yet.

---

## 1. State at a glance

| Part | Status |
|---|---|
| `core` (pure Kotlin, all rules) | **built and tested**: 120 unit tests pass (`./gradlew test`) |
| `app` (Android) | **built**: `assembleDebug` makes the APK, `lint` 0 errors, no INTERNET permission. **Not yet run on a phone.** |
| Phase 1 script (`analysis/`) | **built and tested** on synthetic CSVs: 16 tests (`python3 -m unittest discover -s analysis`) |
| GitHub | pushed to the private repo `ammararif7859/qadam`, branch `main` |

## 2. Build environment notes

- **Claude Code container (21–22 Oct):** `dl.google.com` was blocked until Ammar allowed it
  on 22 Oct; GitHub was attached the same day. The Android SDK was installed at
  `/root/android-sdk` (command-line tools 23.0, `platforms;android-37.0`,
  `build-tools;37.0.0`, `platform-tools`) — that folder disappears with the container.
- Maven Central answered HTTP 429 at random from that container; a local retrying relay
  and `~/.gradle/init.d/maven-central-repo1.init.gradle.kts` worked around it. Both lived
  outside the repo; a normal machine needs neither.
- **Any machine:** the SDK location goes in `local.properties` (`sdk.dir=/path/to/sdk`,
  ignored by git) or the `ANDROID_HOME` variable. Pi's image (SPEC §15 Route A) needs the
  same SDK packages as above and JDK 17 or 21.

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
- Phase 1 script — `analysis/`: synthetic CSV generator, `qadam_analyze.py` (signal
  table + plots per activity, rule replay, threshold sweeps, proposals in properties
  format), 16 tests. Runs a synthetic week in about 2 s.
- `core` extras for the app — settings text format, scroll-gesture counter (120 tests).
- Android side — Room database and repository; foreground logger service with
  sensors, usage stats, audio/call/charging state, accelerometer fallbacks, summary and
  event rows, gap detection; accessibility scroll/tap counter and Level 1–3 overlays;
  audio focus, grayscale, vibration; NFC tag receiver and writer; sampling, Level 1
  screen-off and morning notifications; boot receiver; the five screens (Today with live
  debug view, Rules, Tags, Data with CSV export, Permissions). Written on 21 Oct without
  the SDK (type-checked only, with a temporary harness since deleted).
- First real build (22 Oct) — SDK installed; two build fixes: all Gradle plugins are now
  declared in the root build file (the Kotlin plugin couldn't see the Android plugin's
  classes), and a notification button style got an explicit parent. Then three lint fixes
  (themed launcher icon, a redundant version check, an explicit `else`), a shared debug
  signing key (`app/debug.keystore`), the UNBUILT marks removed, `tools/typecheck` deleted.

## 4. Built and tested vs unbuilt

**Built and tested (`core`, 120 tests):**
- `config/` — thresholds file, loader, knob directions.
- `model/`, `csv/` — rows and the CSV export format.
- `signals/` — gravity filter, motion meter, accelerometer step detector, step log.
  Step detector on synthetic data: 96–100 % of steps for normal walking at 10 and 20 Hz,
  0 false steps lying/scrolling, ≤ 8 per 5 min with strong fidgeting. Very gentle walking
  (bounce ≈ 1.2 m/s²) is undercounted — see phone checks.
- `rules/` — settings, app categories, day windows, commitment (§8).
- `logger/` — row accumulator, sampling planner, reports.
- `engine/` — states, ladder, saved state.

**Built and tested (`analysis`, 16 tests, Python 3 standard library; plots need matplotlib):**
- `make_synthetic.py`, `qadam_analyze.py`, `test_analysis.py`. The Python rule replay works
  per summary row (30 s), the app's engine per 10-s tick, so their minutes differ slightly.

**Built, not yet run on a phone (`app`):**
- `./gradlew test assembleDebug lint` passes. Versions in use: AGP 9.4.0, Gradle 9.8.2,
  Kotlin 2.4.21, KSP 2.3.12, Compose BOM 2026.09.00, Room 2.8.5, Activity 1.13.0,
  coroutines 1.11.0; compileSdk/targetSdk 37 (platform `android-37.0`), minSdk 29.
- The APK's permissions (printed after every `assembleDebug`): FOREGROUND_SERVICE,
  FOREGROUND_SERVICE_HEALTH, ACTIVITY_RECOGNITION, PACKAGE_USAGE_STATS, POST_NOTIFICATIONS,
  RECEIVE_BOOT_COMPLETED, NFC, VIBRATE, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
  WRITE_SECURE_SETTINGS, and `de.ammar.qadam.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
  (added by AndroidX; a private permission only Qadam itself holds). **No INTERNET.**
- Merged manifest checked: allowBackup false, data-extraction rules set, service type
  health, accessibility events = clicked + scrolled only, canRetrieveWindowContent false.
- Lint: 0 errors, 21 warnings, none about permissions. Left as they are: hard-coded
  English text (no translation planned), "use androidx.core-ktx" hints (not a dependency),
  `isAccessibilityTool` ignored below Android 12.
- Unchecked until the phone: everything at run time. Most likely to need work on HiOS: the
  grayscale setting names, audio focus pausing PipePipe, the custom sampling notification,
  accessibility overlays above the navigation bar, HiOS killing the service.

## 5. Phone checks Ammar still owes

Full list, in order. Ammar can start in late November.

**First install (laptop + phone), step by step**
- [ ] Get the APK: the file `qadam-0.1-debug.apk` sent in the Claude Code chat on 22 Oct
      (same code as commit "First real Android build"), or build a fresh one with
      `./gradlew assembleDebug` on a machine with the SDK → `app/build/outputs/apk/debug/app-debug.apk`.
- [ ] Laptop (Ubuntu): `sudo apt install adb` — installs adb, the tool that talks to the
      phone over USB.
- [ ] Phone: Settings → About phone → tap "Build number" 7 times (Developer options appear;
      on HiOS the menu may be called slightly differently). Then Settings → System →
      Developer options → USB debugging → on.
- [ ] Connect the phone by USB cable. Laptop: `adb devices`. The phone asks "Allow USB
      debugging?" → Allow. `adb devices` then lists one device.
- [ ] Laptop: `adb install -r qadam-0.1-debug.apk` (use the file's real path) — installs or
      updates Qadam; nothing else on the phone changes. Updates built later from this repo
      install over it with their data kept (same signing key, `app/debug.keystore`).
- [ ] Open Qadam → Permissions: grant Activity recognition (the logger needs it on
      Android 14), Notifications, Usage access, Accessibility (if greyed out: App info → ⋮ →
      Allow restricted settings), battery "Unrestricted", and the three HiOS settings.
- [ ] Optional grayscale: `adb shell pm grant de.ammar.qadam android.permission.WRITE_SECURE_SETTINGS`
      (lets Qadam switch the colour correction; can be revoked with `pm revoke`).
- [ ] Rules: add drift apps (PipePipe, YouTube …) and allowed apps; Tags: write the stickers.

**Phase 0 (SPEC §11)**
- [ ] Debug view shows every sensor's status (first check of Phase 0).
- [ ] Debug view: scrolls count about one per flick in PipePipe; the app in front is right;
      audio, headphones, charging, proximity (cover the top of the phone), light change.
- [ ] A sampling notification appears (about 5 a day) and one tap on an answer closes it;
      the next exported CSV has the label in that row.
- [ ] A tag tap shows "Qadam: chore mode (kitchen)" and Today shows chore mode.
- [ ] Step count: walk 100 counted steps holding the phone, then 100 with it in a pocket.
      The debug step counter should rise by 90–110 each time. If it undercounts, lower
      `step.min_peak` in `thresholds.properties` (default 0.8 m/s²).
- [ ] 1. Runs 3 days without Android killing it.
- [ ] 2. Battery use (Settings → Battery) about 5 % a day or less.
- [ ] 3. Exported CSV opens on the laptop, with labels in it.
- [ ] 4. The APK's permission list has no INTERNET (already true for the 22 Oct build;
      re-check after any rebuild: it is printed after `assembleDebug`).

**Phase 1 (laptop, after 1–3 weeks of Phase 0 logging)**
- [ ] Export the summary CSV, run `python3 analysis/qadam_analyze.py export.csv`, read
      `analysis/out/report.md` and the plots, copy the lines you agree with from
      `analysis/out/proposed_thresholds.properties` into `thresholds.properties`, rebuild.
      Never commit the export (`.gitignore` blocks `*.csv`).

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
- Core-only builds: `settings.gradle.kts` skips `:app` when `-Pqadam.coreOnly=true` is passed
  (useful without the SDK; Google's Maven must still be reachable for the plugins).
- All Gradle plugins are declared once in the root `build.gradle.kts` (`apply false`), so
  the Kotlin and Android plugins share one class loader (AGP 9's built-in Kotlin needs this).
- One debug signing key in the repo (`app/debug.keystore`, the standard non-secret debug
  password). Every machine's debug build gets the same signature, so updates install over
  the logged data instead of needing an uninstall. Never use it for anything public.

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

App (Android side)
- Overlays are plain Android views in accessibility-overlay windows (no "display over other
  apps" permission; Compose outside an activity needs extra plumbing).
- Full-screen overlays (Levels 2, 3) have a "Go to the home screen" button; while the phone
  is locked no app counts as in front, so nothing ever covers the lock screen.
- Tick every 2 s at Level ≥ 2 with the screen on (blocks appear fast), 10 s with screen on
  or audio, 60 s otherwise; every 2 s while a Qadam screen is open (live debug view).
- In a call = audio mode ringing / in call / in communication (no phone permission needed).
- One scroll = one burst of scroll events (events < 400 ms apart are one flick).
- Off-time for the morning summary: a gap is logged only when the service restarts after
  > 6 min without a heartbeat, from the last heartbeat to the restart — an upper bound
  (if the phone slept first, the sleep is counted too).
- The engine state is saved after every change, so a reboot or kill keeps a Level 3 block.
- Sampling continues in every phase (labels stay useful); the plan is recomputed from the
  date and a per-install random seed.
- Writing or testing a tag on the Tags screen uses NFC reader mode, so it doesn't start chore mode.
- The foreground service needs Activity recognition on Android 14+ (health type); without
  it the logger doesn't start and the Today screen says so.

Phase 1 script
- Each label covers rows within ±5 min (`--label-span`). Rows in chore mode with no label
  near count as "chores" (labels are rare while the phone is in a pocket); `--no-chore-mode` turns this off.
- Proposal = the tried value with the most (caught drifting minutes − 5 × minutes of chores
  or studying hit by mistake), one threshold at a time; ties keep the current value. A
  proposal at the edge of the tried range is flagged. Ammar decides what to copy.
- Python standard library only, plus matplotlib for the plots (optional).

Commitment (§8)
- Loosening = anything that makes Qadam fire or block less: a looser threshold (direction
  per knob in `Knobs.kt`), removing a drift app, allowing a drift app, a later bedtime or
  earlier night end, removing meal-window time, lowering `maxLevel`, grayscale off, a long
  pause (2–72 h). Everything else applies at once.
- A tightening of the same thing voids a pending loosening; cancelling a pending change
  is always immediate.

## 7. Next step

**Ammar, late November:** section 5 in order — first install, then Phase 0 (interventions
stay off, `maxLevel` 0, until Phase 1's numbers are in).

**The next agent (Pi):** nothing to build until the phone reports back. When something
fails on the phone, ask for `adb logcat -s Qadam` (AGENTS.md rule 5); fix it in a small
step; rebuild with `./gradlew test assembleDebug lint`; Ammar reinstalls with
`adb install -r`. Pi's image needs the SDK packages listed in section 2.

## 8. How to build

- Everything: `./gradlew test assembleDebug lint` (needs the SDK, see section 2).
  `assembleDebug` runs `tools/check-apk-permissions.sh` automatically and fails on INTERNET.
- `core` only, without the SDK: `./gradlew -Pqadam.coreOnly=true test`
- Phase 1 script: `python3 -m unittest discover -s analysis`
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

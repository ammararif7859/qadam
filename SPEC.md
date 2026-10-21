# Qadam — app spec v1.1 (21 Oct 2026)

**v1.1:** my phone added (§0); sensor check and accelerometer fallbacks (§4); HiOS background settings (§13).

*Qadam* (قدم) — "step" in Urdu, Arabic and Persian. The app's one rule is about steps.

**What this file is.** The full description of the app, for the coding agent: Pi with DeepSeek, or Claude Code until 25 Oct. In the repo it is `SPEC.md`; the agent's working rules are `AGENTS.md` (separate file). The app is built phase by phase (§11). Each phase ends with a working APK and checks I do by hand on my phone.

---

## 0. Fill in before Phase 0

- Phone: **Tecno Pova 5 Pro 5G** (MediaTek Dimensity 6080). It runs **HiOS 14.0.0, which is Android 14**.
- NFC: **yes** (published specs). Check that it is switched on: Settings → search "NFC".
- Drift apps (§5): PipePipe, YouTube, ______
- Always-allowed apps (§5): phone, ______
- Bedtime: ______ Meal windows (when I usually finish eating): ______
- Where the NFC tags go (§9): ______
- My if-then plans (§7, Level 1 text), one per window. Example: "After eating → plate to the sink, food in the fridge, then sit at the desk."

---

## 1. The problem

- I lose 1–2 hours at a time lying still with the phone. Mostly after meals, before sleep and before chores.
- It starts without a decision. I notice afterwards, and even then I don't get up.
- Screen-time and delay apps stopped working: I got used to their warnings.
- I also listen to physics and maths videos while doing chores, and I learn a lot that way. **That must keep working.**

## 2. The idea

**Moving: the phone is free. Still, with the phone in hand or audio running: the phone gets harder to use. The way out is always to get up.**

- Detect *drifting*: entertainment app or audio, body still, not in chore mode.
- Escalate slowly: nudge → friction → stop (§7).
- At the top level, the only normal unlock is walking to an NFC tag stuck far from the bed. **Getting up is the unlock.** An override exists, but it costs time and is logged.
- Rules I set when calm can be tightened at once, but loosened only after 24 hours (§8).

Why this should not fade like the old apps:
1. It doesn't fire while I do chores, so I have no reason to switch it off.
2. Its escape route is the behaviour I want (standing up), not a button I learn to tap.
3. Its thresholds come from my own data (§11, Phase 1), not from guesses.

## 3. Privacy — non-negotiable

- **No INTERNET permission.** Without it, Android doesn't let the app send anything anywhere. Checked on every build (AGENTS.md).
- The accessibility service never reads screen content. It counts scrolls and taps and notes which app is in front. No text, URLs, video titles or notification contents, ever.
- No Google Play Services, Firebase, analytics, crash reporting or ads.
- `allowBackup="false"`: Android's cloud backup gets nothing.
- Data stays in the app's private storage. It leaves the phone only when I export a CSV by hand.
- My real exports never go to a cloud model. The agent tests with synthetic data.

---

## 4. Signals

| # | Signal | Android source | Permission | What it tells |
|---|---|---|---|---|
| 1 | Screen on/off | `ACTION_SCREEN_ON/OFF`, `PowerManager.isInteractive` | none | phone in use |
| 2 | App in front | `UsageStatsManager.queryEvents` (resumed/paused) | Usage access (special) | which app, how long |
| 3 | Scrolls, taps (counts only) | `AccessibilityService`: `TYPE_VIEW_SCROLLED`, `TYPE_VIEW_CLICKED`; `canRetrieveWindowContent="false"` | Accessibility (manual) | feed-scrolling rate |
| 4 | Steps | `Sensor.TYPE_STEP_DETECTOR` | `ACTIVITY_RECOGNITION` | moving (only when the phone is on me) |
| 5 | Motion level | `TYPE_LINEAR_ACCELERATION`, RMS over 10 s | none | lying on a surface / in hand / carried |
| 6 | Orientation | `TYPE_GRAVITY` → x, y, z | none | lying vs sitting (learned, §11) |
| 7 | Proximity | `TYPE_PROXIMITY` | none | pocket or face-down |
| 8 | Light | `TYPE_LIGHT` | none | dark room |
| 9 | Audio | `AudioManager.isMusicActive()`, output device types | none | background listening, headphones |
| 10 | Charging | battery broadcast | none | phone on its charger |
| 11 | Chore mode | NFC tag, NDEF URI `qadam://tag/<name>` | NFC | I declared chores by walking to a tag |
| 12 | Labels | experience-sampling notification (Phase 0) | notifications | ground truth |

(RMS — root mean square, the typical size of the shaking. Ground truth — what I was really doing, used to check the detector.)

**Sensors on my phone.** The published specs list an accelerometer, gyroscope, proximity sensor, compass and light sensor, but no step sensor. So:
- At startup the app checks every sensor it uses and shows the result on the debug screen. This is Phase 0's first check.
- **No `TYPE_STEP_DETECTOR`** → steps come from the accelerometer: a step detector in `core` that finds peaks in the acceleration magnitude at walking rhythm (about 1–2.5 per second). Unit-tested on synthetic walking, lying and phone-on-a-table signals.
- **No `TYPE_GRAVITY` or `TYPE_LINEAR_ACCELERATION`** → derive both from the accelerometer with a low-pass filter: gravity is the slow part, linear acceleration the rest.
- The fallback keeps the accelerometer on at a low rate whenever the screen is on or audio plays, and off otherwise. Battery use is measured in Phase 0's check 2.

**Sampling.** Signals 5–8 only while the screen is on or audio plays, around 10 Hz, batched. The step detector always (it is a cheap hardware sensor). The app stores 30-second summary rows, never raw samples.

**Summary row** — every 30 s while the screen is on or audio plays; every 5 min otherwise:
`time, screen_on, app, app_category, session_s, scrolls, taps, steps, steps_last_5min, motion_rms_mean, motion_rms_p90, gravity_x, gravity_y, gravity_z, proximity_near, lux, audio, headphones, charging, chore_mode, window, state, level, label`

**Event rows:** intervention shown and my response; overrides with reason; tag taps; service started or stopped; every settings change.

## 5. App categories

- **Drift apps** — my list (§0). Only these can trigger anything.
- **Always allowed** — phone, SMS and the messengers I name, clock and alarms, maps, banking and authenticator, camera, study apps (PDF reader, Anki), Markor, Qadam itself. Never blocked.
- **Neutral** — everything else. Logged, never blocked.

## 6. States — v1 rules

Thresholds are **placeholders until Phase 1** replaces them with numbers from my data.

- **CHORE** — 30 min after a tag tap; another tap extends it. Ends early if drift signals hold for 5 min (screen on, scrolling, no steps).
- **MOVING** — ≥ 20 steps in the last 3 min. Nothing fires.
- **DRIFT_SCREEN** — a drift app in front for ≥ 10 min without a break, < 10 steps in the last 5 min, not CHORE.
- **DRIFT_SCROLL** — in a drift app, ≥ 6 scrolls per minute for 3 min. Reaches Level 1 sooner.
- **DRIFT_AUDIO** — audio playing, screen off, no steps for ≥ 15 min, not CHORE.
- **NIGHT** — after bedtime, a drift app starts at Level 2.
- **DANGER WINDOW** — the meal windows (§0): the time thresholds above are halved.

**Reset to Level 0:** ≥ 50 steps, or the screen off for 10 min with no audio.

## 7. The ladder

**Level 1 — Nudge.**
- One vibration and a small card at the bottom of the screen (accessibility overlay), over the app.
- Text: my if-then plan for this window (§0).
- Buttons:
  - **Getting up** — closes it. If no steps follow within 2 min → Level 2.
  - **5 more minutes** — at most once an hour.
  - **Wrong, I'm not drifting** — logs a false positive, snoozes 15 min.

**Level 2 — Friction.** After 3 min if Level 1 changed nothing, or at a second trigger within 30 min.
- Full-screen overlay. To get 5 minutes, I type my next concrete action (at least 3 words).
- Optional grayscale. It needs a one-time grant from the laptop: `adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS`.
- Audio keeps playing only once steps start.

**Level 3 — Stop.** After 5 more minutes.
- Pause audio (request audio focus), go to the home screen, block drift apps for 20 min (the overlay covers any drift app that opens).
- Unlock: tap any Qadam tag.
- Hard override: hold a button for 30 s and type a reason. At most 2 a day, logged.

**Never blocked:** calls, alarms, Settings, the always-allowed list.

## 8. Commitment rules

- **Tightening** takes effect at once.
- **Loosening** (a higher threshold, removing a drift app, switching a level off) takes effect after 24 h, and shows as "pending until …".
- **Pause:** "pause 1 hour" at most once a day, logged. Longer pauses follow the 24-hour rule.
- If the accessibility service or the foreground service is switched off, that is logged, and the next morning's summary notification shows for how long.
- **Honest limit:** I can always uninstall the app. The design goal is only that getting up is quicker than defeating it.

## 9. NFC tags

- NTAG213 or NTAG215 stickers. The app writes them itself (§10, Tags screen).
- **Place them far from the bed**, ideally outside my room: next to the kitchen sink or fridge, by the laundry. Never on the desk or near the bed: an unlock reachable from the bed is no unlock.
- A tap starts 30 min of chore mode (§6) and lifts a Level 3 block (§7).

## 10. Screens

1. **Today** — drift minutes, triggers, chore minutes, current state. In Phase 0 also a **live debug view of every signal**. That view is how we check each sensor works.
2. **Rules** — drift and allowed apps, thresholds, bedtime, meal windows, if-then plans, pending loosenings.
3. **Tags** — write and test tags.
4. **Data** — export CSV (through Android's file picker), delete all data, retention (summary rows kept 30 days, then only daily totals).
5. **Permissions** — each permission with its status and a button to its settings page.

---

## 11. Phases

**Phase 0 — Logger. No interventions.**
- Build: the foreground service; signals 1–11; summary and event rows; tag writing and reading; the screens above; CSV export.
- Experience sampling: about 5 random notifications a day while the screen is on — "Right now: chores / studying / eating / resting on purpose / drifting / other." One tap, no app opening.
- Checks:
  1. It runs 3 days without Android killing it.
  2. Battery use (Settings → Battery) stays around 5 % a day or less.
  3. The exported CSV opens on the laptop, with labels in it.
  4. The APK's permission list has no INTERNET.

**Phase 1 — Read my data, on the laptop.**
- A Python script reads the CSV, plots each signal for my labelled periods (drifting against chores against studying), and proposes thresholds.
- For each rule it prints how many drifting minutes the rule would have caught, and how many chore minutes it would have hit by mistake.
- The agent writes the script; the agent doesn't read the CSV. Either I run the script myself, or Pi runs with the local model (see the chat answer of 21 Oct).
- Output: the numbers that replace the placeholders in §6.

**Phase 2 — Level 1 only, for one week.**
- Checks: under one false positive a day ("Wrong" button); my if-then plans written.

**Phase 3 — The full ladder:** Levels 2–3, the override, the 24-hour rule, the tag unlock.
- Checks: every scenario in §12.

**Phase 4 (optional) — A learned detector.** Only if the Phase 3 rules misfire too often.
- Train a small decision tree or logistic regression on my labels, on the laptop (scikit-learn), and write its rules into Kotlin.
- This is the part where "local AI" earns a place: a model trained only on my data, running only on my phone.

**Phase 5 (optional) — Weekly review.** A script turns the export into a note in Obsidian: drift minutes per day, triggers by time of day, overrides, false positives, chore minutes.

## 12. Test scenarios — by hand on the phone

1. Lie on the bed and scroll PipePipe → Level 1 by about 10 min; sooner with heavy scrolling.
2. Walk to the kitchen, tap the tag, prop the phone up, wash dishes for 20 min with a video playing → nothing fires.
3. Lie down with audio and the screen off for 15 min → Level 1 (audio version).
4. Phone in pocket, walking with audio → nothing fires.
5. At Level 3 → only a tag or the 30-second override unlocks.
6. A call during Level 3 → the call works.
7. Reboot → the service starts again by itself.
8. Loosen a threshold → it shows "active tomorrow at hh:mm".

## 13. Known hard parts — for the agent

- **Android kills background work, and HiOS (Tecno) is strict about it.** Use a foreground service. The Permissions screen lists these phone settings for me, with a status wherever Android can report it:
  - Settings → Battery Lab → Battery Saver Settings → turn off App Power Saving Management for Qadam;
  - Phone Master → Auto-start Management → allow Qadam;
  - lock Qadam in the recent-apps list (the lock icon on its card);
  - Android's own battery setting for Qadam → Unrestricted.

  Menu names change between HiOS versions. Phase 0's check 1 (three days without being killed) is the real test.
- **Android 14 and later** require every foreground service to declare a type (`health` or `specialUse` here). Check the current documentation.
- **Sensors in the background:** Android stops continuous sensors for background apps, so sensor listening lives inside the foreground service.
- **Sideloaded apps:** Android may grey out the accessibility switch ("Restricted setting"). Fix: App info → ⋮ → Allow restricted settings. Installing with `adb` is the planned route; adb installs are also exempt from Google's developer verification.
- **Libraries can add INTERNET** through manifest merging. Remove it with `tools:node="remove"` and check the final APK every build.
- **Pausing audio** by requesting audio focus works only with apps that respect focus. Test with PipePipe. Reading media sessions would need notification access, another restricted permission: avoid it unless the focus approach fails.

## 14. Success after four weeks

- Drift minutes per day lower than the Phase 0 baseline — measured, not felt.
- Chore listening unchanged.
- The app still installed.

---

## 15. How it gets built

**Route A — Pi in Docker** (the main route).
- **Code folder:** `~/Code/qadam`, not inside `~/Study`. Obsidian shouldn't index build files, and the course vault stays clean.
- **Image:** a new image `pi-android` = `pi-sandbox` + a JDK (17 or 21) + Android command-line tools, platform and build-tools. Pi checks the current versions.
- **Launcher:** `code-pi qadam`, like `study-pi`, but mounting `~/Code/qadam` at `/workspace`. The first Gradle build downloads about 1–2 GB from Google's Maven and Maven Central.
- **On the host, not in the container:** `sudo apt install adb`. On the phone: Developer options → USB debugging. Then `adb devices`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Build the image and launcher as a Pi Lab lab: "lab: android build image".

**Route B — Claude Code, until 25 Oct.** Same rule as Pi: inside a container or in its cloud version, never with my full desktop rights. Copy `AGENTS.md` as `CLAUDE.md`. Push to a private repo; no exported data ever goes in the repo.

**First message to the agent:**
> Read SPEC.md and AGENTS.md. Start Phase 0, step 1: an empty Compose app that builds, with INTERNET removed and the permission check working. Plan first, then wait for my go.

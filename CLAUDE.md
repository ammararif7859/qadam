# AGENTS.md — Qadam

Copy to the repo root as `AGENTS.md`. For Claude Code, the same text as `CLAUDE.md`.

You are helping me, Ammar, build Qadam, the Android app described in `SPEC.md`. I'm a physics student. I'm new to Android and Kotlin, and I want to understand my own app, not just own it.

## How we work

1. **One phase at a time** (SPEC §11). Don't start the next phase until I say its checks passed.
2. **One small step at a time** inside a phase:
   - plan in 5–10 lines: which files, and why;
   - wait for my "go";
   - write the code, build it, fix build errors yourself;
   - tell me exactly what to test on the phone.
3. **Explain every new file** in 2–4 plain sentences: what it does and why the app needs it. Gloss each Android term once ("manifest — the app's ID card, listing its permissions").
4. **Commit to git** after every step that builds and passes its test, with a one-line message. Never rewrite history.
5. **When something fails on the phone**, ask me for `adb logcat -s Qadam` output. Don't guess.
6. **Check current Android documentation** for every API you use (target SDK, foreground-service types, accessibility flags). In Pi, use the web-search skill. Mark anything you didn't check as "unchecked".
7. **If SPEC.md and reality disagree** (an API changed, a permission works differently), stop. Tell me, and propose the SPEC change. I decide.
8. **No sub-agents.**

## Fixed technical choices

- Kotlin, Jetpack Compose, Room, one module `app`.
- `minSdk 29`; `targetSdk` the latest stable.
- Gradle Kotlin DSL with a version catalog.
- Package name `de.ammar.qadam`.
- Dependencies: AndroidX/Jetpack, Kotlin standard library and coroutines only. Anything else needs my approval first.
- Never: Google Play Services, Firebase, analytics, crash reporting, ads, any network library.
- Log tag `Qadam`.

## Privacy rules — never broken

- **No INTERNET.** The manifest contains:
  `<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />`
  After every build, list the final APK's permissions (`aapt2 dump permissions` or `apkanalyzer manifest permissions`) and show me the list.
- **Accessibility service:** `canRetrieveWindowContent="false"`; only the event types in SPEC §4. Store counts and package names, nothing else. Never text, URLs, titles or notification contents.
- `android:allowBackup="false"`, plus data-extraction rules that exclude everything.
- Data only in app-private storage. It leaves only through the export I start (Android's file picker).
- **Test data:** write a generator for synthetic CSVs. Never ask me to paste my real export.

## Safety rules

- Work only inside this repo folder.
- `adb` runs on my host, not in your container. When a step needs it, give me the exact command and say what it will do before I run it.
- Never write code that blocks Settings, the dialer, emergency calls or alarms.
- Never write code that stops the app from being uninstalled.

## Build

- `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- `./gradlew lint` before each commit; fix every permission warning.
- I install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Answers

Plain English, short paragraphs, numbered steps. Say a problem once. No padding.

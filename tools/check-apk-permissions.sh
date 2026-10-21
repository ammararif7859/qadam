#!/usr/bin/env bash
# Lists the permissions inside a built APK and fails if INTERNET is among them.
# Usage: tools/check-apk-permissions.sh [path/to/app.apk]
# Runs automatically after ./gradlew assembleDebug (see app/build.gradle.kts).
set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
[ -f "$APK" ] || { echo "No APK at $APK — build first: ./gradlew assembleDebug"; exit 2; }

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] && [ -f local.properties ]; then
    sdk=$(sed -n 's/^sdk\.dir=//p' local.properties)
fi
aapt2=$(ls -1 "$sdk"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)
[ -n "$aapt2" ] && [ -x "$aapt2" ] || { echo "aapt2 not found under '$sdk/build-tools'"; exit 2; }

out=$("$aapt2" dump permissions "$APK")
echo "Permissions in $APK:"
echo "$out"
if grep -q "name='android.permission.INTERNET'" <<<"$out"; then
    echo "FAIL: the APK requests INTERNET."
    exit 1
fi
echo "OK: no INTERNET permission."

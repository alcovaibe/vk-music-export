#!/usr/bin/env bash
# Preserve screenshots even when the instrumentation check fails.
set -uo pipefail
adb shell wm size 1080x2400
adb shell wm density 440
./gradlew connectedDebugAndroidTest --stacktrace
result=$?
adb pull /sdcard/Android/data/app.alcovaibe.vkmusicexport/files/screenshots/ interface-screenshots/ || true
exit "$result"

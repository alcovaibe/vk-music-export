#!/usr/bin/env bash
# Preserve screenshots even when the instrumentation check fails.
set -uo pipefail
adb shell wm size 1080x2400
adb shell wm density 440
./gradlew assembleDebug assembleDebugAndroidTest --stacktrace
build_result=$?
if [ "$build_result" -ne 0 ]; then exit "$build_result"; fi
# AVD's launcher can time out while Gradle competes for CPU during the first build.
# Stop that unrelated background launcher before starting the app under test.
adb shell am force-stop com.android.launcher3
./gradlew connectedDebugAndroidTest --stacktrace
result=$?
adb pull /data/local/tmp/vk-music-export-screenshots/ interface-screenshots/ || true
exit "$result"

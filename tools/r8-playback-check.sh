#!/usr/bin/env bash
set -euo pipefail
api=${1:?Expected API}
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = "$api"
mkdir -p r8-evidence
adb logcat -c
adb logcat -v raw -s VKR8Bench:I '*:S' > r8-evidence/playback.log &
reader=$!
trap 'kill "$reader" 2>/dev/null || true' EXIT
./gradlew --dependency-verification strict -Pr8PlaybackCheck=true connectedReleaseAndroidTest -Pkotlin.compiler.execution.strategy=in-process --stacktrace
kill "$reader" 2>/dev/null || true
wait "$reader" 2>/dev/null || true
trap - EXIT
python3 tools/verify-r8-evidence.py "$api"
# AGP may remove the tested package after instrumentation; reinstall the same minified bytes.
adb install -r app/build/outputs/apk/release/app-release.apk
# Keep observed cold/warm startup and graphics data, without inferring physical-device budgets.
for sample in 1 2 3 4 5; do
  adb shell am force-stop app.alcovaibe.vkmusicexport
  adb shell am start -W -n app.alcovaibe.vkmusicexport/.MainActivity > "r8-evidence/cold-$sample.txt"
  adb shell input keyevent KEYCODE_HOME
  adb shell am start -W -n app.alcovaibe.vkmusicexport/.MainActivity > "r8-evidence/warm-$sample.txt"
done
adb shell dumpsys gfxinfo app.alcovaibe.vkmusicexport reset > /dev/null
for sample in 1 2 3 4 5; do adb shell input swipe 360 1200 360 450 250; done
adb shell dumpsys gfxinfo app.alcovaibe.vkmusicexport framestats > r8-evidence/frames.txt
adb shell dumpsys meminfo app.alcovaibe.vkmusicexport > r8-evidence/memory.txt
python3 tools/summarize-r8-performance.py "$api"

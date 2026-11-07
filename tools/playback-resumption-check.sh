#!/usr/bin/env bash
# Disposable AVD only: persisted queue must survive actual process death, not a singleton reset.
set -euo pipefail
expected_api=${1:?Expected API required}
serial=$(adb get-serialno | tr -d '\r')
[[ "$serial" == emulator-* ]]
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" == "$expected_api" ]]
root=interface-diagnostics/playback-restart
mkdir -p "$root"
package=app.alcovaibe.vkmusicexport
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
trap 'adb logcat -d -b crash > "$root/crash.txt" || true; adb logcat -d -t 6000 > "$root/logcat.txt" || true' EXIT
for phase in seed resume active cleared; do
  adb shell am instrument -w -r -e playbackPhase "$phase" -e class app.alcovaibe.vkmusicexport.PlaybackRestartDeviceTest "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$root/$phase.txt"
  cat "$root/$phase.txt"
  grep -F 'OK (1 test)' "$root/$phase.txt"
  adb shell am force-stop "$package"
done
adb exec-out run-as "$package" cat files/playback-restart-proof.json > "$root/proof.json"
python3 tools/verify-playback-resumption.py "$root" "$expected_api"

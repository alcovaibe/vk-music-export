#!/usr/bin/env bash
# Preserve screenshots even when the instrumentation check fails.
set -uo pipefail
expected_api=${1:?Expected Android API level is required}
actual_api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [[ "$actual_api" != "$expected_api" ]]; then
  echo "Wrong emulator API: expected $expected_api, observed $actual_api." >&2
  exit 2
fi
echo "Verified emulator API: $actual_api."
mkdir -p interface-diagnostics
free -m > interface-diagnostics/host-memory.txt
adb shell df -h /data
# boot_completed can be set while PackageManager is still restarting after first boot.
# Wait for a stable service before asking Gradle to install the APKs.
stable=0
for attempt in $(seq 1 30); do
  if timeout 10 adb shell cmd package path android > interface-diagnostics/package-ready.txt 2>&1 &&
     grep -q '^package:' interface-diagnostics/package-ready.txt; then
    stable=$((stable+1))
    if [[ "$stable" == 3 ]]; then break; fi
  else
    stable=0
  fi
  sleep 2
done
if [[ "$stable" != 3 ]]; then
  adb logcat -d -b crash > interface-diagnostics/crash.txt || true
  echo 'Emulator PackageManager did not become stable before instrumentation.' >&2
  exit 2
fi
adb shell wm size 720x1600
adb shell wm density 294
# AVD's launcher can time out while Gradle competes for CPU during the first build.
# Stop that unrelated background launcher before starting the app under test.
adb shell am force-stop com.android.launcher3
# Do not let an old report satisfy this run if instrumentation never starts.
rm -rf app/build/outputs/androidTest-results/connected app/build/reports/androidTests/connected
./gradlew connectedDebugAndroidTest -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' --stacktrace
result=$?
if [[ "$result" == 0 ]]; then
  python3 tools/verify-instrumentation.py app/build/outputs/androidTest-results/connected
  result=$?
fi
if [[ "$result" != 0 ]]; then
  free -m > interface-diagnostics/host-memory-after.txt
  timeout 15 adb logcat -d -b crash > interface-diagnostics/crash.txt || true
  timeout 15 adb shell dumpsys meminfo > interface-diagnostics/device-memory.txt || true
  # A fresh disposable AVD can show a framework ANR above the app. Keep the
  # process/window evidence instead of treating missing focus as an app result.
  adb shell dumpsys window windows | python3 -c 'import sys; print("".join(x for x in sys.stdin if "mCurrentFocus" in x or "mFocusedApp" in x))' || true
  adb logcat -d -b events | python3 -c 'import sys; print("".join(x for x in sys.stdin if "am_anr" in x or "am_crash" in x)[-12000:])' || true
fi
adb pull /data/local/tmp/vk-music-export-screenshots/ interface-screenshots/ || true
exit "$result"

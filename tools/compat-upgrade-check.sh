#!/usr/bin/env bash
# Verify data preservation between two API 29-compatible debug APKs on a disposable emulator.
set -euo pipefail
api=${1:?Android API is required}
[[ "$api" =~ ^(29|30|31|32|33)$ ]] || { echo "Not an API 29–33 test" >&2; exit 2; }
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" == "$api" ]] || exit 2
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || {
  echo "Refusing to modify a physical device" >&2;exit 2;
}
package=app.alcovaibe.vkmusicexport
baseline=compatibility-apks/baseline.apk
upgrade=compatibility-apks/upgrade.apk
[[ -s "$baseline" && -s "$upgrade" ]] || { echo "Missing compatible debug APK pair" >&2;exit 2; }
mkdir -p interface-diagnostics
# Both APKs are assembled from this branch with minSdk 29. Neither is a historical release.
adb install -r "$baseline"
adb shell am start -W -n "$package/.MainActivity"
adb shell am force-stop "$package"
adb shell run-as "$package" mkdir -p files
adb shell run-as "$package" touch files/api29-upgrade-checkpoint
adb shell run-as "$package" ls -l files/api29-upgrade-checkpoint > interface-diagnostics/upgrade-before.txt
adb install -r "$upgrade"
adb shell run-as "$package" ls -l files/api29-upgrade-checkpoint > interface-diagnostics/upgrade-after.txt
adb shell dumpsys package "$package" | grep 'versionCode=2000000000' > interface-diagnostics/upgrade-version.txt
adb shell am start -W -n "$package/.MainActivity"
adb shell am force-stop "$package"
echo "PASS: API $api compatible debug APK version upgrade preserved private application data." | tee interface-diagnostics/compat-upgrade.txt

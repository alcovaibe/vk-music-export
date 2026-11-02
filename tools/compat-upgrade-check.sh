#!/usr/bin/env bash
# Verify data preservation between two API 29-compatible debug APKs on a disposable emulator.
set -euo pipefail
api=${1:?Android API is required}
[[ "$api" =~ ^(29|30|31|32|33)$ ]] || { echo "Not an API 29–33 test" >&2; exit 2; }

# AVDs sometimes exit immediately after instrumentation. Never let adb wait
# indefinitely for a disappeared emulator and consume the entire CI job timeout.
adb_checked() {
  if ! timeout --signal=TERM --kill-after=5s 90s adb "$@"; then
    echo "API $api: adb command failed or timed out (emulator may be offline): adb $*" >&2
    return 1
  fi
}
if ! timeout 15s adb get-state | grep -qx device; then
  echo "API $api: emulator is not online before upgrade verification." >&2
  exit 2
fi
[[ "$(adb_checked shell getprop ro.build.version.sdk | tr -d '\r')" == "$api" ]] || {
  echo "Unexpected emulator API for upgrade check." >&2;exit 2;
}
[[ "$(adb_checked shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || {
  echo "Refusing to modify a physical device" >&2;exit 2;
}
package=app.alcovaibe.vkmusicexport
baseline=compatibility-apks/baseline.apk
upgrade=compatibility-apks/upgrade.apk
[[ -s "$baseline" && -s "$upgrade" ]] || { echo "Missing compatible debug APK pair" >&2;exit 2; }
mkdir -p interface-diagnostics
# Both APKs are assembled from this branch with minSdk 29. Neither is a historical release.
adb_checked install -r "$baseline"
adb_checked shell am start -W -n "$package/.MainActivity"
adb_checked shell am force-stop "$package"
adb_checked shell run-as "$package" mkdir -p files
adb_checked shell run-as "$package" touch files/api29-upgrade-checkpoint
adb_checked shell run-as "$package" ls -l files/api29-upgrade-checkpoint > interface-diagnostics/upgrade-before.txt
adb_checked install -r "$upgrade"
adb_checked shell run-as "$package" ls -l files/api29-upgrade-checkpoint > interface-diagnostics/upgrade-after.txt
adb_checked shell dumpsys package "$package" | grep 'versionCode=2000000000' > interface-diagnostics/upgrade-version.txt
adb_checked shell am start -W -n "$package/.MainActivity"
adb_checked shell am force-stop "$package"
# This check runs before instrumentation. Restore a fresh installation so the
# normal debug APK (lower versionCode) can be installed without a downgrade or
# stale test state. We only uninstall on disposable AVDs verified above.
adb_checked uninstall "$package"
echo "PASS: API $api compatible debug APK version upgrade preserved private application data." | tee interface-diagnostics/compat-upgrade.txt

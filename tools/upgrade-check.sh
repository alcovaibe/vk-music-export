#!/usr/bin/env bash
# Disposable rooted AVD only. No device, account or network access is needed.
set -euo pipefail
package=app.alcovaibe.vkmusicexport
appdata=/data/user/0/$package
mkdir -p upgrade-results
trap 'result=$?; echo "Upgrade check failed at line $LINENO (exit $result)"; adb logcat -d -b crash > upgrade-results/crash.txt || true; exit "$result"' ERR
adb root
adb wait-for-device
adb shell wm size 720x1600
adb shell wm density 294
adb shell am force-stop com.android.launcher3
adb install upgrade-input/previous.apk
adb shell am start -W -n "$package/.MainActivity"
adb shell am force-stop "$package"
uid=$(adb shell stat -c %u "$appdata" | tr -d '\r')
[[ "$uid" =~ ^[0-9]+$ ]]
adb push tools/fixtures/upgrade-collection.json /data/local/tmp/upgrade-collection.json
adb shell "mkdir -p $appdata/files; cp /data/local/tmp/upgrade-collection.json $appdata/files/collection.json; chown $uid:$uid $appdata/files/collection.json; chmod 600 $appdata/files/collection.json; restorecon -RF $appdata"
expected=$(sha256sum tools/fixtures/upgrade-collection.json | cut -d' ' -f1)
assert_collection() {
  actual=$(adb shell "sha256sum $appdata/files/collection.json" | cut -d' ' -f1 | tr -d '\r')
  test "$actual" = "$expected"
}
assert_visible() {
  # Give Compose/WebView startup time, but fail if the saved source never appears.
  for attempt in $(seq 1 10); do
    adb shell uiautomator dump /sdcard/upgrade-window.xml >/dev/null
    adb pull /sdcard/upgrade-window.xml "upgrade-results/$1.xml" >/dev/null
    if grep -q 'UpgradeCheckFixture' "upgrade-results/$1.xml"; then return; fi
    sleep 2
  done
  echo 'Saved collection was not rendered by the release APK.'
  adb logcat -d -b crash > upgrade-results/crash.txt
  return 1
}
adb shell am start -W -n "$package/.MainActivity"
assert_visible before
adb shell am force-stop "$package"
assert_collection
# This is the operation owners perform: replace the installed APK, never uninstall or clear data.
adb install -r upgrade-input/current.apk
assert_collection
adb shell dumpsys package "$package" > upgrade-results/package-after.txt
python3 - <<'PY'
import json, re
from pathlib import Path
info = json.loads(Path('release-output/build-info.json').read_text())
package = Path('upgrade-results/package-after.txt').read_text()
assert re.search(r'versionCode=' + str(info['versionCode']) + r'\b', package)
assert 'DEBUGGABLE' not in package
PY
adb shell am start -W -n "$package/.MainActivity"
assert_visible after
assert_collection
adb exec-out screencap -p > upgrade-results/updated-release.png
printf 'PASS: signed release -> higher-version signed release; collection intact and displayed.\n' | tee upgrade-results/result.txt
cp upgrade-results/result.txt release-output/upgrade-check.txt

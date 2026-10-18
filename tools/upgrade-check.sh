#!/usr/bin/env bash
# Disposable rooted AVD only. No device, account or network access is needed.
set -euo pipefail
serial=$(adb get-serialno | tr -d '\r')
if [[ "$serial" != emulator-* ]]; then
  echo 'Refusing to overwrite a collection on a physical device. Use a disposable Android emulator.' >&2
  exit 2
fi
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
    # Emulator framework ANRs can cover the app after resizing the fresh AVD.
    # Only System UI / Quickstep dialogs are handled. App ANRs remain test failures.
    tap=$(python3 tools/emulator-dialog.py "upgrade-results/$1.xml")
    if [[ -n "$tap" ]]; then
      echo "Dismissing an emulator framework dialog; app ANRs are never dismissed."
      read -r x y <<< "$tap"
      adb shell input tap "$x" "$y"
      continue
    fi
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
# A real process restart must retain the previous process journal. No account/network needed.
adb shell am force-stop "$package"
adb shell am start -W -n "$package/.MainActivity"
assert_visible restarted
adb pull "$appdata/no_backup/diagnostics" upgrade-results/diagnostics >/dev/null
python3 - <<'PYDIAG'
import json
from pathlib import Path
runs=sorted(Path('upgrade-results/diagnostics').glob('*'))
assert len(runs)>=2, 'Previous process diagnostic journal missing'
for run in runs[-2:]:
    events=[]
    for part in run.glob('*.ndjson'):
        for line in part.read_text().splitlines():
            try: events.append(json.loads(line))
            except json.JSONDecodeError: pass # Interrupted tail is explicitly recoverable.
    assert any(e.get('type')=='application_create' for e in events), 'Startup event missing'
    assert all(e['run_id']==run.name and isinstance(e['seq'],int) for e in events)
assert sum(p.stat().st_size for p in Path('upgrade-results/diagnostics').rglob('*') if p.is_file())<=20_000_000
Path('upgrade-results/diagnostic-restart.txt').write_text('PASS: journal retained across actual force-stop/restart.\n')
PYDIAG
adb exec-out screencap -p > upgrade-results/updated-release.png
printf 'PASS: signed release -> higher-version signed release; collection intact and displayed.\n' | tee upgrade-results/result.txt
cp upgrade-results/result.txt release-output/upgrade-check.txt
